package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import java.time.Instant

internal data class ParsedContentIds(
    val trakt: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null
)

internal fun parseContentIds(contentId: String?): ParsedContentIds {
    if (contentId.isNullOrBlank()) return ParsedContentIds()
    val raw = contentId.trim()

    if (raw.startsWith("tt")) {
        return ParsedContentIds(imdb = raw.substringBefore(':'))
    }

    if (raw.startsWith("tmdb:", ignoreCase = true)) {
        return ParsedContentIds(tmdb = raw.substringAfter(':').toIntOrNull())
    }

    if (raw.startsWith("trakt:", ignoreCase = true)) {
        return ParsedContentIds(trakt = raw.substringAfter(':').toIntOrNull())
    }

    val numeric = raw.substringBefore(':').toIntOrNull()
    return if (numeric != null) {
        ParsedContentIds(trakt = numeric)
    } else {
        ParsedContentIds()
    }
}

internal fun normalizeContentId(ids: TraktIdsDto?, fallback: String? = null): String {
    val imdb = ids?.imdb?.takeIf { it.isNotBlank() }
    if (!imdb.isNullOrBlank()) return imdb

    val tmdb = ids?.tmdb
    if (tmdb != null) return "tmdb:$tmdb"

    val trakt = ids?.trakt
    if (trakt != null) return "trakt:$trakt"

    return fallback?.takeIf { it.isNotBlank() } ?: ""
}

internal fun toTraktPathId(contentId: String): String {
    val parsed = parseContentIds(contentId)
    return when {
        !parsed.imdb.isNullOrBlank() -> parsed.imdb
        parsed.tmdb != null -> "tmdb:${parsed.tmdb}"
        parsed.trakt != null -> parsed.trakt.toString()
        else -> contentId
    }
}

internal fun extractYear(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    return Regex("(\\d{4})").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

internal fun parseIsoToMillis(value: String?): Long {
    if (value.isNullOrBlank()) return System.currentTimeMillis()
    return runCatching { Instant.parse(value).toEpochMilli() }
        .getOrElse { System.currentTimeMillis() }
}

internal fun toTraktIds(ids: ParsedContentIds): TraktIdsDto {
    return TraktIdsDto(
        trakt = ids.trakt,
        imdb = ids.imdb,
        tmdb = ids.tmdb
    )
}

internal fun TraktIdsDto.hasAnyId(): Boolean {
    return trakt != null || !imdb.isNullOrBlank() || tmdb != null || tvdb != null || !slug.isNullOrBlank()
}

/**
 * Returns true if the given contentId uses a Trakt-compatible prefix
 * (IMDB, TMDB, or Trakt numeric). IDs from other sources (kitsu:, mal:,
 * anilist:, anidb:, tvdb:, etc.) are NOT Trakt-resolvable and should be
 * preserved locally rather than being discarded when absent from Trakt
 * remote responses.
 */
internal fun isTraktCompatibleId(contentId: String?): Boolean {
    if (contentId.isNullOrBlank()) return false
    val raw = contentId.trim()
    if (raw.startsWith("tt")) return true
    if (raw.startsWith("tmdb:", ignoreCase = true)) return true
    if (raw.startsWith("trakt:", ignoreCase = true)) return true
    // Pure numeric IDs are treated as Trakt IDs
    val beforeColon = raw.substringBefore(':')
    if (beforeColon.toIntOrNull() != null) return true
    return false
}

/**
 * If [contentId] is not Trakt-resolvable but [videoId] contains a valid
 * IMDB or TMDB prefix, extract and return the resolved ID from videoId.
 * This handles addons that wrap real IDs in non-standard content IDs
 * (e.g. contentId = "tun_tt7821582", videoId = "tt7821582:3:7").
 *
 * Returns the original [contentId] if it's already valid or if no
 * better ID can be extracted from [videoId].
 */
internal fun resolveEffectiveContentId(contentId: String, videoId: String?): String {
    val parsedContent = parseContentIds(contentId)
    val contentIds = toTraktIds(parsedContent)
    if (contentIds.hasAnyId()) return contentId

    if (videoId.isNullOrBlank() || videoId == contentId) return contentId

    val parsedVideo = parseContentIds(videoId)
    val videoIds = toTraktIds(parsedVideo)
    if (!videoIds.hasAnyId()) return contentId

    // Rebuild a canonical content ID from the resolved video IDs
    return when {
        !parsedVideo.imdb.isNullOrBlank() -> parsedVideo.imdb
        parsedVideo.tmdb != null -> "tmdb:${parsedVideo.tmdb}"
        parsedVideo.trakt != null -> parsedVideo.trakt.toString()
        else -> contentId
    }
}
