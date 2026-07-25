package com.nuvio.tv.core.util

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

private val ISO_DATE_PATTERN = Regex("(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)")

/**
 * Converts timestamped episode releases into the viewer's local calendar date. Plain dates have
 * no timezone information and are preserved as supplied by the provider.
 */
internal fun parseEpisodeReleaseLocalDate(
    raw: String?,
    zoneId: ZoneId = ZoneId.systemDefault()
): LocalDate? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return runCatching { LocalDate.parse(value) }.getOrNull()
        ?: parseExplicitReleaseInstant(value)?.atZone(zoneId)?.toLocalDate()
        ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: parseEmbeddedReleaseDate(value)
}

/**
 * Returns whether a known release has been reached. Zoned timestamps use their exact instant,
 * while date-only values use UTC midnight. A local timestamp without a zone is interpreted in
 * the viewer's timezone.
 */
internal fun isEpisodeReleaseAired(
    raw: String?,
    clock: Clock = Clock.systemDefaultZone()
): Boolean? {
    val releaseInstant = parseEpisodeReleaseInstant(raw, clock.zone) ?: return null
    return !releaseInstant.isAfter(clock.instant())
}

internal fun parseEpisodeReleaseInstant(
    raw: String?,
    zoneId: ZoneId = ZoneId.systemDefault()
): Instant? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return parseExplicitReleaseInstant(value)
        ?: runCatching { LocalDateTime.parse(value).atZone(zoneId).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
        ?: parseEmbeddedReleaseDate(value)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
}

/**
 * Uses the add-on release timestamp unless the user explicitly opts into TMDB's date-only
 * broadcaster calendar dates.
 */
internal fun selectEpisodeReleaseValue(
    addonReleased: String?,
    tmdbAirDate: String?,
    useTmdbReleaseDates: Boolean
): String? {
    val addonValue = addonReleased?.trim()?.takeIf { it.isNotEmpty() }
    if (!useTmdbReleaseDates) return addonValue
    return tmdbAirDate?.trim()?.takeIf { it.isNotEmpty() } ?: addonValue
}

private fun parseExplicitReleaseInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()

private fun parseEmbeddedReleaseDate(value: String): LocalDate? =
    ISO_DATE_PATTERN.find(value)?.value?.let { datePortion ->
        runCatching { LocalDate.parse(datePortion) }.getOrNull()
    }
