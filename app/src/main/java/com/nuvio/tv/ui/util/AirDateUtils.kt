package com.nuvio.tv.ui.util

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
    return parseEpisodeReleaseLocalDate(raw)
}

internal fun computeAirDateBadgeText(
    context: Context,
    releasedIso: String?,
    airDateLabel: String?
): String? {
    if (releasedIso.isNullOrBlank()) {
        return airDateLabel?.let { context.getString(R.string.cw_airs_date, it) }
    }
    if (isEpisodeReleaseAired(releasedIso) == true) return null

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
