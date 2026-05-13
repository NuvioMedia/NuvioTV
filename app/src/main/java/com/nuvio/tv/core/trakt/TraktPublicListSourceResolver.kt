package com.nuvio.tv.core.trakt

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktProminentListDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSearchResultDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.normalizeContentId
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.TraktListSort
import com.nuvio.tv.domain.model.TraktSortHow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.util.Locale
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class TraktPublicListImportMetadata(
    val title: String? = null,
    val coverImageUrl: String? = null,
    val traktListId: Long? = null
)

data class TraktPublicListSearchResult(
    val traktListId: Long,
    val title: String,
    val subtitle: String,
    val coverImageUrl: String? = null,
    val sortBy: String? = null,
    val sortHow: String? = null
)

@Singleton
class TraktPublicListSourceResolver @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    private fun string(resId: Int): String = appContext.getString(resId)
    private fun string(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    fun resolve(source: TraktCollectionSource, page: Int = 1): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val type = source.mediaType.toTraktType()
                val sortBy = TraktListSort.normalize(source.sortBy)
                val sortHow = TraktSortHow.normalize(source.sortHow)
                val response = publicRequest {
                    traktApi.getPublicListItems(
                        id = source.traktListId.toString(),
                        type = type,
                        page = page,
                        limit = PAGE_LIMIT,
                        sortBy = sortBy,
                        sortHow = sortHow
                    )
                }
                val pageCountHeader = response.headers()["X-Pagination-Page-Count"]
                if (!response.isSuccessful) error(errorMessageFor(response.code(), string(R.string.collections_editor_error_load_trakt_list)))
                val rawItems = response.body().orEmpty()
                val items = rawItems
                    .mapNotNull { it.toPreview(source.mediaType) }
                    .distinctBy { "${it.apiType}:${it.id}" }
                val pageCount = pageCountHeader?.toIntOrNull() ?: page
                row(
                    source = source.copy(
                        sortBy = sortBy,
                        sortHow = sortHow
                    ),
                    page = page,
                    hasMore = page < pageCount && items.isNotEmpty(),
                    items = items
                )
            }
        }
        result.fold(
            onSuccess = { emit(NetworkResult.Success(it)) },
            onFailure = { emit(NetworkResult.Error(it.message ?: string(R.string.collections_editor_error_load_trakt_list))) }
        )
    }

    suspend fun listImportMetadata(input: String): TraktPublicListImportMetadata = withContext(Dispatchers.IO) {
        val idPath = parseTraktListPath(input) ?: error(string(R.string.collections_editor_error_trakt_list_id_or_url))
        val response = publicRequest { traktApi.getPublicList(id = idPath) }
        if (!response.isSuccessful) error(errorMessageFor(response.code(), string(R.string.collections_editor_error_trakt_list_not_found)))
        val list = response.body() ?: error(string(R.string.collections_editor_error_trakt_list_not_found))
        val id = list.ids?.trakt ?: idPath.toLongOrNull()
            ?: error(string(R.string.collections_editor_error_trakt_list_missing_numeric_id))
        TraktPublicListImportMetadata(
            title = list.name?.takeIf { it.isNotBlank() },
            coverImageUrl = list.images?.posters.firstTraktImageUrl(),
            traktListId = id
        )
    }

    suspend fun searchPublicLists(query: String): List<TraktPublicListSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val response = publicRequest {
            traktApi.searchLists(
                query = query.trim()
            )
        }
        if (!response.isSuccessful) error(errorMessageFor(response.code(), string(R.string.collections_editor_error_search_trakt_lists)))
        response.body().orEmpty().mapNotNull { it.toPublicListResult() }
    }

    suspend fun trendingPublicLists(): List<TraktPublicListSearchResult> = loadProminentLists {
        traktApi.getTrendingLists()
    }

    suspend fun popularPublicLists(): List<TraktPublicListSearchResult> = loadProminentLists {
        traktApi.getPopularLists()
    }

    fun parseTraktListId(input: String): Long? {
        return parseTraktListPath(input)?.toLongOrNull()
    }

    private suspend fun loadProminentLists(
        call: suspend () -> Response<List<TraktProminentListDto>>
    ): List<TraktPublicListSearchResult> = withContext(Dispatchers.IO) {
        val response = publicRequest(call)
        if (!response.isSuccessful) error(errorMessageFor(response.code(), string(R.string.collections_editor_error_load_trakt_lists)))
        response.body().orEmpty().mapNotNull { item ->
            item.list?.toPublicListResult(
                likeCount = item.likeCount
            )
        }
    }

    private suspend fun <T> publicRequest(call: suspend () -> Response<T>): Response<T> {
        return traktAuthService.executePublicRequest(call) ?: error(string(R.string.trakt_error_public_request_failed))
    }

    private fun row(source: TraktCollectionSource, page: Int, hasMore: Boolean, items: List<MetaPreview>): CatalogRow {
        val rawType = source.mediaType.toCollectionRawType()
        return CatalogRow(
            addonId = "trakt",
            addonName = "Trakt",
            addonBaseUrl = "",
            catalogId = source.key(),
            catalogName = source.title,
            type = ContentType.fromString(rawType),
            rawType = rawType,
            items = items,
            isLoading = false,
            hasMore = hasMore,
            currentPage = page,
            supportsSkip = hasMore,
            skipStep = PAGE_LIMIT
        )
    }

    private fun TraktListItemDto.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        return when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> movie?.toPreview()
            TmdbCollectionMediaType.TV -> show?.toPreview()
        }
    }

    private fun TraktMovieDto.toPreview(): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val fallback = when {
            ids?.trakt != null -> "trakt:${ids.trakt}"
            !ids?.slug.isNullOrBlank() -> "movie:${ids.slug}"
            else -> null
        }
        val contentId = normalizeContentId(ids, fallback)
        if (contentId.isBlank()) return null
        return MetaPreview(
            id = contentId,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = title,
            poster = images.traktBestPosterUrl(),
            posterShape = PosterShape.POSTER,
            background = images.traktBestBackdropUrl(),
            logo = images.traktBestLogoUrl(),
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = year?.toString() ?: released?.take(4),
            imdbRating = rating?.toFloat(),
            genres = genres.orEmpty(),
            runtime = runtime?.takeIf { it > 0 }?.let { "$it min" },
            status = status,
            ageRating = certification,
            language = languages?.firstOrNull(),
            released = released,
            country = country,
            imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
            slug = ids?.slug?.takeIf { it.isNotBlank() },
            landscapePoster = images.traktBestBackdropUrl(),
            rawPosterUrl = images.traktPosterUrl()
        )
    }

    private fun TraktShowDto.toPreview(): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val fallback = when {
            ids?.trakt != null -> "trakt:${ids.trakt}"
            !ids?.slug.isNullOrBlank() -> "series:${ids.slug}"
            else -> null
        }
        val contentId = normalizeContentId(ids, fallback)
        if (contentId.isBlank()) return null
        return MetaPreview(
            id = contentId,
            type = ContentType.SERIES,
            rawType = "series",
            name = title,
            poster = images.traktBestPosterUrl(),
            posterShape = PosterShape.POSTER,
            background = images.traktBestBackdropUrl(),
            logo = images.traktBestLogoUrl(),
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = year?.toString() ?: firstAired?.take(4),
            imdbRating = rating?.toFloat(),
            genres = genres.orEmpty(),
            runtime = runtime?.takeIf { it > 0 }?.let { "$it min" },
            status = status,
            ageRating = certification,
            language = languages?.firstOrNull(),
            released = firstAired,
            country = country,
            imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
            slug = ids?.slug?.takeIf { it.isNotBlank() },
            landscapePoster = images.traktBestBackdropUrl(),
            rawPosterUrl = images.traktPosterUrl()
        )
    }

    private fun TraktSearchResultDto.toPublicListResult(): TraktPublicListSearchResult? {
        if (!type.equals("list", ignoreCase = true)) return null
        return list?.toPublicListResult()
    }

    private fun TraktListSummaryDto.toPublicListResult(likeCount: Int? = null): TraktPublicListSearchResult? {
        val id = ids?.trakt ?: return null
        val listTitle = name?.takeIf { it.isNotBlank() }
            ?: string(R.string.collections_editor_trakt_list_with_id, id)
        val owner = user?.username?.takeIf { it.isNotBlank() }
        val stats = buildList {
            itemCount?.let { add(string(R.string.collections_editor_trakt_items_count, it)) }
            (likeCount ?: likes)?.let { add(string(R.string.collections_editor_trakt_likes_count, it)) }
        }
        val subtitle = (listOfNotNull(owner) + stats).joinToString(" • ")
            .ifBlank { string(R.string.collections_editor_trakt_public_list) }
        return TraktPublicListSearchResult(
            traktListId = id,
            title = listTitle,
            subtitle = subtitle,
            coverImageUrl = images?.posters.firstTraktImageUrl(),
            sortBy = sortBy,
            sortHow = sortHow
        )
    }

    private fun parseTraktListPath(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        trimmed.toLongOrNull()?.let { return it.toString() }
        Regex("""[?&]id=([^&#/]+)""")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        Regex("""trakt\.tv/lists/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        Regex("""trakt\.tv/users/[^/]+/lists/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return trimmed.takeIf { it.matches(Regex("""[A-Za-z0-9_-]+""")) }
    }

    private fun TmdbCollectionMediaType.toTraktType(): String {
        return when (this) {
            TmdbCollectionMediaType.MOVIE -> "movie"
            TmdbCollectionMediaType.TV -> "show"
        }
    }

    private fun TmdbCollectionMediaType.toCollectionRawType(): String {
        return when (this) {
            TmdbCollectionMediaType.MOVIE -> "movie"
            TmdbCollectionMediaType.TV -> "series"
        }
    }

    private fun TraktCollectionSource.key(): String {
        return listOf(
            "trakt",
            "list",
            traktListId.toString(),
            mediaType.value,
            sortBy.lowercase(Locale.US),
            sortHow.lowercase(Locale.US)
        ).joinToString("_")
    }

    private fun errorMessageFor(code: Int, fallback: String): String {
        return when (code) {
            401, 403 -> string(R.string.trakt_error_list_not_found_or_private)
            404 -> string(R.string.trakt_error_list_not_found_or_private)
            429 -> string(R.string.trakt_error_rate_limit_reached)
            else -> "$fallback ($code)"
        }
    }

    companion object {
        const val PAGE_LIMIT = 50
    }
}
