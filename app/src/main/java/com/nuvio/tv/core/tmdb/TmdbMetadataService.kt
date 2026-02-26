package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbEpisode
import com.nuvio.tv.data.remote.api.TmdbImage
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCast
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCrew
import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.core.device.DeviceCapabilities
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaCompany
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbMetadataService"
private const val TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c"

@Singleton
class TmdbMetadataService @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val deviceCapabilities: DeviceCapabilities
) {
    // In-memory caches
    private val enrichmentCache = ConcurrentHashMap<String, TmdbEnrichment>()
    private val episodeCache = ConcurrentHashMap<String, Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>()
    private val personCache = ConcurrentHashMap<String, PersonDetail>()
    private val moreLikeThisCache = ConcurrentHashMap<String, List<MetaPreview>>()

    suspend fun fetchEnrichment(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en"
    ): TmdbEnrichment? =
        withContext(Dispatchers.IO) {
            val normalizedLanguage = normalizeTmdbLanguage(language)
            val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage"
            enrichmentCache[cacheKey]?.let { return@withContext it }

            val numericId = tmdbId.toIntOrNull() ?: return@withContext null
            val tmdbType = when (contentType) {
                ContentType.SERIES, ContentType.TV -> "tv"
                else -> "movie"
            }

            try {
                val includeImageLanguage = buildString {
                    append(normalizedLanguage.substringBefore("-"))
                    append(",")
                    append(normalizedLanguage)
                    append(",en,null")
                }

                // Fetch details, credits, and images in parallel
                val (details, credits, images, ageRating) = coroutineScope {
                    val detailsDeferred = async {
                        when (tmdbType) {
                            "tv" -> tmdbApi.getTvDetails(numericId, TMDB_API_KEY, normalizedLanguage)
                            else -> tmdbApi.getMovieDetails(numericId, TMDB_API_KEY, normalizedLanguage)
                        }.body()
                    }
                    val creditsDeferred = async {
                        when (tmdbType) {
                            "tv" -> tmdbApi.getTvCredits(numericId, TMDB_API_KEY, normalizedLanguage)
                            else -> tmdbApi.getMovieCredits(numericId, TMDB_API_KEY, normalizedLanguage)
                        }.body()
                    }
                    val imagesDeferred = async {
                        when (tmdbType) {
                            "tv" -> tmdbApi.getTvImages(numericId, TMDB_API_KEY, includeImageLanguage)
                            else -> tmdbApi.getMovieImages(numericId, TMDB_API_KEY, includeImageLanguage)
                        }.body()
                    }
                    val ageRatingDeferred = async {
                        when (tmdbType) {
                            "tv" -> {
                                val ratings = tmdbApi.getTvContentRatings(numericId, TMDB_API_KEY).body()?.results.orEmpty()
                                selectTvAgeRating(ratings, normalizedLanguage)
                            }
                            else -> {
                                val releases = tmdbApi.getMovieReleaseDates(numericId, TMDB_API_KEY).body()?.results.orEmpty()
                                selectMovieAgeRating(releases, normalizedLanguage)
                            }
                        }
                    }
                    Quadruple(
                        detailsDeferred.await(),
                        creditsDeferred.await(),
                        imagesDeferred.await(),
                        ageRatingDeferred.await()
                    )
                }

                val genres = details?.genres?.mapNotNull { genre ->
                    genre.name.trim().takeIf { name -> name.isNotBlank() }
                } ?: emptyList()
                val description = details?.overview?.takeIf { it.isNotBlank() }
                val releaseInfo = details?.releaseDate
                    ?: details?.firstAirDate
                val rating = details?.voteAverage
                val runtime = details?.runtime ?: details?.episodeRunTime?.firstOrNull()
                val countries = details?.productionCountries
                    ?.mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }
                    ?.takeIf { it.isNotEmpty() }
                    ?: details?.originCountry?.takeIf { it.isNotEmpty() }
                val language = details?.originalLanguage?.takeIf { it.isNotBlank() }
                val localizedTitle = (details?.title ?: details?.name)?.takeIf { it.isNotBlank() }
                val productionCompanies = details?.productionCompanies
                    .orEmpty()
                    .mapNotNull { company ->
                        val name = company.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCompany(
                            name = name,
                            logo = buildLogoUrl(company.logoPath)
                        )
                    }
                val networks = details?.networks
                    .orEmpty()
                    .mapNotNull { network ->
                        val name = network.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCompany(
                            name = name,
                            logo = buildLogoUrl(network.logoPath)
                        )
                    }
                val poster = buildPosterUrl(details?.posterPath)
                val backdrop = buildBackdropUrl(details?.backdropPath)

                val logoPath = images?.logos
                    ?.sortedWith(
                        compareByDescending<com.nuvio.tv.data.remote.api.TmdbImage> {
                            it.iso6391 == normalizedLanguage.substringBefore("-")
                        }
                            .thenByDescending { it.iso6391 == "en" }
                            .thenByDescending { it.iso6391 == null }
                    )
                    ?.firstOrNull()
                    ?.filePath

                val logo = buildLogoUrl(logoPath)

                val castMembers = credits?.cast
                    .orEmpty()
                    .mapNotNull { member ->
                        val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = member.character?.takeIf { it.isNotBlank() },
                            photo = buildProfileUrl(member.profilePath),
                            tmdbId = member.id
                        )
                    }

                val creatorMembers = if (tmdbType == "tv") {
                    details?.createdBy
                        .orEmpty()
                        .mapNotNull { creator ->
                            val tmdbPersonId = creator.id ?: return@mapNotNull null
                            val name = creator.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            MetaCastMember(
                                name = name,
                                character = "Creator",
                                photo = buildProfileUrl(creator.profilePath),
                                tmdbId = tmdbPersonId
                            )
                        }
                        .distinctBy { it.tmdbId ?: it.name.lowercase() }
                } else {
                    emptyList()
                }

                val creator = if (tmdbType == "tv") {
                    details?.createdBy
                        .orEmpty()
                        .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }
                } else {
                    emptyList()
                }

                val directorCrew = credits?.crew
                    .orEmpty()
                    .filter { it.job.equals("Director", ignoreCase = true) }

                val directorMembers = directorCrew
                    .mapNotNull { member ->
                        val tmdbPersonId = member.id ?: return@mapNotNull null
                        val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = "Director",
                            photo = buildProfileUrl(member.profilePath),
                            tmdbId = tmdbPersonId
                        )
                    }
                    .distinctBy { it.tmdbId ?: it.name.lowercase() }

                val director = directorCrew
                    .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }

                val writerCrew = credits?.crew
                    .orEmpty()
                    .filter { crew ->
                        val job = crew.job?.lowercase() ?: ""
                        job.contains("writer") || job.contains("screenplay")
                    }

                val writerMembers = writerCrew
                    .mapNotNull { member ->
                        val tmdbPersonId = member.id ?: return@mapNotNull null
                        val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = "Writer",
                            photo = buildProfileUrl(member.profilePath),
                            tmdbId = tmdbPersonId
                        )
                    }
                    .distinctBy { it.tmdbId ?: it.name.lowercase() }

                val writer = writerCrew
                    .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }

                // Only expose either Director or Writer people (prefer Director).
                val hasCreator = creatorMembers.isNotEmpty() || creator.isNotEmpty()
                val hasDirector = directorMembers.isNotEmpty() || director.isNotEmpty()

                val exposedDirectorMembers = when {
                    tmdbType == "tv" && hasCreator -> creatorMembers
                    tmdbType != "tv" && hasDirector -> directorMembers
                    else -> emptyList()
                }
                val exposedWriterMembers = when {
                    tmdbType == "tv" && hasCreator -> emptyList()
                    tmdbType != "tv" && hasDirector -> emptyList()
                    else -> writerMembers
                }

                val exposedDirector = when {
                    tmdbType == "tv" && hasCreator -> creator
                    tmdbType != "tv" && hasDirector -> director
                    else -> emptyList()
                }
                val exposedWriter = when {
                    tmdbType == "tv" && hasCreator -> emptyList()
                    tmdbType != "tv" && hasDirector -> emptyList()
                    else -> writer
                }

                if (
                    genres.isEmpty() && description == null && backdrop == null && logo == null &&
                    poster == null && castMembers.isEmpty() && director.isEmpty() && writer.isEmpty() &&
                    releaseInfo == null && rating == null && runtime == null && countries.isNullOrEmpty() && language == null &&
                    productionCompanies.isEmpty() && networks.isEmpty() && ageRating == null
                ) {
                    return@withContext null
                }

                val enrichment = TmdbEnrichment(
                    localizedTitle = localizedTitle,
                    description = description,
                    genres = genres,
                    backdrop = backdrop,
                    logo = logo,
                    poster = poster,
                    directorMembers = exposedDirectorMembers,
                    writerMembers = exposedWriterMembers,
                    castMembers = castMembers,
                    releaseInfo = releaseInfo,
                    rating = rating,
                    runtimeMinutes = runtime,
                    director = exposedDirector,
                    writer = exposedWriter,
                    productionCompanies = productionCompanies,
                    networks = networks,
                    ageRating = ageRating,
                    countries = countries,
                    language = language
                )
                enrichmentCache[cacheKey] = enrichment
                enrichment
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch TMDB enrichment: ${e.message}", e)
                null
            }
        }

    suspend fun fetchEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String = "en"
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${seasonNumbers.sorted().joinToString(",")}:$normalizedLanguage"
        episodeCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyMap()
        val result = mutableMapOf<Pair<Int, Int>, TmdbEpisodeEnrichment>()

        seasonNumbers.distinct().forEach { season ->
            try {
                val response = tmdbApi.getTvSeasonDetails(numericId, season, TMDB_API_KEY, normalizedLanguage)
                val episodes = response.body()?.episodes.orEmpty()
                episodes.forEach { ep ->
                    val epNum = ep.episodeNumber ?: return@forEach
                    result[season to epNum] = ep.toEnrichment()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch TMDB season $season: ${e.message}")
            }
        }

        if (result.isNotEmpty()) {
            episodeCache[cacheKey] = result
        }
        result
    }

    suspend fun fetchMoreLikeThis(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en",
        maxItems: Int = 12
    ): List<MetaPreview> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage:more_like"
        moreLikeThisCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyList()
        val tmdbType = when (contentType) {
            ContentType.SERIES, ContentType.TV -> "tv"
            else -> "movie"
        }

        val includeImageLanguage = buildString {
            append(normalizedLanguage.substringBefore("-"))
            append(",")
            append(normalizedLanguage)
            append(",en,null")
        }

        try {
            val recommendations = when (tmdbType) {
                "tv" -> tmdbApi.getTvRecommendations(numericId, TMDB_API_KEY, normalizedLanguage).body()
                else -> tmdbApi.getMovieRecommendations(numericId, TMDB_API_KEY, normalizedLanguage).body()
            }

            val rawResults = recommendations?.results
                .orEmpty()
                .filter { it.id > 0 }
            val languageCode = normalizedLanguage.substringBefore("-")
            val sortedResults = rawResults
                .sortedWith(
                    compareByDescending<TmdbRecommendationResult> {
                        it.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                    }
                        .thenByDescending { it.voteCount ?: 0 }
                        .thenByDescending { it.voteAverage ?: 0.0 }
                )
            val qualityFilteredResults = sortedResults.filter { rec ->
                val voteCount = rec.voteCount ?: 0
                val voteAverage = rec.voteAverage ?: 0.0
                val localized = rec.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                localized || voteCount >= 20 || voteAverage >= 6.0
            }
            val recommendationResults = (if (qualityFilteredResults.isNotEmpty()) {
                qualityFilteredResults
            } else {
                sortedResults
            }).take(maxItems.coerceAtLeast(1))

            val items = coroutineScope {
                recommendationResults.map { rec ->
                    async {
                        val recTmdbType = when (rec.mediaType?.trim()?.lowercase()) {
                            "tv" -> "tv"
                            "movie" -> "movie"
                            else -> tmdbType
                        }
                        val recContentType = if (recTmdbType == "tv") ContentType.SERIES else ContentType.MOVIE
                        val title = rec.title?.takeIf { it.isNotBlank() }
                            ?: rec.name?.takeIf { it.isNotBlank() }
                            ?: rec.originalTitle?.takeIf { it.isNotBlank() }
                            ?: rec.originalName?.takeIf { it.isNotBlank() }
                            ?: return@async null

                        val localizedBackdropPath = runCatching {
                            when (recTmdbType) {
                                "tv" -> tmdbApi.getTvImages(rec.id, TMDB_API_KEY, includeImageLanguage).body()
                                else -> tmdbApi.getMovieImages(rec.id, TMDB_API_KEY, includeImageLanguage).body()
                            }
                        }.getOrNull()?.let { images ->
                            selectBestLocalizedImagePath(
                                images = images.backdrops.orEmpty(),
                                normalizedLanguage = normalizedLanguage
                            )
                        }

                        val backdrop = buildBackdropUrl(localizedBackdropPath ?: rec.backdropPath)
                        val fallbackPoster = buildBackdropUrl(rec.posterPath)
                        val releaseInfo = (rec.releaseDate ?: rec.firstAirDate)?.take(4)

                        MetaPreview(
                            id = "tmdb:${rec.id}",
                            type = recContentType,
                            name = title,
                            poster = backdrop ?: fallbackPoster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = backdrop,
                            logo = null,
                            description = rec.overview?.takeIf { it.isNotBlank() },
                            releaseInfo = releaseInfo,
                            imdbRating = rec.voteAverage?.toFloat(),
                            genres = emptyList()
                        )
                    }
                }.awaitAll().filterNotNull()
            }

            moreLikeThisCache[cacheKey] = items
            items
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch recommendations for $tmdbId: ${e.message}")
            emptyList()
        }
    }

    private fun buildPosterUrl(path: String?): String? =
        buildImageUrl(path, deviceCapabilities.tmdbPosterSize)

    private fun buildBackdropUrl(path: String?): String? =
        buildImageUrl(path, deviceCapabilities.tmdbBackdropSize)

    private fun buildProfileUrl(path: String?): String? =
        buildImageUrl(path, deviceCapabilities.tmdbProfileSize)

    private fun buildLogoUrl(path: String?): String? =
        buildImageUrl(path, deviceCapabilities.tmdbLogoSize)

    private fun buildStillUrl(path: String?): String? =
        buildImageUrl(path, deviceCapabilities.tmdbStillSize)

    private fun buildImageUrl(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }

    private fun normalizeTmdbLanguage(language: String?): String {
        return language
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', '-')
            ?: "en"
    }

    private fun selectBestLocalizedImagePath(
        images: List<TmdbImage>,
        normalizedLanguage: String
    ): String? {
        if (images.isEmpty()) return null
        val languageCode = normalizedLanguage.substringBefore("-")
        return images
            .sortedWith(
                compareByDescending<TmdbImage> { it.iso6391 == normalizedLanguage }
                    .thenByDescending { it.iso6391 == languageCode }
                    .thenByDescending { it.iso6391 == "en" }
                    .thenByDescending { it.iso6391 == null }
            )
            .firstOrNull()
            ?.filePath
    }

    suspend fun fetchPersonDetail(
        personId: Int,
        preferCrewCredits: Boolean? = null
    ): PersonDetail? =
        withContext(Dispatchers.IO) {
            val cacheKey = "$personId:${preferCrewCredits?.toString() ?: "auto"}"
            personCache[cacheKey]?.let { return@withContext it }

            try {
                val (person, credits) = coroutineScope {
                    val personDeferred = async {
                        tmdbApi.getPersonDetails(personId, TMDB_API_KEY).body()
                    }
                    val creditsDeferred = async {
                        tmdbApi.getPersonCombinedCredits(personId, TMDB_API_KEY).body()
                    }
                    Pair(personDeferred.await(), creditsDeferred.await())
                }

                if (person == null) return@withContext null

                val preferCrewFilmography = preferCrewCredits ?: shouldPreferCrewCredits(person.knownForDepartment)

                val castMovieCredits = mapMovieCreditsFromCast(credits?.cast.orEmpty())
                val crewMovieCredits = mapMovieCreditsFromCrew(credits?.crew.orEmpty())
                val movieCredits = when {
                    preferCrewFilmography && crewMovieCredits.isNotEmpty() -> crewMovieCredits
                    castMovieCredits.isNotEmpty() -> castMovieCredits
                    else -> crewMovieCredits
                }

                val castTvCredits = mapTvCreditsFromCast(credits?.cast.orEmpty())
                val crewTvCredits = mapTvCreditsFromCrew(credits?.crew.orEmpty())
                val tvCredits = when {
                    preferCrewFilmography && crewTvCredits.isNotEmpty() -> crewTvCredits
                    castTvCredits.isNotEmpty() -> castTvCredits
                    else -> crewTvCredits
                }

                val detail = PersonDetail(
                    tmdbId = person.id,
                    name = person.name ?: "Unknown",
                    biography = person.biography?.takeIf { it.isNotBlank() },
                    birthday = person.birthday?.takeIf { it.isNotBlank() },
                    deathday = person.deathday?.takeIf { it.isNotBlank() },
                    placeOfBirth = person.placeOfBirth?.takeIf { it.isNotBlank() },
                    profilePhoto = buildProfileUrl(person.profilePath),
                    knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() },
                    movieCredits = movieCredits,
                    tvCredits = tvCredits
                )
                personCache[cacheKey] = detail
                detail
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch person detail: ${e.message}", e)
                null
            }
        }

    private fun shouldPreferCrewCredits(knownForDepartment: String?): Boolean {
        val department = knownForDepartment?.trim()?.lowercase() ?: return false
        if (department.isBlank()) return false
        return department != "acting" && department != "actors"
    }

    private fun mapMovieCreditsFromCast(cast: List<TmdbPersonCreditCast>): List<MetaPreview> {
        val seenMovieIds = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenMovieIds.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                val year = credit.releaseDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = buildPosterUrl(credit.posterPath),
                    posterShape = PosterShape.POSTER,
                    background = buildBackdropUrl(credit.backdropPath),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapMovieCreditsFromCrew(crew: List<TmdbPersonCreditCrew>): List<MetaPreview> {
        val seenMovieIds = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenMovieIds.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                val year = credit.releaseDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = buildPosterUrl(credit.posterPath),
                    posterShape = PosterShape.POSTER,
                    background = buildBackdropUrl(credit.backdropPath),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCast(cast: List<TmdbPersonCreditCast>): List<MetaPreview> {
        val seenTvIds = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenTvIds.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                val year = credit.firstAirDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = buildPosterUrl(credit.posterPath),
                    posterShape = PosterShape.POSTER,
                    background = buildBackdropUrl(credit.backdropPath),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCrew(crew: List<TmdbPersonCreditCrew>): List<MetaPreview> {
        val seenTvIds = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenTvIds.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                val year = credit.firstAirDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = buildPosterUrl(credit.posterPath),
                    posterShape = PosterShape.POSTER,
                    background = buildBackdropUrl(credit.backdropPath),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

private fun preferredRegions(normalizedLanguage: String): List<String> {
    val fromLanguage = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
    return buildList {
        if (!fromLanguage.isNullOrBlank()) add(fromLanguage)
        add("US")
        add("GB")
    }.distinct()
}

private fun selectMovieAgeRating(
    countries: List<com.nuvio.tv.data.remote.api.TmdbMovieReleaseDateCountry>,
    normalizedLanguage: String
): String? {
    val preferred = preferredRegions(normalizedLanguage)
    val byRegion = countries.associateBy { it.iso31661?.uppercase(Locale.US) }
    preferred.forEach { region ->
        val rating = byRegion[region]
            ?.releaseDates
            .orEmpty()
            .mapNotNull { it.certification?.trim() }
            .firstOrNull { it.isNotBlank() }
        if (!rating.isNullOrBlank()) return rating
    }
    return countries
        .asSequence()
        .flatMap { it.releaseDates.orEmpty().asSequence() }
        .mapNotNull { it.certification?.trim() }
        .firstOrNull { it.isNotBlank() }
}

private fun selectTvAgeRating(
    ratings: List<com.nuvio.tv.data.remote.api.TmdbTvContentRatingItem>,
    normalizedLanguage: String
): String? {
    val preferred = preferredRegions(normalizedLanguage)
    val byRegion = ratings.associateBy { it.iso31661?.uppercase(Locale.US) }
    preferred.forEach { region ->
        val rating = byRegion[region]?.rating?.trim()
        if (!rating.isNullOrBlank()) return rating
    }
    return ratings
        .mapNotNull { it.rating?.trim() }
        .firstOrNull { it.isNotBlank() }
}

data class TmdbEnrichment(
    val localizedTitle: String?,
    val description: String?,
    val genres: List<String>,
    val backdrop: String?,
    val logo: String?,
    val poster: String?,
    val directorMembers: List<MetaCastMember>,
    val writerMembers: List<MetaCastMember>,
    val castMembers: List<MetaCastMember>,
    val releaseInfo: String?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val director: List<String>,
    val writer: List<String>,
    val productionCompanies: List<MetaCompany>,
    val networks: List<MetaCompany>,
    val ageRating: String?,
    val countries: List<String>?,
    val language: String?
)

data class TmdbEpisodeEnrichment(
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val airDate: String?,
    val runtimeMinutes: Int?
)

private fun TmdbEpisode.toEnrichment(): TmdbEpisodeEnrichment {
    val title = name?.takeIf { it.isNotBlank() }
    val overview = overview?.takeIf { it.isNotBlank() }
    val thumbnail = stillPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w300$it" }
    val airDate = airDate?.takeIf { it.isNotBlank() }
    return TmdbEpisodeEnrichment(
        title = title,
        overview = overview,
        thumbnail = thumbnail,
        airDate = airDate,
        runtimeMinutes = runtime
    )
}
