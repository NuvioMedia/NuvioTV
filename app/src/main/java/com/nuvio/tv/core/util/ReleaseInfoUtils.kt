package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
private val ISO_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

fun MetaPreview.isUnreleased(today: LocalDate): Boolean {
    val info = releaseInfo ?: return false
    // Try full date parse first (e.g. "2026-06-15")
    try {
        val date = LocalDate.parse(info.trim(), ISO_DATE_FORMATTER)
        return date.isAfter(today)
    } catch (_: DateTimeParseException) {
        // fall through to year-only
    }
    // Fall back to year extraction
    val yearStr = YEAR_REGEX.find(info)?.value ?: return false
    val year = yearStr.toIntOrNull() ?: return false
    return year > today.year
}

fun CatalogRow.filterReleasedItems(today: LocalDate): CatalogRow {
    val filtered = items.filterNot { it.isUnreleased(today) }
    return if (filtered.size == items.size) this else copy(items = filtered)
}
