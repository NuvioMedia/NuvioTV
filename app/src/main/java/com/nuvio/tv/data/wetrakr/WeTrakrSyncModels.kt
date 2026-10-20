package com.nuvio.tv.data.wetrakr

import kotlinx.serialization.Serializable

@Serializable
data class WeTrakrIds(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
    val mal: Long? = null,
    val anilist: Long? = null,
    val kitsu: Long? = null,
    val anidb: Long? = null
) {
    val isEmpty: Boolean
        get() = imdb == null && tmdb == null && tvdb == null && !hasAnimeIds

    val hasAnimeIds: Boolean
        get() = mal != null || anilist != null || kitsu != null || anidb != null

    val isAnimeOnly: Boolean
        get() = imdb == null && tmdb == null && tvdb == null && hasAnimeIds

    val contentId: String?
        get() = imdb ?: tmdb?.let { "tmdb:$it" } ?: tvdb?.let { "tvdb:$it" } ?: kitsu?.let { "kitsu:$it" }
            ?: mal?.let { "mal:$it" } ?: anilist?.let { "anilist:$it" } ?: anidb?.let { "anidb:$it" }

    fun matches(other: WeTrakrIds): Boolean =
        (imdb != null && imdb == other.imdb) || (tmdb != null && tmdb == other.tmdb) ||
            (tvdb != null && tvdb == other.tvdb) || (mal != null && mal == other.mal) ||
            (anilist != null && anilist == other.anilist) || (kitsu != null && kitsu == other.kitsu) ||
            (anidb != null && anidb == other.anidb)

    fun merged(other: WeTrakrIds) = WeTrakrIds(
        imdb ?: other.imdb, tmdb ?: other.tmdb, tvdb ?: other.tvdb,
        mal ?: other.mal, anilist ?: other.anilist, kitsu ?: other.kitsu, anidb ?: other.anidb
    )

    fun aliases(): Set<String> = buildSet {
        imdb?.let { add(it); add("imdb:$it") }
        tmdb?.let { add("tmdb:$it") }
        tvdb?.let { add("tvdb:$it") }
        mal?.let { add("mal:$it") }
        anilist?.let { add("anilist:$it") }
        kitsu?.let { add("kitsu:$it") }
        anidb?.let { add("anidb:$it") }
    }
}

@Serializable
data class WeTrakrMedia(
    val ids: WeTrakrIds,
    val id: Long? = null,
    val title: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null
) {
    fun merged(other: WeTrakrMedia) = WeTrakrMedia(
        ids.merged(other.ids), id ?: other.id, title ?: other.title, year ?: other.year,
        poster ?: other.poster, backdrop ?: other.backdrop, genres.ifEmpty { other.genres },
        runtimeMinutes ?: other.runtimeMinutes
    )
}

@Serializable
enum class WeTrakrItemType { MOVIE, SHOW, EPISODE }

@Serializable
data class WeTrakrWatchedRecord(
    val type: WeTrakrItemType,
    val media: WeTrakrMedia,
    val watchedAt: String,
    val season: Int? = null,
    val episode: Int? = null
) {
    val key: String
        get() = weTrakrRecordKey(type, media.ids, season, episode)
}

@Serializable
data class WeTrakrPlayback(
    val type: WeTrakrItemType,
    val media: WeTrakrMedia,
    val progress: Float,
    val updatedAt: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val runtimeSeconds: Long? = null
) {
    val key: String
        get() = weTrakrRecordKey(type, media.ids, season, episode)
}

@Serializable
data class WeTrakrWatchlistItem(
    val type: WeTrakrItemType,
    val media: WeTrakrMedia,
    val addedAt: String? = null
) {
    val key: String
        get() = weTrakrRecordKey(type, media.ids, null, null)
}

@Serializable
data class WeTrakrActivities(val values: Map<String, String> = emptyMap()) {
    operator fun get(key: String): String? = values[key]

    fun changed(previous: WeTrakrActivities, vararg keys: String): Boolean =
        keys.any { values[it] != previous.values[it] }
}

@Serializable
enum class WeTrakrSyncBucket { WATCHED_MOVIES, WATCHED_EPISODES, PLAYBACK, WATCHLIST }

@Serializable
data class WeTrakrSyncSnapshot(
    val accountId: Long,
    val watched: List<WeTrakrWatchedRecord> = emptyList(),
    val playback: List<WeTrakrPlayback> = emptyList(),
    val watchlist: List<WeTrakrWatchlistItem> = emptyList(),
    val activities: WeTrakrActivities? = null,
    val checkedAtEpochMs: Long? = null,
    val isInitialized: Boolean = false,
    val invalidatedBuckets: Set<WeTrakrSyncBucket> = emptySet()
)

data class WeTrakrPage<T>(
    val items: List<T>,
    val nextCursor: String? = null
)

internal fun weTrakrRecordKey(type: WeTrakrItemType, ids: WeTrakrIds, season: Int?, episode: Int?): String =
    "$type:${ids.contentId ?: throw WeTrakrDecodingException()}:${season ?: -1}:${episode ?: -1}"
