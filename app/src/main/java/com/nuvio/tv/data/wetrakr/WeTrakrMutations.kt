package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class WeTrakrTarget(
    val type: WeTrakrItemType,
    val media: WeTrakrMedia,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null
) {
    fun matches(record: WeTrakrWatchedRecord): Boolean = record.type == type &&
        media.ids.matches(record.media.ids) && record.season == season && record.episode == episode

    fun matches(record: WeTrakrPlayback): Boolean = record.type == type &&
        media.ids.matches(record.media.ids) && record.season == season && record.episode == episode

    fun watched(at: String) = WeTrakrWatchedRecord(type, media, at, season, episode)

    fun playback(progress: Float, at: String, previous: WeTrakrPlayback?) = WeTrakrPlayback(
        type = type,
        media = previous?.media?.merged(media) ?: media,
        progress = progress,
        updatedAt = at,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle ?: previous?.episodeTitle,
        runtimeSeconds = previous?.runtimeSeconds
    )

    fun scrobbleBody(progress: Double? = null, appVersion: String? = null): JsonObject = buildJsonObject {
        put(if (type == WeTrakrItemType.MOVIE) "movie" else "show", buildJsonObject {
            put("ids", media.ids.requestIds())
            media.title?.let { put("title", it) }
            media.year?.let { put("year", it) }
        })
        if (type == WeTrakrItemType.EPISODE) {
            put("episode", buildJsonObject {
                put("season", requireNotNull(season))
                put("number", requireNotNull(episode))
            })
        }
        progress?.let {
            require(it.isFinite() && it in 0.0..100.0)
            put("progress", BigDecimal.valueOf(it).setScale(2, RoundingMode.DOWN).toDouble())
        }
        appVersion?.takeIf(String::isNotBlank)?.let { put("app_version", it) }
    }
}

internal fun TrackingMediaReference.weTrakrTarget(): WeTrakrTarget? {
    val ids = ids.toWeTrakrIds() ?: return null
    val isMovie = kind == TrackingMediaKind.MOVIE || catalog?.contentType?.lowercase() in setOf("movie", "film")
    val media = WeTrakrMedia(ids, title = title, year = year, poster = posterUrl)
    if (isMovie) return WeTrakrTarget(WeTrakrItemType.MOVIE, media)
    val episode = episode ?: return WeTrakrTarget(WeTrakrItemType.SHOW, media)
    if (episode.usesTvdbSeasonMapping) return null
    val season = episode.season?.takeIf { it >= 0 } ?: return null
    val number = episode.number.takeIf { it > 0 } ?: return null
    return WeTrakrTarget(WeTrakrItemType.EPISODE, media, season, number, episode.title)
}

internal fun TrackingExternalIds.toWeTrakrIds(): WeTrakrIds? = WeTrakrIds(
    imdb = imdb?.takeIf { it.matches(Regex("tt[0-9]+")) },
    tmdb = tmdb?.takeIf { it > 0L },
    tvdb = tvdb?.toLongOrNull()?.takeIf { it > 0L }
).takeUnless { it.isEmpty }

internal fun WeTrakrIds.requestIds(): JsonObject = buildJsonObject {
    imdb?.let { put("imdb", it) }
    tmdb?.let { put("tmdb", it) }
    tvdb?.let { put("tvdb", it) }
}

internal data class WeTrakrTrackingChange(val target: WeTrakrTarget, val trackedAt: String?)

internal fun weTrakrTrackingBody(
    changes: List<WeTrakrTrackingChange>,
    status: String,
    allowRewatch: Boolean? = null
): String = buildJsonObject {
    allowRewatch?.let { put("allow_rewatch", it) }
    val movies = changes.filter { it.target.type == WeTrakrItemType.MOVIE }
    val shows = changes.filter { it.target.type != WeTrakrItemType.MOVIE }
        .groupBy { it.target.media.ids.contentId }
        .values
    if (movies.isNotEmpty()) put("movies", JsonArray(movies.map { change ->
        buildJsonObject {
            put("ids", change.target.media.ids.requestIds())
            put("status", status)
            change.trackedAt?.let { put("tracked_at", it) }
        }
    }))
    if (shows.isNotEmpty()) put("shows", JsonArray(shows.flatMap { group ->
        val ids = group.first().target.media.ids.requestIds()
        val whole = group.filter { it.target.type == WeTrakrItemType.SHOW }.map { change ->
            buildJsonObject {
                put("ids", ids)
                put("status", status)
                change.trackedAt?.let { put("tracked_at", it) }
            }
        }
        val episodes = group.filter { it.target.type == WeTrakrItemType.EPISODE }
        val nested = if (episodes.isEmpty()) emptyList() else listOf(buildJsonObject {
            put("ids", ids)
            put("status", status)
            put("seasons", JsonArray(episodes.groupBy { it.target.season }.toSortedMap(compareBy { it ?: -1 }).map { (season, items) ->
                buildJsonObject {
                    put("number", requireNotNull(season))
                    put("episodes", JsonArray(items.map { change ->
                        buildJsonObject {
                            put("number", requireNotNull(change.target.episode))
                            change.trackedAt?.let { put("tracked_at", it) }
                        }
                    }))
                }
            }))
        })
        whole + nested
    }))
}.toString()

internal fun weTrakrNotFoundCount(body: String): Int = weTrakrResultCount(body, "notFound", "not_found")

internal data class WeTrakrErrored(val alreadyWatched: Int = 0, val other: Int = 0) {
    val total: Int
        get() = alreadyWatched + other
}

internal fun weTrakrErrored(body: String): WeTrakrErrored {
    val section = weTrakrElement(body).weTrakrObject()["errored"] as? JsonObject ?: return WeTrakrErrored()
    val items = section.values.filterIsInstance<JsonArray>().flatten().filterIsInstance<JsonObject>()
    val already = items.count { item ->
        (item["error"] as? JsonPrimitive)?.contentOrNull?.startsWith(WETRAKR_ALREADY_WATCHED) == true
    }
    return WeTrakrErrored(already, items.size - already)
}

private const val WETRAKR_ALREADY_WATCHED = "Already watched!"

private fun weTrakrResultCount(body: String, vararg keys: String): Int {
    val root = weTrakrElement(body).weTrakrObject()
    val section = keys.firstNotNullOfOrNull { root[it] } ?: return 0
    return section.resultItems()
}

private fun JsonElement.resultItems(): Int = when (this) {
    is JsonArray -> sumOf { item ->
        val seasons = (item as? JsonObject)?.get("seasons") as? JsonArray
        seasons?.sumOf { season ->
            ((season as? JsonObject)?.get("episodes") as? JsonArray)?.size?.coerceAtLeast(1) ?: 1
        } ?: 1
    }
    is JsonObject -> values.sumOf { it.resultItems() }
    else -> 0
}
