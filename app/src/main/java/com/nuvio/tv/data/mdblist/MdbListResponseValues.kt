package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.util.daysInMonth
import com.nuvio.tv.core.util.daysSinceEpoch
import com.nuvio.tv.core.util.decimalAt
import com.nuvio.tv.core.util.fractionMillisAt
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal fun mdbListResponseElement(body: String): JsonElement =
    runCatching { Json.parseToJsonElement(body) }.getOrElse { throw MdbListDecodingException() }

internal fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: throw MdbListDecodingException()
internal fun JsonObject.objectValue(name: String): JsonObject? = when (val value = get(name)) {
    null, JsonNull -> null
    else -> value.objectValue()
}

internal fun JsonObject.arrayValue(name: String): JsonArray = when (val value = get(name)) {
    null, JsonNull -> JsonArray(emptyList())
    is JsonArray -> value
    else -> throw MdbListDecodingException()
}

internal fun JsonObject.text(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    (get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
}

internal fun JsonObject.number(vararg names: String): Long? = text(*names)?.toLongOrNull()
internal fun JsonObject.integer(vararg names: String): Int? = number(*names)
    ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
internal fun JsonObject.flag(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull

private val mdbListLocalTimestamp = DateTimeFormatterBuilder()
    .appendPattern("yyyy-MM-dd HH:mm:ss")
    .optionalStart()
    .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
    .optionalEnd()
    .toFormatter()

internal fun mdbListTimestamp(value: String): Long =
    parseMdbListTimestampFast(value) ?: parseMdbListTimestampSlow(value)

private fun parseMdbListTimestampSlow(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }
    .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
    .recoverCatching { LocalDateTime.parse(value, mdbListLocalTimestamp).toInstant(ZoneOffset.UTC).toEpochMilli() }
    .getOrElse { throw MdbListDecodingException() }

private fun parseMdbListTimestampFast(text: String): Long? {
    val length = text.length
    if (length < MDBLIST_TIMESTAMP_MIN_LENGTH) return null
    if (text[4] != '-' || text[7] != '-' || text[13] != ':' || text[16] != ':') return null
    val separator = text[10]
    if (separator != ' ' && separator != 'T' && separator != 't') return null
    val year = decimalAt(text, 0, 4)
    val month = decimalAt(text, 5, 2)
    val day = decimalAt(text, 8, 2)
    val hour = decimalAt(text, 11, 2)
    val minute = decimalAt(text, 14, 2)
    val second = decimalAt(text, 17, 2)
    if (year < 1 || month !in 1..12 || day < 1) return null
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    if (day > daysInMonth(year, month)) return null
    var index = MDBLIST_TIMESTAMP_MIN_LENGTH
    var millis = 0
    if (index < length && text[index] == '.') {
        val fractionStart = index + 1
        var fractionEnd = fractionStart
        while (fractionEnd < length && text[fractionEnd] in '0'..'9') fractionEnd++
        if (fractionEnd - fractionStart !in 1..9) return null
        millis = fractionMillisAt(text, fractionStart, fractionEnd)
        if (millis < 0) return null
        index = fractionEnd
    }
    val offsetSeconds = when {
        index == length -> if (separator == ' ') 0L else return null
        separator == ' ' -> return null
        text[index] == 'Z' || text[index] == 'z' -> {
            if (index != length - 1) return null
            0L
        }
        text[index] == '+' || text[index] == '-' -> {
            if (length - index != 6 || text[index + 3] != ':') return null
            val zoneHour = decimalAt(text, index + 1, 2)
            val zoneMinute = decimalAt(text, index + 4, 2)
            if (zoneHour < 0 || zoneMinute < 0 || zoneMinute > 59) return null
            if (zoneHour > 18 || (zoneHour == 18 && zoneMinute != 0)) return null
            val magnitude = zoneHour * 3_600L + zoneMinute * 60L
            if (text[index] == '-') -magnitude else magnitude
        }
        else -> return null
    }
    val localSeconds = daysSinceEpoch(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second
    return (localSeconds - offsetSeconds) * 1_000L + millis
}

private const val MDBLIST_TIMESTAMP_MIN_LENGTH = 19

internal fun JsonObject.timestamp(vararg names: String): String? = text(*names)?.also(::mdbListTimestamp)

internal fun String.isMdbListImdbId(): Boolean {
    if (length < 3 || this[0] != 't' || this[1] != 't') return false
    for (index in 2 until length) {
        if (this[index] !in '0'..'9') return false
    }
    return true
}

internal fun decodeMdbListIds(value: JsonObject): MdbListIds {
    val ids = value.objectValue("ids") ?: value
    return MdbListIds(
        imdb = ids.text("imdb", "imdbid")?.takeIf(String::isMdbListImdbId),
        tmdb = ids.number("tmdb", "tmdbid")?.takeIf { it > 0L },
        tvdb = ids.number("tvdb", "tvdbid")?.takeIf { it > 0L },
        trakt = ids.number("trakt", "traktid")?.takeIf { it > 0L },
        mdblist = ids.text("mdblist")
    ).also { it.key }
}

internal fun decodeMdbListMedia(value: JsonObject): MdbListMedia = MdbListMedia(
    ids = decodeMdbListIds(value),
    title = value.text("title", "name"),
    year = value.integer("year"),
    poster = mdbListImageUrl(value.text("poster"), "w500"),
    backdrop = mdbListImageUrl(value.text("backdrop", "background"), "w1280"),
    runtimeMinutes = value.number("runtime")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
)

internal fun mdbListImageUrl(value: String?, size: String): String? = when {
    value == null -> null
    value.startsWith("https://") || value.startsWith("http://") -> value
    value.startsWith('/') && !value.startsWith("//") -> "https://image.tmdb.org/t/p/$size$value"
    else -> null
}

internal fun <T> mdbListPage(value: JsonObject, items: List<T>): MdbListPage<T> {
    val pagination = value.objectValue("pagination") ?: value
    val cursor = pagination.text("next_cursor")
    val offset = pagination.number("offset")
    val limit = pagination.number("limit")
    val total = pagination.number("total")
    val hasMore = pagination.flag("has_more") ?: if (offset != null && limit != null && total != null) {
        offset + limit < total
    } else false
    val nextOffset = if (hasMore == true && cursor == null) {
        if (offset == null || offset < 0L || limit == null || limit < 1L || offset + limit > Int.MAX_VALUE) {
            throw MdbListDecodingException()
        }
        (offset + limit).toInt()
    } else null
    return MdbListPage(items, cursor, nextOffset, value.timestamp("server_time"))
}
