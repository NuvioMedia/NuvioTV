package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktCommentDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSearchResultDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.TraktCommentReview
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val COMMENTS_SORT = "likes"
private const val COMMENTS_LIMIT = 100
private const val COMMENTS_CACHE_TTL_MS = 10 * 60_000L
private val INLINE_SPOILER_REGEX = Regex(
    "\\[spoiler\\].*?\\[/spoiler\\]",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val INLINE_SPOILER_TAG_REGEX = Regex("\\[/?spoiler\\]", RegexOption.IGNORE_CASE)

internal enum class TraktCommentsType(val apiValue: String) {
    MOVIE("movie"),
    SHOW("show")
}

internal data class ResolvedCommentsTarget(
    val type: TraktCommentsType,
    val pathId: String
)

data class TraktCommentsPage(
    val items: List<TraktCommentReview>,
    val currentPage: Int,
    val pageCount: Int,
    val itemCount: Int
)

@Singleton
class TraktCommentsService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    private data class TimedCache(
        val pages: Map<Int, List<TraktCommentReview>>,
        val pageCount: Int,
        val itemCount: Int,
        val updatedAtMs: Long
    )

    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, TimedCache>()

    suspend fun getCommentsPage(
        meta: Meta,
        fallbackItemId: String? = null,
        fallbackItemType: String? = null,
        page: Int = 1,
        forceRefresh: Boolean = false
    ): TraktCommentsPage {
        val target = resolveCommentsTarget(meta, fallbackItemId, fallbackItemType)
            ?: return TraktCommentsPage(
                items = emptyList(),
                currentPage = page,
                pageCount = 0,
                itemCount = 0
            )
        val cacheKey = "${target.type.apiValue}|${target.pathId}"

        if (forceRefresh) {
            cacheMutex.withLock {
                cache.remove(cacheKey)
            }
        }

        if (!forceRefresh) {
            cacheMutex.withLock {
                val cached = cache[cacheKey]
                if (
                    cached != null &&
                    System.currentTimeMillis() - cached.updatedAtMs <= COMMENTS_CACHE_TTL_MS &&
                    cached.pages.containsKey(page)
                ) {
                    return TraktCommentsPage(
                        items = cached.pages.getValue(page),
                        currentPage = page,
                        pageCount = cached.pageCount,
                        itemCount = cached.itemCount
                    )
                }
            }
        }

        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            when (target.type) {
                TraktCommentsType.MOVIE -> traktApi.getMovieComments(
                    authorization = authHeader,
                    id = target.pathId,
                    sort = COMMENTS_SORT,
                    page = page,
                    limit = COMMENTS_LIMIT
                )

                TraktCommentsType.SHOW -> traktApi.getShowComments(
                    authorization = authHeader,
                    id = target.pathId,
                    sort = COMMENTS_SORT,
                    page = page,
                    limit = COMMENTS_LIMIT
                )
            }
        } ?: throw IllegalStateException("Trakt comments request failed")

        val comments = when {
            response.code() == 404 -> emptyList()
            !response.isSuccessful -> throw IllegalStateException("Failed to load Trakt reviews (${response.code()})")
            else -> response.body().orEmpty()
        }

        val pageCount = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: page
        val itemCount = response.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: comments.size
        val selected = filterDisplayableComments(comments).map(::toReviewModel)

        cacheMutex.withLock {
            val cached = cache[cacheKey]
            cache[cacheKey] = TimedCache(
                pages = (cached?.pages.orEmpty() + (page to selected)),
                pageCount = pageCount,
                itemCount = itemCount,
                updatedAtMs = System.currentTimeMillis()
            )
        }

        return TraktCommentsPage(
            items = selected,
            currentPage = page,
            pageCount = pageCount,
            itemCount = itemCount
        )
    }

    private suspend fun resolveCommentsTarget(
        meta: Meta,
        fallbackItemId: String?,
        fallbackItemType: String?
    ): ResolvedCommentsTarget? {
        val type = resolveCommentsType(meta = meta, fallbackItemType = fallbackItemType) ?: return null
        val directPathId = resolveDirectPathId(meta = meta, fallbackItemId = fallbackItemId)
        if (!directPathId.isNullOrBlank()) {
            return ResolvedCommentsTarget(type = type, pathId = directPathId)
        }

        val tmdbId = resolveTmdbCandidate(meta = meta, fallbackItemId = fallbackItemId) ?: return null
        val searchResponse = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.searchById(
                authorization = authHeader,
                idType = "tmdb",
                id = tmdbId.toString(),
                type = type.apiValue
            )
        } ?: throw IllegalStateException("Trakt TMDB search request failed")

        if (!searchResponse.isSuccessful) {
            if (searchResponse.code() == 404) return null
            throw IllegalStateException("Failed to resolve Trakt id (${searchResponse.code()})")
        }

        val resolvedPathId = searchResponse.body()
            .orEmpty()
            .firstOrNull { it.type.equals(type.apiValue, ignoreCase = true) }
            ?.toTraktPathId(type)

        return resolvedPathId?.let { ResolvedCommentsTarget(type = type, pathId = it) }
    }

    private fun resolveCommentsType(meta: Meta, fallbackItemType: String?): TraktCommentsType? {
        val normalizedType = listOf(meta.apiType, meta.rawType, fallbackItemType)
            .firstNotNullOfOrNull { value ->
                value?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            }

        return when (meta.type) {
            ContentType.MOVIE -> TraktCommentsType.MOVIE
            ContentType.SERIES, ContentType.TV -> TraktCommentsType.SHOW
            else -> when (normalizedType) {
                "movie" -> TraktCommentsType.MOVIE
                "series", "show", "tv" -> TraktCommentsType.SHOW
                else -> null
            }
        }
    }

    private fun resolveDirectPathId(meta: Meta, fallbackItemId: String?): String? {
        meta.imdbId?.takeIf { it.isNotBlank() }?.let { return it }

        val metaIds = parseContentIds(meta.id)
        metaIds.imdb?.takeIf { it.isNotBlank() }?.let { return it }
        metaIds.trakt?.let { return it.toString() }

        meta.slug?.takeIf { it.isNotBlank() }?.let { return it }

        val fallbackIds = parseContentIds(fallbackItemId)
        fallbackIds.imdb?.takeIf { it.isNotBlank() }?.let { return it }
        fallbackIds.trakt?.let { return it.toString() }

        return null
    }

    private fun resolveTmdbCandidate(meta: Meta, fallbackItemId: String?): Int? {
        val metaIds = parseContentIds(meta.id)
        if (metaIds.tmdb != null) return metaIds.tmdb

        return parseContentIds(fallbackItemId).tmdb
    }
}

