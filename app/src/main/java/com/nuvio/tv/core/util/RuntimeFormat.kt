package com.nuvio.tv.core.util

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale

/**
 * Formats a duration as a compact label in the user's language, using the platform's ICU data:
 * "1h 20m" in English, "1 שע׳ 20 דק׳" in Hebrew, "1 ч 20 мин" in Russian, and so on.
 *
 * Returns null for a zero or negative duration so callers can drop the label instead of showing "0m".
 * Single implementation for every place that shows a runtime (hero, home carousel, episode cards,
 * post-play), which previously each hardcoded Latin "h" / "m" units.
 */
fun formatRuntimeMinutes(totalMinutes: Int, locale: Locale = Locale.getDefault()): String? {
    if (totalMinutes <= 0) return null
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val parts = buildList {
        if (hours > 0) add(Measure(hours, MeasureUnit.HOUR))
        if (minutes > 0) add(Measure(minutes, MeasureUnit.MINUTE))
    }
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
        .formatMeasures(*parts.toTypedArray())
}
