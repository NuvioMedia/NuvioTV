package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbAggregateCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbCastMember
import com.nuvio.tv.data.remote.api.TmdbCollectionPart
import com.nuvio.tv.data.remote.api.TmdbCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbCrewMember
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbEpisode
import com.nuvio.tv.data.remote.api.TmdbImage
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCast
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCrew
import com.nuvio.tv.data.remote.api.TmdbPersonCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaCompany
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val TAG = "TmdbMetadataService"
private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
private const val TMDB_TRAILER_FALLBACK_LANGUAGE = "en-US"
private const val TMDB_SEASON_REQUEST_CONCURRENCY = 4
private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

@Singleton
class TmdbMetadataService(
    private val tmdbApi: TmdbApi,
    private val ioDispatcher: CoroutineDispatcher
) {
    @Inject
    constructor(tmdbApi: TmdbApi) : this(tmdbApi, Dispatchers.IO)

    // In-memory caches
    private val enrichmentCache = ConcurrentHashMap<String, TmdbEnrichment>()
    private val episodeCache = ConcurrentHashMap<String, Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>()
    private val enrichmentInFlight = ConcurrentHashMap<String, CompletableDeferred<TmdbEnrichment?>>()
    private val episodeInFlight = ConcurrentHashMap<String, CompletableDeferred<Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>>()
    private val personCache = ConcurrentHashMap<String, PersonDetail>()
    private val moreLikeThisCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val entityHeaderCache = ConcurrentHashMap<String, TmdbEntityHeader>()
    private val entityRailCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val entityBrowseCache = ConcurrentHashMap<String, TmdbEntityBrowseData>()

    suspend fun fetchEnrichment(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en"
    ): TmdbEnrichment? =
        withContext(ioDispatcher) {
            val normalizedLanguage = normalizeTmdbLanguage(language)
            val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage"
            enrichmentCache[cacheKey]?.let { return@withContext it }
            enrichmentInFlight[cacheKey]?.let { return@withContext it.await() }

            val numericId = tmdbId.toIntOrNull() ?: return@withContext null
            val requestDeferred = CompletableDeferred<TmdbEnrichment?>()
            enrichmentInFlight.putIfAbsent(cacheKey, requestDeferred)?.let { existing ->
                return@withContext existing.await()
            }
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

                // Fetch details, credits, images, alt titles, and trailers in parallel
                val (details, credits, images, ageRating, altTitles, trailers) = coroutineScope {
                    val detailsDeferred = async {
                        when (tmdbType) {
                            "tv" -> tmdbApi.getTvDetails(numericId, TMDB_API_KEY, normalizedLanguage)
                            else -> tmdbApi.getMovieDetails(numericId, TMDB_API_KEY, normalizedLanguage)
                        }.body()
                    }
                    val creditsDeferred = async {
                        when (tmdbType) {
                            "tv" -> {
                                val aggregate = tmdbApi.getTvAggregateCredits(numericId, TMDB_API_KEY, normalizedLanguage).body()
                                aggregate?.let { mapAggregateCreditsToStandard(it) }
                            }
                            else -> tmdbApi.getMovieCredits(numericId, TMDB_API_KEY, normalizedLanguage).body()
                        }
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
                    val altTitlesDeferred = async {
                        runCatching {
                            val resp = when (tmdbType) {
                                "tv" -> tmdbApi.getTvAlternativeTitles(numericId, TMDB_API_KEY).body()
                                else -> tmdbApi.getMovieAlternativeTitles(numericId, TMDB_API_KEY).body()
                            }
                            (resp?.movieTitles ?: resp?.tvTitles).orEmpty()
                                .mapNotNull { it.title?.trim()?.takeIf(String::isNotBlank) }
                        }.getOrDefault(emptyList())
                    }
                    val trailersDeferred = async {
                        fetchTmdbTrailers(
                            tmdbId = numericId,
                            tmdbType = tmdbType,
                            preferredLanguage = normalizedLanguage
                        )
                    }
                    Sextuple(
                        detailsDeferred.await(),
                        creditsDeferred.await(),
                        imagesDeferred.await(),
                        ageRatingDeferred.await(),
                        altTitlesDeferred.await(),
                        trailersDeferred.await()
                    )
                }

                val needsCastEnglishFallback = !normalizedLanguage.startsWith("en") &&
                    !normalizedLanguage.startsWith("ja") &&
                    !normalizedLanguage.startsWith("ko") &&
                    !normalizedLanguage.startsWith("zh") &&
                    (
                        credits?.cast.orEmpty().any { member ->
                            val name = member.name
                            val original = member.originalName
                            !name.isNullOrBlank() && containsCjkOrHangul(name) && (original.isNullOrBlank() || containsCjkOrHangul(original))
                        } ||
                        credits?.crew.orEmpty().any { member ->
                            val name = member.name
                            val original = member.originalName
                            !name.isNullOrBlank() && containsCjkOrHangul(name) && (original.isNullOrBlank() || containsCjkOrHangul(original))
                        } ||
                        details?.createdBy.orEmpty().any { creatorItem ->
                            val name = creatorItem.name
                            val original = creatorItem.originalName
                            !name.isNullOrBlank() && containsCjkOrHangul(name) && (original.isNullOrBlank() || containsCjkOrHangul(original))
                        }
                    )

                val englishFallbackNames = if (needsCastEnglishFallback) {
                    runCatching {
                        val englishCredits = when (tmdbType) {
                            "tv" -> tmdbApi.getTvAggregateCredits(numericId, TMDB_API_KEY, "en-US").body()?.let { mapAggregateCreditsToStandard(it) }
                            else -> tmdbApi.getMovieCredits(numericId, TMDB_API_KEY, "en-US").body()
                        }
                        val englishTvDetails = if (tmdbType == "tv" && !details?.createdBy.isNullOrEmpty()) {
                            tmdbApi.getTvDetails(numericId, TMDB_API_KEY, "en-US").body()
                        } else null

                        buildMap<Int, String> {
                            englishCredits?.cast?.forEach { member ->
                                val id = member.id
                                val name = member.name?.trim()?.takeIf { it.isNotBlank() }
                                if (id != null && name != null) put(id, name)
                            }
                            englishCredits?.crew?.forEach { member ->
                                val id = member.id
                                val name = member.name?.trim()?.takeIf { it.isNotBlank() }
                                if (id != null && name != null) put(id, name)
                            }
                            englishTvDetails?.createdBy?.forEach { creatorItem ->
                                val id = creatorItem.id
                                val name = creatorItem.name?.trim()?.takeIf { it.isNotBlank() }
                                if (id != null && name != null) put(id, name)
                            }
                        }
                    }.getOrElse {
                        Log.w(TAG, "Failed to fetch English credits fallback for $numericId: ${it.message}")
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }

                val genres = details?.genres?.mapNotNull { genre ->
                    genre.name.trim().takeIf { name -> name.isNotBlank() }
                } ?: emptyList()
                val description = details?.overview?.takeIf { it.isNotBlank() }
                val status = details?.status?.trim()?.takeIf { it.isNotBlank() }
                val releaseInfo = if (tmdbType == "tv") {
                    details?.firstAirDate.yearPart()?.let { startYear ->
                        buildShowYearRange(startYear, details?.lastAirDate.yearPart(), status)
                    }
                } else {
                    details?.releaseDate.yearPart()
                }
                val rating = details?.voteAverage
                val runtime = details?.runtime ?: details?.episodeRunTime?.firstOrNull()
                val countries = details?.productionCountries
                    ?.mapNotNull { it.iso31661?.trim()?.uppercase()?.takeIf { code -> code.isNotBlank() } }
                    ?.takeIf { it.isNotEmpty() }
                    ?: details?.originCountry?.takeIf { it.isNotEmpty() }
                val language = details?.originalLanguage?.takeIf { it.isNotBlank() }
                val rawLocalizedTitle = (details?.title ?: details?.name)?.takeIf { it.isNotBlank() }
                val originalTitle = (details?.originalTitle ?: details?.originalName)
                    ?.trim()?.takeIf { it.isNotBlank() }

                // If TMDB returned the original title because no translation
                // exists for the user's language, treat as no localized title
                // so the caller keeps the addon-provided title instead.
                val droppedUntranslatedTitle = rawLocalizedTitle != null &&
                    originalTitle != null &&
                    rawLocalizedTitle == originalTitle &&
                    !normalizedLanguage.startsWith("en") &&
                    language != null &&
                    !normalizedLanguage.startsWith(language)
                var localizedTitle = if (droppedUntranslatedTitle) null else rawLocalizedTitle
                val isCjkLanguage = normalizedLanguage.startsWith("ja") ||
                    normalizedLanguage.startsWith("ko") ||
                    normalizedLanguage.startsWith("zh")
                if (
                    normalizedLanguage != "en" &&
                    !isCjkLanguage &&
                    containsCjkOrHangul(localizedTitle ?: originalTitle ?: "")
                ) {
                    val englishTitle = runCatching {
                        when (tmdbType) {
                            "tv" -> tmdbApi.getTvDetails(numericId, TMDB_API_KEY, "en").body()
                            else -> tmdbApi.getMovieDetails(numericId, TMDB_API_KEY, "en").body()
                        }?.let { englishDetails ->
                            (englishDetails.title ?: englishDetails.name)
                                ?.trim()
                                ?.takeIf { it.isNotBlank() && !containsCjkOrHangul(it) }
                        }
                    }.getOrNull()
                    localizedTitle = resolveDisplayLabel(
                        localized = rawLocalizedTitle,
                        original = originalTitle,
                        fallbackEnglish = englishTitle,
                        preferredLanguage = normalizedLanguage
                    )
                }
                val productionCompanies = details?.productionCompanies
                    .orEmpty()
                    .mapNotNull { company ->
                        val name = company.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCompany(
                            name = name,
                            logo = buildImageUrl(company.logoPath, size = "w300"),
                            tmdbId = company.id
                        )
                    }
                val networks = details?.networks
                    .orEmpty()
                    .mapNotNull { network ->
                        val name = network.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCompany(
                            name = name,
                            logo = buildImageUrl(network.logoPath, size = "w300"),
                            tmdbId = network.id
                        )
                    }
                val poster = buildImageUrl(details?.posterPath, size = "w500")
                val backdrop = buildImageUrl(details?.backdropPath, size = "w1280")

                val collectionId = details?.belongsToCollection?.id
                val collectionName = details?.belongsToCollection?.name

                val logoPath = images?.logos?.let {
                    selectBestLocalizedImagePath(it, normalizedLanguage)
                }

                val logo = buildImageUrl(logoPath, size = "w500")

                val castMembers = credits?.cast
                    .orEmpty()
                    .mapNotNull { member ->
                        val name = resolveDisplayLabel(
                            localized = member.name,
                            original = member.originalName,
                            fallbackEnglish = member.id?.let { englishFallbackNames[it] },
                            preferredLanguage = normalizedLanguage
                        )?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = member.character?.takeIf { it.isNotBlank() },
                            photo = buildImageUrl(member.profilePath, size = "w500"),
                            tmdbId = member.id
                        )
                    }

                val creatorMembers = if (tmdbType == "tv") {
                    details?.createdBy
                        .orEmpty()
                        .mapNotNull { creatorItem ->
                            val tmdbPersonId = creatorItem.id ?: return@mapNotNull null
                            val name = resolveDisplayLabel(
                                localized = creatorItem.name,
                                original = creatorItem.originalName,
                                fallbackEnglish = englishFallbackNames[tmdbPersonId],
                                preferredLanguage = normalizedLanguage
                            )?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            MetaCastMember(
                                name = name,
                                character = "Creator",
                                photo = buildImageUrl(creatorItem.profilePath, size = "w500"),
                                tmdbId = tmdbPersonId
                            )
                        }
                        .distinctBy { it.tmdbId ?: it.name.lowercase(Locale.US) }
                } else {
                    emptyList()
                }

                val creator = if (tmdbType == "tv") {
                    details?.createdBy
                        .orEmpty()
                        .mapNotNull { creatorItem ->
                            resolveDisplayLabel(
                                localized = creatorItem.name,
                                original = creatorItem.originalName,
                                fallbackEnglish = creatorItem.id?.let { englishFallbackNames[it] },
                                preferredLanguage = normalizedLanguage
                            )?.takeIf { it.isNotBlank() }
                        }
                } else {
                    emptyList()
                }

                val directorCrew = credits?.crew
                    .orEmpty()
                    .filter { it.job.equals("Director", ignoreCase = true) }

                val directorMembers = directorCrew
                    .mapNotNull { member ->
                        val tmdbPersonId = member.id ?: return@mapNotNull null
                        val name = resolveDisplayLabel(
                            localized = member.name,
                            original = member.originalName,
                            fallbackEnglish = englishFallbackNames[tmdbPersonId],
                            preferredLanguage = normalizedLanguage
                        )?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = "Director",
                            photo = buildImageUrl(member.profilePath, size = "w500"),
                            tmdbId = tmdbPersonId
                        )
                    }
                    .distinctBy { it.tmdbId ?: it.name.lowercase(Locale.US) }

                val director = directorCrew
                    .mapNotNull { member ->
                        resolveDisplayLabel(
                            localized = member.name,
                            original = member.originalName,
                            fallbackEnglish = member.id?.let { englishFallbackNames[it] },
                            preferredLanguage = normalizedLanguage
                        )?.takeIf { it.isNotBlank() }
                    }

                val writerCrew = credits?.crew
                    .orEmpty()
                    .filter { crew ->
                        val job = crew.job?.lowercase(Locale.US) ?: ""
                        job.contains("writer") || job.contains("screenplay")
                    }

                val writerMembers = writerCrew
                    .mapNotNull { member ->
                        val tmdbPersonId = member.id ?: return@mapNotNull null
                        val name = resolveDisplayLabel(
                            localized = member.name,
                            original = member.originalName,
                            fallbackEnglish = englishFallbackNames[tmdbPersonId],
                            preferredLanguage = normalizedLanguage
                        )?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = "Writer",
                            photo = buildImageUrl(member.profilePath, size = "w500"),
                            tmdbId = tmdbPersonId
                        )
                    }
                    .distinctBy { it.tmdbId ?: it.name.lowercase(Locale.US) }

                val writer = writerCrew
                    .mapNotNull { member ->
                        resolveDisplayLabel(
                            localized = member.name,
                            original = member.originalName,
                            fallbackEnglish = member.id?.let { englishFallbackNames[it] },
                            preferredLanguage = normalizedLanguage
                        )?.takeIf { it.isNotBlank() }
                    }

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
                    productionCompanies.isEmpty() && networks.isEmpty() && ageRating == null && status == null &&
                    trailers.isEmpty()
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
                    status = status,
                    countries = countries,
                    language = language,
                    collectionId = collectionId,
                    collectionName = collectionName,
                    originalTitle = originalTitle,
                    alternativeTitles = altTitles,
                    trailers = trailers
                )
                enrichmentCache[cacheKey] = enrichment
                requestDeferred.complete(enrichment)
                enrichment
            } catch (e: CancellationException) {
                requestDeferred.cancel(e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch TMDB enrichment: ${e.message}", e)
                requestDeferred.complete(null)
                null
            } finally {
                if (!requestDeferred.isCompleted) {
                    requestDeferred.complete(null)
                }
                enrichmentInFlight.remove(cacheKey, requestDeferred)
            }
        }

    private suspend fun fetchTmdbTrailers(
        tmdbId: Int,
        tmdbType: String,
        preferredLanguage: String
    ): List<MetaTrailer> {
        val localizedResults = when (tmdbType) {
            "tv" -> runCatching {
                tmdbApi.getTvVideos(tmdbId, TMDB_API_KEY, preferredLanguage).body()?.results.orEmpty()
            }.getOrElse {
                Log.w(TAG, "Failed to fetch localized TV trailers for $tmdbId: ${it.message}")
                emptyList()
            }

            else -> runCatching {
                tmdbApi.getMovieVideos(tmdbId, TMDB_API_KEY, preferredLanguage).body()?.results.orEmpty()
            }.getOrElse {
                Log.w(TAG, "Failed to fetch localized movie trailers for $tmdbId: ${it.message}")
                emptyList()
            }
        }

        val mergedResults = if (
            localizedResults.isNotEmpty() ||
            preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)
        ) {
            localizedResults
        } else {
            val fallbackResults = when (tmdbType) {
                "tv" -> runCatching {
                    tmdbApi.getTvVideos(tmdbId, TMDB_API_KEY, TMDB_TRAILER_FALLBACK_LANGUAGE)
                        .body()?.results.orEmpty()
                }.getOrElse {
                    Log.w(TAG, "Failed to fetch fallback TV trailers for $tmdbId: ${it.message}")
                    emptyList()
                }

                else -> runCatching {
                    tmdbApi.getMovieVideos(tmdbId, TMDB_API_KEY, TMDB_TRAILER_FALLBACK_LANGUAGE)
                        .body()?.results.orEmpty()
                }.getOrElse {
                    Log.w(TAG, "Failed to fetch fallback movie trailers for $tmdbId: ${it.message}")
                    emptyList()
                }
            }
            localizedResults + fallbackResults
        }

        return rankTmdbTrailers(mergedResults)
            .mapNotNull { video ->
                val ytId = video.key?.trim()?.takeIf { YOUTUBE_VIDEO_ID_REGEX.matches(it) } ?: return@mapNotNull null
                MetaTrailer(
                    source = "TMDB",
                    type = video.type?.takeIf(String::isNotBlank),
                    name = video.name?.takeIf(String::isNotBlank),
                    ytId = ytId,
                    lang = video.iso6391?.takeIf(String::isNotBlank)
                )
            }
            .distinctBy { it.ytId }
    }

    private fun rankTmdbTrailers(results: List<TmdbVideoResult>): List<TmdbVideoResult> {
        fun typePriority(type: String?): Int = when (type?.trim()?.lowercase(Locale.US)) {
            "trailer" -> 0
            "teaser" -> 1
            "clip" -> 2
            "featurette" -> 3
            else -> 4
        }

        return results
            .asSequence()
            .filter { video ->
                video.site.equals("YouTube", ignoreCase = true) &&
                    !video.key.isNullOrBlank()
            }
            .sortedWith(
                compareBy<TmdbVideoResult> { typePriority(it.type) }
                    .thenByDescending { it.official == true }
                    .thenByDescending { it.publishedAt.orEmpty() }
            )
            .toList()
    }

    suspend fun fetchEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String = "en"
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> = withContext(ioDispatcher) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${seasonNumbers.sorted().joinToString(",")}:$normalizedLanguage"
        episodeCache[cacheKey]?.let { return@withContext it }
        episodeInFlight[cacheKey]?.let { return@withContext it.await() }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyMap()
        val requestDeferred = CompletableDeferred<Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>()
        episodeInFlight.putIfAbsent(cacheKey, requestDeferred)?.let { existing ->
            return@withContext existing.await()
        }
        try {
            val semaphore = Semaphore(TMDB_SEASON_REQUEST_CONCURRENCY)
            val seasonResults = coroutineScope {
                seasonNumbers.distinct().map { season ->
                    async {
                        semaphore.withPermit {
                            try {
                                val response = tmdbApi.getTvSeasonDetails(
                                    numericId,
                                    season,
                                    TMDB_API_KEY,
                                    normalizedLanguage
                                )
                                response.body()?.episodes.orEmpty().mapNotNull { episode ->
                                    val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                                    (season to episodeNumber) to episode.toEnrichment()
                                }.toMap()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to fetch TMDB season $season: ${e.message}")
                                emptyMap()
                            }
                        }
                    }
                }.awaitAll()
            }

            val finalResult = buildMap {
                seasonResults.forEach(::putAll)
            }
            if (finalResult.isNotEmpty()) {
                episodeCache[cacheKey] = finalResult
            }
            requestDeferred.complete(finalResult)
            finalResult
        } catch (e: CancellationException) {
            requestDeferred.cancel(e)
            throw e
        } finally {
            if (!requestDeferred.isCompleted) {
                requestDeferred.complete(emptyMap())
            }
            episodeInFlight.remove(cacheKey, requestDeferred)
        }
    }

    suspend fun fetchMoreLikeThis(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en",
        maxItems: Int = 12
    ): List<MetaPreview> = withContext(ioDispatcher) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val itemLimit = maxItems.coerceAtLeast(1)
        val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage:more_like:$itemLimit"
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
            }).take(itemLimit)

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

                        val backdrop = buildImageUrl(localizedBackdropPath ?: rec.backdropPath, size = "w1280")
                        val fallbackPoster = buildImageUrl(rec.posterPath, size = "w780")

                        val releaseInfo = if (recTmdbType == "tv") {
                            val startYear = rec.firstAirDate.yearPart()
                            if (startYear != null) {
                                val tvDetails = runCatching {
                                    tmdbApi.getTvDetails(rec.id, TMDB_API_KEY, normalizedLanguage).body()
                                }.getOrNull()
                                val status = tvDetails?.status
                                val endYear = tvDetails?.lastAirDate.yearPart()
                                buildShowYearRange(startYear, endYear, status)
                            } else null
                        } else {
                            rec.releaseDate.yearPart()
                        }

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
                            genres = emptyList(),
                            landscapePoster = backdrop,
                            rawPosterUrl = fallbackPoster
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

    private val collectionCache = ConcurrentHashMap<String, TmdbMovieCollection>()

    suspend fun fetchMovieCollection(
        collectionId: Int,
        language: String = "en"
    ): TmdbMovieCollection = withContext(ioDispatcher) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$collectionId:$normalizedLanguage:collection"
        collectionCache[cacheKey]?.let { return@withContext it }

        try {
            val collectionResponse = tmdbApi.getCollectionDetails(collectionId, TMDB_API_KEY, normalizedLanguage).body()
            val rawParts = collectionResponse?.parts.orEmpty()
            val isCjkLanguage = normalizedLanguage.startsWith("ja") ||
                normalizedLanguage.startsWith("ko") ||
                normalizedLanguage.startsWith("zh")
            val englishCollection = if (
                normalizedLanguage != "en" &&
                !isCjkLanguage &&
                (
                    containsCjkOrHangul(collectionResponse?.name ?: "") ||
                    collectionPartsContainCjkTitles(rawParts)
                )
            ) {
                runCatching {
                    tmdbApi.getCollectionDetails(collectionId, TMDB_API_KEY, "en").body()
                }.getOrNull()
            } else {
                null
            }
            val englishTitlesById = englishCollectionTitlesById(englishCollection?.parts.orEmpty())
            val resolvedCollectionName = resolveDisplayLabel(
                localized = collectionResponse?.name,
                original = null,
                fallbackEnglish = englishCollection?.name,
                preferredLanguage = normalizedLanguage
            )

            // Show in release order
            val sortedParts = rawParts.sortedBy { it.releaseDate ?: "9999" }

            val includeImageLanguage = buildString {
                append(normalizedLanguage.substringBefore("-"))
                append(",")
                append(normalizedLanguage)
                append(",en,null")
            }

            val items = coroutineScope {
                sortedParts.map { part ->
                    async {
                        val title = resolveDisplayLabel(
                            localized = part.title,
                            original = part.originalTitle,
                            fallbackEnglish = englishTitlesById[part.id],
                            preferredLanguage = normalizedLanguage
                        ) ?: return@async null

                        val localizedBackdropPath = runCatching {
                            tmdbApi.getMovieImages(part.id, TMDB_API_KEY, includeImageLanguage).body()
                        }.getOrNull()?.let { images ->
                            selectBestLocalizedImagePath(
                                images = images.backdrops.orEmpty(),
                                normalizedLanguage = normalizedLanguage
                            )
                        }

                        val backdrop = buildImageUrl(localizedBackdropPath ?: part.backdropPath, size = "w1280")
                        val fallbackPoster = buildImageUrl(part.posterPath, size = "w780")
                        val releaseInfo = part.releaseDate?.take(4)

                        MetaPreview(
                            id = "tmdb:${part.id}",
                            type = ContentType.MOVIE,
                            name = title,
                            poster = backdrop ?: fallbackPoster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = backdrop,
                            logo = null,
                            description = part.overview?.takeIf { it.isNotBlank() },
                            releaseInfo = releaseInfo,
                            imdbRating = part.voteAverage?.toFloat(),
                            genres = emptyList(),
                            landscapePoster = backdrop,
                            rawPosterUrl = fallbackPoster
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            val collection = TmdbMovieCollection(
                name = resolvedCollectionName,
                items = items
            )
            collectionCache[cacheKey] = collection
            collection
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch collection for $collectionId: ${e.message}")
            TmdbMovieCollection(name = null, items = emptyList())
        }
    }

    suspend fun fetchEntityBrowse(
        entityKind: TmdbEntityKind,
        entityId: Int,
        sourceType: String,
        fallbackName: String? = null,
        language: String = "en"
    ): TmdbEntityBrowseData? = withContext(ioDispatcher) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val normalizedSourceType = normalizeEntitySourceType(sourceType)
        val cacheKey = "${entityKind.routeValue}:$entityId:$normalizedSourceType:$normalizedLanguage"
        entityBrowseCache[cacheKey]?.let { return@withContext it }

        val header = fetchEntityHeader(
            entityKind = entityKind,
            entityId = entityId,
            fallbackName = fallbackName,
            language = normalizedLanguage
        )

        val rails = buildEntityMediaOrder(entityKind, normalizedSourceType)
            .flatMap { mediaType ->
                TmdbEntityRailType.values().mapNotNull { railType ->
                    val pageResult = fetchEntityRailPage(
                        entityKind = entityKind,
                        entityId = entityId,
                        mediaType = mediaType,
                        railType = railType,
                        language = normalizedLanguage,
                        page = 1
                    )
                    val items = pageResult.items
                    if (items.isEmpty()) {
                        null
                    } else {
                        TmdbEntityRail(
                            mediaType = mediaType,
                            railType = railType,
                            items = items,
                            currentPage = 1,
                            hasMore = pageResult.hasMore,
                            isLoading = false
                        )
                    }
                }
            }

        if (header == null && rails.isEmpty()) return@withContext null

        val data = TmdbEntityBrowseData(
            header = header ?: TmdbEntityHeader(
                id = entityId,
                kind = entityKind,
                name = fallbackName?.takeIf { it.isNotBlank() } ?: "Unknown",
                logo = null,
                originCountry = null,
                secondaryLabel = null,
                description = null
            ),
            rails = rails
        )
        entityBrowseCache[cacheKey] = data
        data
    }

    private suspend fun fetchEntityHeader(
        entityKind: TmdbEntityKind,
        entityId: Int,
        fallbackName: String?,
        language: String
    ): TmdbEntityHeader? {
        val cacheKey = "${entityKind.routeValue}:$entityId:$language:header"
        entityHeaderCache[cacheKey]?.let { return it }

        val header = try {
            when (entityKind) {
                TmdbEntityKind.COMPANY -> {
                    val body = tmdbApi.getCompanyDetails(entityId, TMDB_API_KEY).body()
                    if (body == null) {
                        null
                    } else {
                        TmdbEntityHeader(
                            id = body.id,
                            kind = entityKind,
                            name = body.name?.takeIf { it.isNotBlank() }
                                ?: fallbackName?.takeIf { it.isNotBlank() }
                                ?: "Unknown",
                            logo = buildImageUrl(body.logoPath, size = "w500"),
                            originCountry = body.originCountry?.takeIf { it.isNotBlank() },
                            secondaryLabel = body.headquarters?.takeIf { it.isNotBlank() },
                            description = body.description?.takeIf { it.isNotBlank() }
                        )
                    }
                }

                TmdbEntityKind.NETWORK -> {
                    val body = tmdbApi.getNetworkDetails(entityId, TMDB_API_KEY).body()
                    if (body == null) {
                        null
                    } else {
                        TmdbEntityHeader(
                            id = body.id,
                            kind = entityKind,
                            name = body.name?.takeIf { it.isNotBlank() }
                                ?: fallbackName?.takeIf { it.isNotBlank() }
                                ?: "Unknown",
                            logo = buildImageUrl(body.logoPath, size = "w500"),
                            originCountry = body.originCountry?.takeIf { it.isNotBlank() },
                            secondaryLabel = body.headquarters?.takeIf { it.isNotBlank() },
                            description = null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch ${entityKind.routeValue} header for $entityId: ${e.message}")
            null
        } ?: fallbackName?.takeIf { it.isNotBlank() }?.let {
            TmdbEntityHeader(
                id = entityId,
                kind = entityKind,
                name = it,
                logo = null,
                originCountry = null,
                secondaryLabel = null,
                description = null
            )
        }

        if (header != null) {
            entityHeaderCache[cacheKey] = header
        }
        return header
    }

    suspend fun fetchEntityRailPage(
        entityKind: TmdbEntityKind,
        entityId: Int,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        page: Int
    ): TmdbEntityRailPageResult {
        if (entityKind == TmdbEntityKind.NETWORK && mediaType == TmdbEntityMediaType.MOVIE) {
            return TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }

        val cacheKey = "${entityKind.routeValue}:$entityId:${mediaType.value}:${railType.value}:$language:page:$page"
        entityRailCache[cacheKey]?.let { cached ->
            return TmdbEntityRailPageResult(
                items = cached,
                hasMore = cached.isNotEmpty()
            )
        }

        val today = LocalDate.now().toString()
        val voteCountFloor = if (railType == TmdbEntityRailType.TOP_RATED) TOP_RATED_VOTE_COUNT_FLOOR else null
        val result = try {
            suspend fun loadDiscover(requestLanguage: String) = when (mediaType) {
                TmdbEntityMediaType.MOVIE -> {
                    tmdbApi.discoverMovies(
                        apiKey = TMDB_API_KEY,
                        language = requestLanguage,
                        page = page,
                        sortBy = movieSortBy(railType),
                        withCompanies = entityId.toString(),
                        releaseDateLte = if (railType == TmdbEntityRailType.RECENT) today else null,
                        voteCountGte = voteCountFloor
                    ).body()
                }

                TmdbEntityMediaType.TV -> {
                    tmdbApi.discoverTv(
                        apiKey = TMDB_API_KEY,
                        language = requestLanguage,
                        page = page,
                        sortBy = tvSortBy(railType),
                        withCompanies = if (entityKind == TmdbEntityKind.COMPANY) entityId.toString() else null,
                        withNetworks = if (entityKind == TmdbEntityKind.NETWORK) entityId.toString() else null,
                        firstAirDateLte = if (railType == TmdbEntityRailType.RECENT || entityKind == TmdbEntityKind.NETWORK) today else null,
                        voteCountGte = voteCountFloor,
                        withStatus = if (entityKind == TmdbEntityKind.NETWORK) "0|3|4" else null
                    ).body()
                }
            }

            val response = loadDiscover(language)
            val results = response?.results.orEmpty()
            val totalPages = response?.totalPages ?: page

            val isCjkLanguage = language.startsWith("ja") ||
                language.startsWith("ko") ||
                language.startsWith("zh")
            val englishTitlesById = if (
                language != "en" &&
                !isCjkLanguage &&
                discoverResultsContainCjkTitles(results)
            ) {
                englishDiscoverTitlesById(loadDiscover("en")?.results.orEmpty())
            } else {
                emptyMap()
            }

            val mappedItems = results
                .filter { it.id > 0 }
                .mapNotNull { discoverItem ->
                    mapEntityDiscoverResult(
                        result = discoverItem,
                        mediaType = mediaType,
                        preferredLanguage = language,
                        englishTitlesById = englishTitlesById
                    )
                }
                .take(ENTITY_RAIL_MAX_ITEMS)

            TmdbEntityRailPageResult(
                items = mappedItems,
                hasMore = page < totalPages && mappedItems.isNotEmpty()
            )
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to fetch ${entityKind.routeValue} rail ${railType.value}/${mediaType.value} for $entityId: ${e.message}"
            )
            TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }

        if (result.items.isNotEmpty()) {
            entityRailCache[cacheKey] = result.items
        }
        return result
    }

    private fun mapEntityDiscoverResult(
        result: TmdbDiscoverResult,
        mediaType: TmdbEntityMediaType,
        preferredLanguage: String,
        englishTitlesById: Map<Int, String>
    ): MetaPreview? {
        val title = resolveDisplayLabel(
            localized = result.title ?: result.name,
            original = result.originalTitle ?: result.originalName,
            fallbackEnglish = englishTitlesById[result.id],
            preferredLanguage = preferredLanguage
        ) ?: return null

        val poster = buildImageUrl(result.posterPath, size = "w500")
            ?: buildImageUrl(result.backdropPath, size = "w780")
            ?: return null
        val background = buildImageUrl(result.backdropPath, size = "w1280")
        val releaseInfo = when (mediaType) {
            TmdbEntityMediaType.MOVIE -> result.releaseDate?.take(4)
            TmdbEntityMediaType.TV -> result.firstAirDate?.take(4)
        }

        return MetaPreview(
            id = "tmdb:${result.id}",
            type = if (mediaType == TmdbEntityMediaType.TV) ContentType.SERIES else ContentType.MOVIE,
            name = title,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = background,
            logo = null,
            description = result.overview?.takeIf { it.isNotBlank() },
            releaseInfo = releaseInfo,
            imdbRating = result.voteAverage?.toFloat(),
            genres = emptyList()
        )
    }

    internal fun buildEntityMediaOrder(
        entityKind: TmdbEntityKind,
        sourceType: String
    ): List<TmdbEntityMediaType> {
        if (entityKind == TmdbEntityKind.NETWORK) {
            return listOf(TmdbEntityMediaType.TV)
        }

        return when (normalizeEntitySourceType(sourceType)) {
            "movie" -> listOf(TmdbEntityMediaType.MOVIE, TmdbEntityMediaType.TV)
            else -> listOf(TmdbEntityMediaType.TV, TmdbEntityMediaType.MOVIE)
        }
    }

    private fun normalizeEntitySourceType(sourceType: String): String {
        return when (sourceType.trim().lowercase(Locale.US)) {
            "movie" -> "movie"
            "tv", "series", "show" -> "tv"
            else -> "tv"
        }
    }

    private fun movieSortBy(railType: TmdbEntityRailType): String = when (railType) {
        TmdbEntityRailType.POPULAR -> "popularity.desc"
        TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
        TmdbEntityRailType.RECENT -> "primary_release_date.desc"
    }

    private fun tvSortBy(railType: TmdbEntityRailType): String = when (railType) {
        TmdbEntityRailType.POPULAR -> "popularity.desc"
        TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
        TmdbEntityRailType.RECENT -> "first_air_date.desc"
    }

    private fun buildShowYearRange(startYear: String, endYear: String?, status: String?): String {
        val isEnded = status != null && status != "Returning Series" && status != "In Production"
        return when {
            isEnded && endYear != null && endYear != startYear -> "$startYear-$endYear"
            isEnded -> startYear
            else -> "$startYear-"
        }
    }

    private fun String?.yearPart(): String? {
        val value = this?.trim()?.takeIf { it.length >= 4 }?.take(4) ?: return null
        return value.takeIf { it.all(Char::isDigit) }
    }

    private fun buildImageUrl(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }

    private fun normalizeTmdbLanguage(language: String?): String {
        val raw = language
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', '-')
            ?: return "en"
        // Normalize region code to uppercase (e.g. pt-br -> pt-BR)
        val normalized = raw.split("-").let { parts ->
            if (parts.size == 2) "${parts[0].lowercase(Locale.US)}-${parts[1].uppercase(Locale.US)}"
            else raw.lowercase(Locale.US)
        }
        // Map codes unsupported by TMDB to their closest equivalent
        return when (normalized) {
            "es-419" -> "es-MX"
            else -> normalized
        }
    }

    private fun selectBestLocalizedImagePath(
        images: List<TmdbImage>,
        normalizedLanguage: String
    ): String? {
        if (images.isEmpty()) return null
        val languageCode = normalizedLanguage.substringBefore("-")
        val explicitRegion = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
        val regionCode = explicitRegion
            ?: LANGUAGE_DEFAULT_REGION[languageCode]
            ?: DEFAULT_LANGUAGE_REGIONS[languageCode]
        // Once we have any region (explicit like fr-FR, or inferred for a bare "fr" via
        // the default-region map), prefer the exact region match first, then same-language
        // with no region, then same-language from any other region (cross-region fallback
        // e.g. pt-PT for pt-BR), and only then fall back to English.
        return images
            .sortedWith(
                compareByDescending<TmdbImage> { it.iso6391 == languageCode && it.iso31661 == regionCode }
                    .thenByDescending { it.iso6391 == languageCode && it.iso31661 == null }
                    .thenByDescending { it.iso6391 == languageCode }
                    .thenByDescending { it.iso6391 == "en" }
                    .thenByDescending { it.iso6391 == null }
            )
            .firstOrNull()
            ?.filePath
    }

    companion object {
        private val DEFAULT_LANGUAGE_REGIONS = mapOf(
            "pt" to "PT",
            "es" to "ES"
        )
        private const val ENTITY_RAIL_MAX_ITEMS = 20
        private const val TOP_RATED_VOTE_COUNT_FLOOR = 200
    }

    private fun mapAggregateCreditsToStandard(aggregate: TmdbAggregateCreditsResponse): TmdbCreditsResponse {
        return TmdbCreditsResponse(
            cast = aggregate.cast?.map { member ->
                TmdbCastMember(
                    id = member.id,
                    name = member.name,
                    originalName = member.originalName,
                    character = member.roles?.firstOrNull()?.character,
                    profilePath = member.profilePath
                )
            },
            crew = aggregate.crew?.flatMap { member ->
                member.jobs?.map { job ->
                    TmdbCrewMember(
                        id = member.id,
                        name = member.name,
                        originalName = member.originalName,
                        job = job.job,
                        department = member.department,
                        profilePath = member.profilePath
                    )
                } ?: emptyList()
            }
        )
    }

    suspend fun fetchPersonDetail(
        personId: Int,
        preferCrewCredits: Boolean? = null,
        language: String = "en"
    ): PersonDetail? =
        withContext(ioDispatcher) {
            val normalizedLanguage = normalizeTmdbLanguage(language)
            val cacheKey = "$personId:${preferCrewCredits?.toString() ?: "auto"}:$normalizedLanguage"
            personCache[cacheKey]?.let { return@withContext it }

            try {
                val (person, credits) = coroutineScope {
                    val personDeferred = async {
                        tmdbApi.getPersonDetails(personId, TMDB_API_KEY, normalizedLanguage).body()
                    }
                    val creditsDeferred = async {
                        tmdbApi.getPersonCombinedCredits(personId, TMDB_API_KEY, normalizedLanguage).body()
                    }
                    Pair(personDeferred.await(), creditsDeferred.await())
                }

                if (person == null) return@withContext null

                val isCjkLanguage = normalizedLanguage.startsWith("ja") ||
                    normalizedLanguage.startsWith("ko") ||
                    normalizedLanguage.startsWith("zh")

                val shouldFetchEnglishPerson = normalizedLanguage != "en" &&
                    (person.biography.isNullOrBlank() || (!isCjkLanguage && person.name != null && containsCjkOrHangul(person.name) && (person.originalName == null || containsCjkOrHangul(person.originalName))))
                val shouldFetchEnglishCredits = normalizedLanguage != "en" &&
                    !isCjkLanguage &&
                    personCreditsContainCjkTitles(credits)

                val (englishPerson, englishCredits) = if (shouldFetchEnglishPerson || shouldFetchEnglishCredits) {
                    coroutineScope {
                        val englishPersonDeferred = async {
                            if (shouldFetchEnglishPerson) {
                                runCatching {
                                    tmdbApi.getPersonDetails(personId, TMDB_API_KEY, "en").body()
                                }.getOrNull()
                            } else {
                                null
                            }
                        }
                        val englishCreditsDeferred = async {
                            if (shouldFetchEnglishCredits) {
                                runCatching {
                                    tmdbApi.getPersonCombinedCredits(personId, TMDB_API_KEY, "en").body()
                                }.getOrNull()
                            } else {
                                null
                            }
                        }
                        Pair(englishPersonDeferred.await(), englishCreditsDeferred.await())
                    }
                } else {
                    Pair(null, null)
                }

                val englishTitlesById = englishCreditTitlesById(englishCredits)

                // If biography is empty and language is not English, fetch English fallback
                val biography = if (person.biography.isNullOrBlank() && normalizedLanguage != "en") {
                    englishPerson?.biography
                } else {
                    person.biography
                }?.takeIf { it.isNotBlank() }

                val resolvedPersonName = resolveDisplayLabel(
                    localized = person.name,
                    original = person.originalName,
                    fallbackEnglish = englishPerson?.name,
                    preferredLanguage = normalizedLanguage
                ) ?: "Unknown"

                val preferCrewFilmography = preferCrewCredits ?: shouldPreferCrewCredits(person.knownForDepartment)

                val castMovieCredits = mapMovieCreditsFromCast(
                    credits?.cast.orEmpty(),
                    normalizedLanguage,
                    englishTitlesById
                )
                val crewMovieCredits = mapMovieCreditsFromCrew(
                    credits?.crew.orEmpty(),
                    normalizedLanguage,
                    englishTitlesById
                )
                val movieCredits = when {
                    preferCrewFilmography && crewMovieCredits.isNotEmpty() -> crewMovieCredits
                    castMovieCredits.isNotEmpty() -> castMovieCredits
                    else -> crewMovieCredits
                }

                val castTvCredits = mapTvCreditsFromCast(
                    credits?.cast.orEmpty(),
                    normalizedLanguage,
                    englishTitlesById
                )
                val crewTvCredits = mapTvCreditsFromCrew(
                    credits?.crew.orEmpty(),
                    normalizedLanguage,
                    englishTitlesById
                )
                val tvCredits = when {
                    preferCrewFilmography && crewTvCredits.isNotEmpty() -> crewTvCredits
                    castTvCredits.isNotEmpty() -> castTvCredits
                    else -> crewTvCredits
                }

                val detail = PersonDetail(
                    tmdbId = person.id,
                    name = resolvedPersonName,
                    biography = biography,
                    birthday = person.birthday?.takeIf { it.isNotBlank() },
                    deathday = person.deathday?.takeIf { it.isNotBlank() },
                    placeOfBirth = person.placeOfBirth?.takeIf { it.isNotBlank() },
                    profilePhoto = buildImageUrl(person.profilePath, "w500"),
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

    private fun mapMovieCreditsFromCast(
        cast: List<TmdbPersonCreditCast>,
        preferredLanguage: String,
        englishTitlesById: Map<Int, String>
    ): List<MetaPreview> {
        val seenMovieIds = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenMovieIds.add(credit.id)) return@mapNotNull null
                val title = resolveDisplayLabel(
                    localized = credit.title ?: credit.name,
                    original = credit.originalTitle ?: credit.originalName,
                    fallbackEnglish = englishTitlesById[credit.id],
                    preferredLanguage = preferredLanguage
                ) ?: return@mapNotNull null
                val year = credit.releaseDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapMovieCreditsFromCrew(
        crew: List<TmdbPersonCreditCrew>,
        preferredLanguage: String,
        englishTitlesById: Map<Int, String>
    ): List<MetaPreview> {
        val seenMovieIds = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenMovieIds.add(credit.id)) return@mapNotNull null
                val title = resolveDisplayLabel(
                    localized = credit.title ?: credit.name,
                    original = credit.originalTitle ?: credit.originalName,
                    fallbackEnglish = englishTitlesById[credit.id],
                    preferredLanguage = preferredLanguage
                ) ?: return@mapNotNull null
                val year = credit.releaseDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCast(
        cast: List<TmdbPersonCreditCast>,
        preferredLanguage: String,
        englishTitlesById: Map<Int, String>
    ): List<MetaPreview> {
        val seenTvIds = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenTvIds.add(credit.id)) return@mapNotNull null
                val title = resolveDisplayLabel(
                    localized = credit.name ?: credit.title,
                    original = credit.originalName ?: credit.originalTitle,
                    fallbackEnglish = englishTitlesById[credit.id],
                    preferredLanguage = preferredLanguage
                ) ?: return@mapNotNull null
                val year = credit.firstAirDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCrew(
        crew: List<TmdbPersonCreditCrew>,
        preferredLanguage: String,
        englishTitlesById: Map<Int, String>
    ): List<MetaPreview> {
        val seenTvIds = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenTvIds.add(credit.id)) return@mapNotNull null
                val title = resolveDisplayLabel(
                    localized = credit.name ?: credit.title,
                    original = credit.originalName ?: credit.originalTitle,
                    fallbackEnglish = englishTitlesById[credit.id],
                    preferredLanguage = preferredLanguage
                ) ?: return@mapNotNull null
                val year = credit.firstAirDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
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

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)

private data class Sextuple<A, B, C, D, E, F>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F
)

// Fallback regions for language codes that don't carry a region tag (e.g. "fr"
// instead of "fr-FR"). Without this, non-hyphenated locales fall straight through
// to the US/GB defaults in preferredRegions and users see American ratings.
private val LANGUAGE_DEFAULT_REGION: Map<String, String> = mapOf(
    "ar" to "SA", "bg" to "BG", "bs" to "BA", "cs" to "CZ", "da" to "DK",
    "de" to "DE", "el" to "GR", "es" to "ES", "et" to "EE", "fi" to "FI",
    "fr" to "FR", "he" to "IL", "hi" to "IN", "hr" to "HR", "hu" to "HU",
    "id" to "ID", "it" to "IT", "ja" to "JP", "ko" to "KR", "lt" to "LT",
    "lv" to "LV", "nl" to "NL", "no" to "NO", "pl" to "PL", "pt" to "PT",
    "ro" to "RO", "ru" to "RU", "sk" to "SK", "sl" to "SI", "sr" to "RS",
    "sv" to "SE", "th" to "TH", "tr" to "TR", "uk" to "UA", "vi" to "VN",
    "zh" to "CN"
)

private fun preferredRegions(normalizedLanguage: String): List<String> {
    val languageCode = normalizedLanguage.substringBefore("-").lowercase(Locale.US)
    val fromLanguage = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
        ?: LANGUAGE_DEFAULT_REGION[languageCode]
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

data class TmdbMovieCollection(
    val name: String?,
    val items: List<MetaPreview>
)

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
    val status: String?,
    val countries: List<String>?,
    val language: String?,
    val collectionId: Int?,
    val collectionName: String?,
    val originalTitle: String? = null,
    val alternativeTitles: List<String> = emptyList(),
    val trailers: List<MetaTrailer> = emptyList()
)

data class TmdbEpisodeEnrichment(
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val airDate: String?,
    val runtimeMinutes: Int?
)

enum class TmdbEntityKind(val routeValue: String) {
    COMPANY("company"),
    NETWORK("network");

    companion object {
        fun fromRouteValue(value: String): TmdbEntityKind = when (value.trim().lowercase(Locale.US)) {
            "network" -> NETWORK
            else -> COMPANY
        }
    }
}

enum class TmdbEntityMediaType(val value: String) {
    MOVIE("movie"),
    TV("tv")
}

enum class TmdbEntityRailType(val value: String) {
    POPULAR("popular"),
    TOP_RATED("top_rated"),
    RECENT("recent")
}

data class TmdbEntityHeader(
    val id: Int,
    val kind: TmdbEntityKind,
    val name: String,
    val logo: String?,
    val originCountry: String?,
    val secondaryLabel: String?,
    val description: String?
)

data class TmdbEntityRail(
    val mediaType: TmdbEntityMediaType,
    val railType: TmdbEntityRailType,
    val items: List<MetaPreview>,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false
)

data class TmdbEntityBrowseData(
    val header: TmdbEntityHeader,
    val rails: List<TmdbEntityRail>
)

data class TmdbEntityRailPageResult(
    val items: List<MetaPreview>,
    val hasMore: Boolean
)

private fun TmdbEpisode.toEnrichment(): TmdbEpisodeEnrichment {
    val title = name?.takeIf { it.isNotBlank() }
    val overview = overview?.takeIf { it.isNotBlank() }
    val thumbnail = stillPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
    val airDate = airDate?.takeIf { it.isNotBlank() }
    return TmdbEpisodeEnrichment(
        title = title,
        overview = overview,
        thumbnail = thumbnail,
        airDate = airDate,
        runtimeMinutes = runtime
    )
}

private fun discoverResultsContainCjkTitles(results: List<TmdbDiscoverResult>): Boolean {
    return results.any { result ->
        containsCjkOrHangul(result.title ?: result.name ?: return@any false)
    }
}

private fun englishDiscoverTitlesById(results: List<TmdbDiscoverResult>): Map<Int, String> {
    val titles = LinkedHashMap<Int, String>()
    results.forEach { result ->
        val text = result.title?.trim()?.takeIf { it.isNotBlank() }
            ?: result.name?.trim()?.takeIf { it.isNotBlank() }
            ?: return@forEach
        if (!containsCjkOrHangul(text)) {
            titles.putIfAbsent(result.id, text)
        }
    }
    return titles
}

private fun personCreditsContainCjkTitles(credits: TmdbPersonCreditsResponse?): Boolean {
    if (credits == null) return false
    return credits.cast.orEmpty().any { containsCjkOrHangul(it.title ?: it.name ?: return@any false) } ||
        credits.crew.orEmpty().any { containsCjkOrHangul(it.title ?: it.name ?: return@any false) }
}

private fun englishCreditTitlesById(credits: TmdbPersonCreditsResponse?): Map<Int, String> {
    if (credits == null) return emptyMap()
    val titles = LinkedHashMap<Int, String>()
    fun putTitle(id: Int, title: String?, name: String?) {
        val text = title?.trim()?.takeIf { it.isNotBlank() }
            ?: name?.trim()?.takeIf { it.isNotBlank() }
            ?: return
        if (!containsCjkOrHangul(text)) {
            titles.putIfAbsent(id, text)
        }
    }
    credits.cast.orEmpty().forEach { putTitle(it.id, it.title, it.name) }
    credits.crew.orEmpty().forEach { putTitle(it.id, it.title, it.name) }
    return titles
}

private fun collectionPartsContainCjkTitles(parts: List<TmdbCollectionPart>): Boolean {
    return parts.any { containsCjkOrHangul(it.title ?: return@any false) }
}

private fun englishCollectionTitlesById(parts: List<TmdbCollectionPart>): Map<Int, String> {
    val titles = LinkedHashMap<Int, String>()
    parts.forEach { part ->
        val text = part.title?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
        if (!containsCjkOrHangul(text)) {
            titles.putIfAbsent(part.id, text)
        }
    }
    return titles
}

internal fun containsCjkOrHangul(text: String): Boolean {
    return text.any { ch ->
        ch in '\u3040'..'\u30FF' ||  // Hiragana + Katakana
        ch in '\u4E00'..'\u9FFF' ||  // CJK Unified Ideographs
        ch in '\u3400'..'\u4DBF' ||  // CJK Extension A
        ch in '\uAC00'..'\uD7AF' ||  // Hangul Syllables
        ch in '\u1100'..'\u11FF' ||  // Hangul Jamo
        ch in '\u3130'..'\u318F'     // Hangul Compatibility Jamo
    }
}

internal fun resolveDisplayLabel(
    localized: String?,
    original: String?,
    fallbackEnglish: String? = null,
    preferredLanguage: String
): String? {
    val name = localized?.trim()?.takeIf { it.isNotBlank() }
    val originalLabel = original?.trim()?.takeIf { it.isNotBlank() }
    val fallback = fallbackEnglish?.trim()?.takeIf { it.isNotBlank() }

    if (name == null) return originalLabel ?: fallback
    val lang = preferredLanguage.lowercase(Locale.US)

    // User explicitly prefers CJK / Hangul
    if (lang.startsWith("ja") || lang.startsWith("ko") || lang.startsWith("zh")) {
        return name
    }

    // If already Latin or non-CJK script, keep localized label
    if (!containsCjkOrHangul(name)) {
        return name
    }

    // Label is CJK/Hangul: if original is Latin (Romaji/stage name), use it
    if (originalLabel != null && !containsCjkOrHangul(originalLabel)) {
        return originalLabel
    }

    // If English fallback exists and is Latin / non-CJK, prefer it
    if (fallback != null && !containsCjkOrHangul(fallback)) {
        return fallback
    }

    // Otherwise fallback to whatever non-null exists
    return fallback ?: originalLabel ?: name
}
