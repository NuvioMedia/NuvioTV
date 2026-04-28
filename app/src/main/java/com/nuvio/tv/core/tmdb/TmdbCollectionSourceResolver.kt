package com.nuvio.tv.core.tmdb

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbCollectionSearchResult
import com.nuvio.tv.data.remote.api.TmdbCompanySearchResult
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbListItem
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class TmdbSourceImportMetadata(
    val title: String? = null,
    val coverImageUrl: String? = null
)

@Singleton
class TmdbCollectionSourceResolver @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    fun resolve(source: TmdbCollectionSource, page: Int = 1): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val language = tmdbSettingsDataStore.settings.first().language
                when (source.sourceType) {
                    TmdbCollectionSourceType.LIST -> resolveList(source, language, page)
                    TmdbCollectionSourceType.COLLECTION -> resolveCollection(source, language)
                    TmdbCollectionSourceType.COMPANY,
                    TmdbCollectionSourceType.NETWORK,
                    TmdbCollectionSourceType.DISCOVER -> resolveDiscover(source, language, page)
                }
            }
        }
        result.fold(
            onSuccess = { emit(NetworkResult.Success(it)) },
            onFailure = { emit(NetworkResult.Error(it.message ?: "Could not load TMDB source")) }
        )
    }

    suspend fun searchCompanies(query: String): List<TmdbCompanySearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        tmdbApi.searchCompanies(BuildConfig.TMDB_API_KEY, query.trim()).body()?.results.orEmpty()
    }

    suspend fun searchCollections(query: String): List<TmdbCollectionSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val language = tmdbSettingsDataStore.settings.first().language
        tmdbApi.searchCollections(BuildConfig.TMDB_API_KEY, query.trim(), language).body()?.results.orEmpty()
    }

    suspend fun listImportMetadata(id: Int): TmdbSourceImportMetadata = withContext(Dispatchers.IO) {
        val language = tmdbSettingsDataStore.settings.first().language
        val body = tmdbApi.getListDetails(id, BuildConfig.TMDB_API_KEY, language, 1).body()
            ?: error("TMDB list not found")
        TmdbSourceImportMetadata(
            title = body.name?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun collectionImportMetadata(id: Int): TmdbSourceImportMetadata = withContext(Dispatchers.IO) {
        val language = tmdbSettingsDataStore.settings.first().language
        val body = tmdbApi.getCollectionDetails(id, BuildConfig.TMDB_API_KEY, language).body()
            ?: error("TMDB collection not found")
        TmdbSourceImportMetadata(
            title = body.name?.takeIf { it.isNotBlank() },
            coverImageUrl = imageUrl(body.posterPath, "w500") ?: imageUrl(body.backdropPath, "w1280")
        )
    }

    suspend fun companyImportMetadata(id: Int): TmdbSourceImportMetadata = withContext(Dispatchers.IO) {
        val body = tmdbApi.getCompanyDetails(id, BuildConfig.TMDB_API_KEY).body()
            ?: error("TMDB company not found")
        TmdbSourceImportMetadata(
            title = body.name?.takeIf { it.isNotBlank() },
            coverImageUrl = imageUrl(body.logoPath, "w500")
        )
    }

    suspend fun networkImportMetadata(id: Int): TmdbSourceImportMetadata = withContext(Dispatchers.IO) {
        val body = tmdbApi.getNetworkDetails(id, BuildConfig.TMDB_API_KEY).body()
            ?: error("TMDB network not found")
        TmdbSourceImportMetadata(
            title = body.name?.takeIf { it.isNotBlank() },
            coverImageUrl = imageUrl(body.logoPath, "w500")
        )
    }

    suspend fun searchKeywords(query: String): Map<Int, String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyMap()
        tmdbApi.searchKeywords(BuildConfig.TMDB_API_KEY, query.trim()).body()?.results.orEmpty()
            .mapNotNull { result ->
                val name = result.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                result.id to name
            }
            .toMap()
    }

    suspend fun genres(mediaType: TmdbCollectionMediaType): Map<Int, String> = withContext(Dispatchers.IO) {
        val language = tmdbSettingsDataStore.settings.first().language
        val response = when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> tmdbApi.getMovieGenres(BuildConfig.TMDB_API_KEY, language)
            TmdbCollectionMediaType.TV -> tmdbApi.getTvGenres(BuildConfig.TMDB_API_KEY, language)
        }
        response.body()?.genres.orEmpty().associate { it.id to it.name }
    }

    fun parseTmdbId(input: String): Int? {
        val trimmed = input.trim()
        trimmed.toIntOrNull()?.let { return it }
        return Regex("""(?:list|collection|company|network)/(\d+)""")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""[?&]id=(\d+)""")
                .find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private suspend fun resolveList(source: TmdbCollectionSource, language: String, page: Int): CatalogRow {
        val id = source.tmdbId ?: error("Missing TMDB list ID")
        val body = tmdbApi.getListDetails(id, BuildConfig.TMDB_API_KEY, language, page).body()
            ?: error("TMDB list not found")
        val items = body.items.orEmpty()
            .mapNotNull { it.toPreview() }
            .sortedFor(source.sortBy)
            .distinctBy { "${it.apiType}:${it.id}" }
        return row(
            source = source.copy(title = source.title.ifBlank { body.name ?: "TMDB List" }),
            page = body.page ?: page,
            hasMore = (body.page ?: page) < (body.totalPages ?: page),
            items = items
        )
    }

    private suspend fun resolveCollection(source: TmdbCollectionSource, language: String): CatalogRow {
        val id = source.tmdbId ?: error("Missing TMDB collection ID")
        val body = tmdbApi.getCollectionDetails(id, BuildConfig.TMDB_API_KEY, language).body()
            ?: error("TMDB collection not found")
        val items = body.parts.orEmpty()
            .mapNotNull {
                val title = it.title?.takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${it.id}",
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    name = title,
                    poster = imageUrl(it.posterPath, "w500") ?: imageUrl(it.backdropPath, "w780"),
                    posterShape = PosterShape.POSTER,
                    background = imageUrl(it.backdropPath, "w1280"),
                    logo = null,
                    description = it.overview?.takeIf { value -> value.isNotBlank() },
                    releaseInfo = it.releaseDate?.take(4),
                    imdbRating = it.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
            .sortedFor(source.sortBy)
        return row(
            source = source.copy(title = source.title.ifBlank { body.name ?: "TMDB Collection" }),
            page = 1,
            hasMore = false,
            items = items
        )
    }

    private suspend fun resolveDiscover(source: TmdbCollectionSource, language: String, page: Int): CatalogRow {
        val mediaType = if (source.sourceType == TmdbCollectionSourceType.NETWORK) {
            TmdbCollectionMediaType.TV
        } else {
            source.mediaType
        }
        val filters = source.filters
        val today = LocalDate.now().toString()
        val response = when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> tmdbApi.discoverMovies(
                apiKey = BuildConfig.TMDB_API_KEY,
                language = language,
                page = page,
                sortBy = movieSort(source.sortBy),
                withCompanies = when (source.sourceType) {
                    TmdbCollectionSourceType.COMPANY -> source.tmdbId?.toString()
                    else -> filters.withCompanies
                },
                releaseDateLte = filters.releaseDateLte,
                voteCountGte = filters.voteCountGte,
                withGenres = filters.withGenres,
                releaseDateGte = filters.releaseDateGte,
                voteAverageGte = filters.voteAverageGte,
                voteAverageLte = filters.voteAverageLte,
                withOriginalLanguage = filters.withOriginalLanguage,
                withOriginCountry = filters.withOriginCountry,
                withKeywords = filters.withKeywords,
                year = filters.year
            ).body()
            TmdbCollectionMediaType.TV -> tmdbApi.discoverTv(
                apiKey = BuildConfig.TMDB_API_KEY,
                language = language,
                page = page,
                sortBy = tvSort(source.sortBy),
                withCompanies = when (source.sourceType) {
                    TmdbCollectionSourceType.COMPANY -> source.tmdbId?.toString()
                    else -> filters.withCompanies
                },
                withNetworks = when (source.sourceType) {
                    TmdbCollectionSourceType.NETWORK -> source.tmdbId?.toString()
                    else -> filters.withNetworks
                },
                firstAirDateLte = filters.releaseDateLte ?: if (source.sourceType == TmdbCollectionSourceType.NETWORK) today else null,
                withStatus = if (source.sourceType == TmdbCollectionSourceType.NETWORK) "0|3|4" else null,
                voteCountGte = filters.voteCountGte,
                withGenres = filters.withGenres,
                firstAirDateGte = filters.releaseDateGte,
                voteAverageGte = filters.voteAverageGte,
                voteAverageLte = filters.voteAverageLte,
                withOriginalLanguage = filters.withOriginalLanguage,
                withOriginCountry = filters.withOriginCountry,
                withKeywords = filters.withKeywords,
                firstAirDateYear = filters.year
            ).body()
        } ?: error("TMDB discover returned no data")
        val items = response.results.orEmpty().mapNotNull { it.toPreview(mediaType) }.distinctBy { it.id }
        return row(
            source = source.copy(mediaType = mediaType),
            page = response.page ?: page,
            hasMore = (response.page ?: page) < (response.totalPages ?: page) && items.isNotEmpty(),
            items = items
        )
    }

    private fun row(source: TmdbCollectionSource, page: Int, hasMore: Boolean, items: List<MetaPreview>): CatalogRow {
        val mediaType = if (source.mediaType == TmdbCollectionMediaType.TV) "series" else source.mediaType.value
        return CatalogRow(
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "",
            catalogId = source.key(),
            catalogName = source.title,
            type = ContentType.fromString(mediaType),
            rawType = mediaType,
            items = items,
            isLoading = false,
            hasMore = hasMore,
            currentPage = page,
            supportsSkip = hasMore,
            skipStep = 20
        )
    }

    private fun List<MetaPreview>.sortedFor(sortBy: String): List<MetaPreview> {
        return when (sortBy) {
            TmdbCollectionSort.ORIGINAL.value -> this
            TmdbCollectionSort.VOTE_AVERAGE_DESC.value -> sortedWith(
                compareByDescending<MetaPreview> { it.imdbRating ?: -1f }
                    .thenByDescending { it.releaseInfo ?: "" }
            )
            TmdbCollectionSort.RELEASE_DATE_DESC.value,
            TmdbCollectionSort.FIRST_AIR_DATE_DESC.value -> sortedByDescending { it.releaseInfo ?: "" }
            TmdbCollectionSort.POPULAR_DESC.value -> this
            else -> this
        }
    }

    private fun TmdbListItem.toPreview(): MetaPreview? {
        val media = mediaType?.lowercase(Locale.US)
        val contentType = when (media) {
            "tv" -> ContentType.SERIES
            else -> ContentType.MOVIE
        }
        val rawType = if (contentType == ContentType.SERIES) "series" else "movie"
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        return MetaPreview(
            id = "tmdb:$id",
            type = contentType,
            rawType = rawType,
            name = title,
            poster = imageUrl(posterPath, "w500") ?: imageUrl(backdropPath, "w780"),
            posterShape = PosterShape.POSTER,
            background = imageUrl(backdropPath, "w1280"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = (releaseDate ?: firstAirDate)?.take(4),
            imdbRating = voteAverage?.toFloat(),
            genres = emptyList()
        )
    }

    private fun TmdbDiscoverResult.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        val contentType = if (mediaType == TmdbCollectionMediaType.TV) ContentType.SERIES else ContentType.MOVIE
        val rawType = if (mediaType == TmdbCollectionMediaType.TV) "series" else "movie"
        return MetaPreview(
            id = "tmdb:$id",
            type = contentType,
            rawType = rawType,
            name = title,
            poster = imageUrl(posterPath, "w500") ?: imageUrl(backdropPath, "w780"),
            posterShape = PosterShape.POSTER,
            background = imageUrl(backdropPath, "w1280"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = when (mediaType) {
                TmdbCollectionMediaType.MOVIE -> releaseDate?.take(4)
                TmdbCollectionMediaType.TV -> firstAirDate?.take(4)
            },
            imdbRating = voteAverage?.toFloat(),
            genres = emptyList()
        )
    }

    private fun TmdbCollectionSource.key(): String {
        return buildString {
            append("tmdb_")
            append(sourceType.name.lowercase(Locale.US))
            tmdbId?.let {
                append("_")
                append(it)
            }
            append("_")
            append(mediaType.value)
            append("_")
            append(sortBy.replace('.', '_'))
            filters.hashCode().takeIf { sourceType == TmdbCollectionSourceType.DISCOVER }?.let {
                append("_")
                append(it.toUInt().toString(16))
            }
        }
    }

    private fun movieSort(sortBy: String): String {
        return when (sortBy) {
            "first_air_date.desc" -> "primary_release_date.desc"
            else -> sortBy.ifBlank { "popularity.desc" }
        }
    }

    private fun tvSort(sortBy: String): String {
        return when (sortBy) {
            "primary_release_date.desc" -> "first_air_date.desc"
            else -> sortBy.ifBlank { "popularity.desc" }
        }
    }

    private fun imageUrl(path: String?, size: String): String? {
        val clean = path?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }
}
