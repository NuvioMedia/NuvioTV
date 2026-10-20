package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.extractTrackingYear
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.repository.TraktCommentsPage
import com.nuvio.tv.data.repository.containsInlineSpoilers
import com.nuvio.tv.data.repository.stripInlineSpoilerMarkup
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal enum class WeTrakrCatalogKind(val path: String, val filter: String) {
    MOVIE("movies", "movie"),
    SHOW("shows", "show")
}

class WeTrakrCatalogService(
    private val api: WeTrakrApiClient,
    private val sync: WeTrakrSyncRepository,
    private val userFallback: () -> String,
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Timed<T>(val value: T, val atMs: Long)

    private val titleIds = ConcurrentHashMap<String, Long>()
    private val episodeIds = ConcurrentHashMap<String, Long>()
    private val comments = ConcurrentHashMap<String, Timed<TraktCommentsPage>>()
    private val similar = ConcurrentHashMap<String, Timed<List<MetaPreview>>>()

    suspend fun getCommentsPage(
        meta: Meta,
        fallbackItemId: String? = null,
        fallbackItemType: String? = null,
        targetEpisode: Video? = null,
        page: Int = 1,
        forceRefresh: Boolean = false
    ): TraktCommentsPage {
        val empty = TraktCommentsPage(emptyList(), page, 0, 0)
        val kind = weTrakrCatalogKind(meta, fallbackItemType) ?: return empty
        val titleId = resolveTitleId(kind, meta, fallbackItemId) ?: return empty
        val season = targetEpisode?.season
        val number = targetEpisode?.episode
        val path = if (kind == WeTrakrCatalogKind.SHOW && season != null && number != null) {
            val episodeId = resolveEpisodeId(titleId, season, number) ?: return empty
            "/episodes/$episodeId/comments"
        } else {
            "/${kind.path}/$titleId/comments"
        }
        val key = "$path|$page"
        if (forceRefresh) comments.keys.removeAll { it.startsWith("$path|") }
        comments[key]?.takeIf { now() - it.atMs <= CACHE_TTL_MS }?.let { return it.value }
        val response = api.get(
            path,
            query = mapOf("page" to page.toString(), "limit" to COMMENTS_LIMIT.toString()),
            acceptedStatuses = setOf(404)
        )
        val items = if (response.status == 404) emptyList() else decodeWeTrakrComments(response.body, userFallback())
        val result = TraktCommentsPage(
            items = items,
            currentPage = page,
            pageCount = response.header("X-Pagination-Page-Count")?.trim()?.toIntOrNull() ?: page,
            itemCount = response.header("X-Pagination-Item-Count")?.trim()?.toIntOrNull() ?: items.size
        )
        comments[key] = Timed(result, now())
        return result
    }

    suspend fun getSimilar(meta: Meta, fallbackItemId: String? = null, fallbackItemType: String? = null): List<MetaPreview> {
        val kind = weTrakrCatalogKind(meta, fallbackItemType) ?: return emptyList()
        val titleId = resolveTitleId(kind, meta, fallbackItemId) ?: return emptyList()
        val key = "${kind.path}/$titleId"
        similar[key]?.takeIf { now() - it.atMs <= CACHE_TTL_MS }?.let { return it.value }
        val response = api.get("/${kind.path}/$titleId/similar", acceptedStatuses = setOf(404))
        val items = if (response.status == 404) emptyList() else decodeWeTrakrSimilar(response.body)
        similar[key] = Timed(items, now())
        return items
    }

    private suspend fun resolveTitleId(kind: WeTrakrCatalogKind, meta: Meta, fallbackItemId: String?): Long? {
        val ids = listOfNotNull(meta.id, fallbackItemId, meta.imdbId)
            .mapNotNull { parseTrackingExternalIds(it).toWeTrakrIds() }
            .fold(WeTrakrIds()) { acc, next -> acc.merged(next) }
        if (ids.isEmpty) return null
        val aliases = ids.aliases()
        aliases.firstNotNullOfOrNull { titleIds["${kind.filter}|$it"] }?.let { return it }
        val found = idFromSnapshot(kind, ids) ?: search(kind, ids, meta.name)
        found?.let { (id, resultIds) -> (aliases + resultIds.aliases()).forEach { titleIds["${kind.filter}|$it"] = id } }
        return found?.first
    }

    private fun idFromSnapshot(kind: WeTrakrCatalogKind, ids: WeTrakrIds): Pair<Long, WeTrakrIds>? {
        val snapshot = sync.currentSnapshot() ?: return null
        val wanted = if (kind == WeTrakrCatalogKind.MOVIE) setOf(WeTrakrItemType.MOVIE) else setOf(WeTrakrItemType.SHOW, WeTrakrItemType.EPISODE)
        val media = snapshot.watched.filter { it.type in wanted }.map { it.media } +
            snapshot.playback.filter { it.type in wanted }.map { it.media } +
            snapshot.watchlist.filter { it.type in wanted }.map { it.media }
        return media.firstOrNull { it.id != null && it.ids.matches(ids) }?.let { it.id!! to it.ids }
    }

    private suspend fun search(kind: WeTrakrCatalogKind, ids: WeTrakrIds, title: String): Pair<Long, WeTrakrIds>? {
        val query = ids.imdb ?: title.trim().takeIf(String::isNotBlank) ?: return null
        val response = api.get("/search", query = mapOf("q" to query, "filter_type" to kind.filter))
        return decodeWeTrakrSearch(response.body, kind.filter).firstOrNull { it.second.matches(ids) }
    }

    private suspend fun resolveEpisodeId(showId: Long, season: Int, number: Int): Long? {
        val key = "$showId|$season|$number"
        episodeIds[key]?.let { return it }
        val response = api.get("/shows/$showId/seasons/$season/episodes/$number", acceptedStatuses = setOf(404))
        if (response.status == 404) return null
        val id = weTrakrElement(response.body).weTrakrObject().long("id") ?: return null
        episodeIds[key] = id
        return id
    }

    private companion object {
        const val COMMENTS_LIMIT = 50
        const val CACHE_TTL_MS = 10 * 60_000L
    }
}

