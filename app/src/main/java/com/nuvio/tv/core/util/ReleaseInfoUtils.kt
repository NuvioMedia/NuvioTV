package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import java.time.Clock
import java.time.LocalDate

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

fun MetaPreview.isUnreleased(
    today: LocalDate,
    clock: Clock = Clock.systemDefaultZone()
): Boolean {
    released?.trim()?.takeIf { it.isNotEmpty() }?.let { rawReleased ->
        isEpisodeReleaseAired(rawReleased, clock)?.let { hasAired ->
            return !hasAired
        }
    }

    val info = releaseInfo ?: return false
    isEpisodeReleaseAired(info.trim(), clock)?.let { hasAired ->
        return !hasAired
    }
    val yearStr = YEAR_REGEX.find(info)?.value ?: return false
    val year = yearStr.toIntOrNull() ?: return false
    return year > today.year
}

fun CatalogRow.filterReleasedItems(
    today: LocalDate,
    clock: Clock = Clock.systemDefaultZone()
): CatalogRow {
    val filtered = items.filterNot { it.isUnreleased(today, clock) }
    return if (filtered.size == items.size) this else copy(items = filtered)
}
