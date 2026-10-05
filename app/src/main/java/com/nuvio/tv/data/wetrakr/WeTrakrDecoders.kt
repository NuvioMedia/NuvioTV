package com.nuvio.tv.data.wetrakr

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun weTrakrElement(body: String): JsonElement =
    runCatching { Json.parseToJsonElement(body) }.getOrElse { throw WeTrakrDecodingException() }

internal fun JsonElement.weTrakrObject(): JsonObject = this as? JsonObject ?: throw WeTrakrDecodingException()

internal fun JsonElement.weTrakrArray(): JsonArray = this as? JsonArray ?: throw WeTrakrDecodingException()

internal fun JsonObject.child(name: String): JsonObject? = when (val value = get(name)) {
    null, JsonNull -> null
    is JsonObject -> value
    else -> throw WeTrakrDecodingException()
}

internal fun JsonObject.text(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    (get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
}

internal fun JsonObject.long(vararg names: String): Long? = text(*names)?.toLongOrNull()

internal fun JsonObject.int(vararg names: String): Int? = long(*names)
    ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

internal fun JsonObject.decimal(vararg names: String): Double? = text(*names)?.toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun weTrakrTimestamp(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }
    .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
    .getOrElse { throw WeTrakrDecodingException() }

internal fun JsonObject.timestamp(vararg names: String): String? = text(*names)?.also(::weTrakrTimestamp)

internal fun decodeWeTrakrIds(value: JsonObject?): WeTrakrIds {
    val ids = value ?: return WeTrakrIds()
    return WeTrakrIds(
        imdb = ids.text("imdb")?.takeIf { it.matches(Regex("tt[0-9]+")) },
        tmdb = ids.long("tmdb")?.takeIf { it > 0L },
        tvdb = ids.long("tvdb")?.takeIf { it > 0L }
    )
}

internal fun weTrakrImageUrl(value: String?, size: String): String? = when {
    value == null -> null
    value.startsWith("https://") || value.startsWith("http://") -> value
    value.startsWith('/') && !value.startsWith("//") -> "https://image.tmdb.org/t/p/$size$value"
    else -> null
}

internal fun decodeWeTrakrMedia(value: JsonObject): WeTrakrMedia = WeTrakrMedia(
    ids = decodeWeTrakrIds(value.child("ids")),
    id = value.long("id")?.takeIf { it > 0L },
    title = value.text("title", "name"),
    year = value.int("year") ?: value.text("release_date", "first_air_date")?.take(4)?.toIntOrNull(),
    poster = weTrakrImageUrl(value.text("poster_path"), "w500"),
    backdrop = weTrakrImageUrl(value.text("backdrop_path"), "w1280"),
    genres = (value["genres"] as? JsonArray).orEmpty().mapNotNull { genre ->
        when (genre) {
            is JsonPrimitive -> genre.contentOrNull
            is JsonObject -> genre.text("name")
            else -> null
        }?.trim()?.takeIf(String::isNotBlank)
    },
    runtimeMinutes = value.int("runtime")?.takeIf { it > 0 }
)

internal fun decodeWeTrakrUser(body: String): WeTrakrUser {
    val root = weTrakrElement(body).weTrakrObject()
    val user = root.child("user") ?: root
    val info = user.child("info")
    val id = user.long("id")?.takeIf { it > 0L } ?: throw WeTrakrAuthException(WeTrakrAuthError.INVALID_RESPONSE)
    return WeTrakrUser(
        id = id,
        username = user.text("username") ?: info?.text("username"),
        displayName = user.text("display_name") ?: info?.text("display_name")
    )
}

internal fun decodeWeTrakrActivities(body: String): WeTrakrActivities {
    val root = weTrakrElement(body).weTrakrObject()
    val values = buildMap {
        root.forEach { (section, value) ->
            when (value) {
                is JsonPrimitive -> value.contentOrNull?.let { put(section, it) }
                is JsonObject -> value.forEach { (field, stamp) ->
                    (stamp as? JsonPrimitive)?.contentOrNull?.let { put("$section.$field", it) }
                }
                else -> Unit
            }
        }
    }
    if ("all" !in values && values.isNotEmpty()) throw WeTrakrDecodingException()
    return WeTrakrActivities(values)
}

internal fun decodeWeTrakrWatched(body: String, type: WeTrakrItemType): List<WeTrakrWatchedRecord> =
    weTrakrElement(body).weTrakrArray().mapNotNull { element ->
        val row = element.weTrakrObject()
        val watchedAt = row.timestamp("last_watched_at", "watched_at", "tracked_at") ?: UNKNOWN_WATCH_DATE
        if (type == WeTrakrItemType.MOVIE) {
            val media = decodeWeTrakrMedia(row)
            if (media.ids.isEmpty) return@mapNotNull null
            WeTrakrWatchedRecord(WeTrakrItemType.MOVIE, media, watchedAt)
        } else {
            val show = row.child("show")
            val showIds = decodeWeTrakrIds(row.child("show_ids") ?: show?.child("ids"))
            if (showIds.isEmpty) return@mapNotNull null
            val season = row.int("season_number")?.takeIf { it >= 0 } ?: return@mapNotNull null
            val number = row.int("number")?.takeIf { it > 0 } ?: return@mapNotNull null
            WeTrakrWatchedRecord(
                WeTrakrItemType.EPISODE,
                WeTrakrMedia(showIds, id = row.long("show_id") ?: show?.long("id"), title = show?.text("title")),
                watchedAt, season, number
            )
        }
    }

internal fun decodeWeTrakrPlayback(body: String, type: WeTrakrItemType): List<WeTrakrPlayback> =
    weTrakrElement(body).weTrakrArray().mapNotNull { element ->
        val row = element.weTrakrObject()
        val playback = row.child("playback") ?: return@mapNotNull null
        val progress = playback.decimal("progress_percent")?.toFloat()?.coerceIn(0f, 100f) ?: return@mapNotNull null
        val updatedAt = playback.timestamp("tracked_at") ?: return@mapNotNull null
        val runtime = playback.long("runtime_seconds")?.takeIf { it > 0L }
        if (type == WeTrakrItemType.MOVIE) {
            val media = decodeWeTrakrMedia(row)
            if (media.ids.isEmpty) return@mapNotNull null
            WeTrakrPlayback(WeTrakrItemType.MOVIE, media, progress, updatedAt, runtimeSeconds = runtime)
        } else {
            val show = row.child("show")
            val media = show?.let(::decodeWeTrakrMedia)
                ?.let { it.copy(ids = it.ids.merged(decodeWeTrakrIds(row.child("show_ids")))) }
                ?: WeTrakrMedia(decodeWeTrakrIds(row.child("show_ids")), id = row.long("media_id", "show_id"))
            if (media.ids.isEmpty) return@mapNotNull null
            val season = row.int("season_number")?.takeIf { it >= 0 } ?: return@mapNotNull null
            val number = row.int("number")?.takeIf { it > 0 } ?: return@mapNotNull null
            WeTrakrPlayback(
                WeTrakrItemType.EPISODE, media, progress, updatedAt, season, number,
                episodeTitle = row.text("title", "name"), runtimeSeconds = runtime
            )
        }
    }

internal fun decodeWeTrakrWatchlist(body: String, type: WeTrakrItemType): List<WeTrakrWatchlistItem> =
    weTrakrElement(body).weTrakrArray().mapNotNull { element ->
        val row = element.weTrakrObject()
        val media = decodeWeTrakrMedia(row)
        if (media.ids.isEmpty) return@mapNotNull null
        WeTrakrWatchlistItem(type, media, row.timestamp("tracked_at", "added_at"))
    }

private const val UNKNOWN_WATCH_DATE = "1970-01-01T00:00:00Z"