internal fun weTrakrCatalogKind(meta: Meta, fallbackItemType: String?): WeTrakrCatalogKind? = when (meta.type) {
    ContentType.MOVIE -> WeTrakrCatalogKind.MOVIE
    ContentType.SERIES, ContentType.TV -> WeTrakrCatalogKind.SHOW
    else -> when (listOf(meta.apiType, meta.rawType, fallbackItemType).firstNotNullOfOrNull { it?.trim()?.lowercase()?.takeIf(String::isNotBlank) }) {
        "movie" -> WeTrakrCatalogKind.MOVIE
        "series", "show", "tv", "anime" -> WeTrakrCatalogKind.SHOW
        else -> null
    }
}

internal fun decodeWeTrakrSearch(body: String, type: String): List<Pair<Long, WeTrakrIds>> =
    weTrakrElement(body).weTrakrArray().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if (item.text("type")?.equals(type, ignoreCase = true) == false) return@mapNotNull null
        val id = item.long("id") ?: return@mapNotNull null
        id to decodeWeTrakrIds(item.child("ids"))
    }

internal fun decodeWeTrakrComments(body: String, userFallback: String): List<TraktCommentReview> =
    weTrakrElement(body).weTrakrArray().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        if ((item["hidden"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
        val raw = item.text("text")?.let(::weTrakrPlainText)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val id = item.long("id") ?: return@mapNotNull null
        val user = item.child("user")
        val info = user?.child("info")
        val username = info?.text("username")?.takeIf(String::isNotBlank)
        TraktCommentReview(
            id = id,
            authorDisplayName = info?.text("display_name")?.takeIf(String::isNotBlank) ?: username ?: userFallback,
            authorUsername = username,
            comment = stripInlineSpoilerMarkup(raw),
            spoiler = (item["spoiler"] as? JsonPrimitive)?.booleanOrNull == true,
            containsInlineSpoilers = containsInlineSpoilers(raw),
            review = (item["is_long"] as? JsonPrimitive)?.booleanOrNull == true,
            likes = item.child("interactions")?.child("counter")?.int("likes") ?: item.int("likes") ?: 0,
            rating = item.int("rating")?.takeIf { it in 1..10 },
            createdAt = item.text("comment_added_at"),
            updatedAt = item.text("comment_updated_at")
        )
    }

internal fun decodeWeTrakrSimilar(body: String): List<MetaPreview> =
    weTrakrElement(body).weTrakrArray().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val title = item.text("title", "name")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val ids = decodeWeTrakrIds(item.child("ids"))
        val contentId = ids.contentId ?: return@mapNotNull null
        val isMovie = item.text("type")?.equals("movie", ignoreCase = true) == true
        val backdrop = item.text("backdrop_path")
        val landscape = weTrakrImageUrl(backdrop, "w780")
        val released = item.text("release_date", "first_air_date")
        MetaPreview(
            id = contentId,
            type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
            rawType = if (isMovie) "movie" else "series",
            name = title,
            poster = landscape ?: weTrakrImageUrl(item.text("poster_path"), "w500"),
            posterShape = PosterShape.LANDSCAPE,
            background = weTrakrImageUrl(backdrop, "w1280"),
            logo = null,
            description = item.text("overview")?.trim()?.takeIf(String::isNotBlank),
            releaseInfo = extractTrackingYear(released)?.toString(),
            imdbRating = item.decimal("rating")?.toFloat(),
            genres = (item["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            runtime = item.int("runtime")?.takeIf { it > 0 }?.let { "$it min" },
            released = released,
            imdbId = ids.imdb,
            landscapePoster = weTrakrImageUrl(backdrop, "w1280"),
            rawPosterUrl = landscape
        )
    }.distinctBy { "${it.rawType}:${it.id}" }

private val HTML_TAG = Regex("<[^>]+>")

internal fun weTrakrPlainText(value: String): String = value
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(HTML_TAG, "")
    .replace("&nbsp;", " ")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
    .trim()