internal fun filterDisplayableComments(comments: List<TraktCommentDto>): List<TraktCommentDto> {
    return comments.filter { !it.comment.isNullOrBlank() }
}

internal fun containsInlineSpoilers(comment: String?): Boolean {
    if (comment.isNullOrBlank()) return false
    return INLINE_SPOILER_REGEX.containsMatchIn(comment)
}

internal fun stripInlineSpoilerMarkup(comment: String?): String {
    if (comment.isNullOrBlank()) return ""
    return comment
        .replace(INLINE_SPOILER_TAG_REGEX, "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

internal fun TraktSearchResultDto.toTraktPathId(expectedType: TraktCommentsType): String? {
    val ids = when (expectedType) {
        TraktCommentsType.MOVIE -> movie?.ids
        TraktCommentsType.SHOW -> show?.ids
    }
    return ids.toBestCommentsPathId()
}

internal fun TraktIdsDto?.toBestCommentsPathId(): String? {
    if (this == null) return null
    return when {
        !imdb.isNullOrBlank() -> imdb
        trakt != null -> trakt.toString()
        !slug.isNullOrBlank() -> slug
        else -> null
    }
}

private fun toReviewModel(dto: TraktCommentDto): TraktCommentReview {
    val authorDisplayName = dto.user?.name
        ?.takeIf { it.isNotBlank() }
        ?: dto.user?.username
            ?.takeIf { it.isNotBlank() }
        ?: "Trakt user"

    return TraktCommentReview(
        id = dto.id,
        authorDisplayName = authorDisplayName,
        authorUsername = dto.user?.username?.takeIf { it.isNotBlank() },
        comment = stripInlineSpoilerMarkup(dto.comment),
        spoiler = dto.spoiler == true,
        containsInlineSpoilers = containsInlineSpoilers(dto.comment),
        review = dto.review == true,
        likes = dto.likes ?: 0,
        rating = dto.userStats?.rating,
        createdAt = dto.createdAt,
        updatedAt = dto.updatedAt
    )
}
