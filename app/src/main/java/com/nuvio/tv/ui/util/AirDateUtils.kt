package com.nuvio.tv.ui.util

import android.content.Context
import com.nuvio.tv.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    val value = raw.trim()
    val zone = ZoneId.systemDefault()

    return runCatching {
        Instant.parse(value).atZone(zone).toLocalDate()
    }.getOrNull() ?: runCatching {
        OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(value).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDate.parse(value)
    }.getOrNull() ?: runCatching {
        val datePortion = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").find(value)?.value
            ?: return@runCatching null
        LocalDate.parse(datePortion)
    }.getOrNull()
}

internal fun computeAirDateBadgeText(
    context: Context,
    releasedIso: String?,
    airDateLabel: String?
): String? {
    if (releasedIso.isNullOrBlank()) {
        return airDateLabel?.let { context.getString(R.string.cw_airs_date, it) }
    }

    val releaseDate = parseEpisodeReleaseDate(releasedIso) ?: return null
    val today = LocalDate.now(ZoneId.systemDefault())
    val daysUntil = ChronoUnit.DAYS.between(today, releaseDate)

    return when {
        daysUntil < 0 -> null
        daysUntil == 0L -> context.getString(R.string.cw_airs_today)
        daysUntil == 1L -> context.getString(R.string.cw_airs_tomorrow)
        daysUntil in 2..7 -> context.resources.getQuantityString(
            R.plurals.cw_airs_in_days,
            daysUntil.toInt(),
            daysUntil.toInt()
        )
        else -> airDateLabel?.let { context.getString(R.string.cw_airs_date, it) }
    }
}
