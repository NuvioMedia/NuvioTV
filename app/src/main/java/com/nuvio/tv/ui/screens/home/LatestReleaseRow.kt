package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.util.formatCalendarReleaseDate
import java.time.LocalDate
import java.util.Locale

internal const val LATEST_RELEASE_ADDON_ID = "trakt-calendar"
internal const val LATEST_RELEASE_CATALOG_ID = "latest-releases"
// Titoli di "Ultime Uscite" di cui si prepara il trailer appena la riga esiste,
// così il primo focus in riga parte senza attendere l'estrazione YouTube.
internal const val TRAILER_ROW_PREFETCH_COUNT = 4

/**
 * Builds the synthetic Home row shown after Continua a guardare / In arrivo.
 *
 * The content mixes movies and series from the Trakt calendar, so it is
 * typed "all": localized type suffixes (" - Film"/ " - Serie") are skipped
 * for that api type. hasMore is false so pagination never triggers — the
 * classic/grid "See all" card routes to the Calendar screen instead of an
 * addon catalog (see NuvioNavHost's onNavigateToCatalogSeeAll).
 *
 * Each card gets its release date as releaseInfo so past releases of the
 * current month show their date ("5 Ottobre"); upcoming titles still prefer
 * the dynamic next-episode badge label.
 */
internal fun buildLatestReleaseHomeRow(
    items: List<CalendarItem>,
    title: String,
    format: (LocalDate) -> String
): HomeRow.Catalog? {
    // Cards without the title logo would fall back to plain text and look out of
    // place in this row, so they are dropped here (not in the repository: the
    // Calendar screen keeps them). The row is rebuilt on every emission, so an
    // item reappears by itself once enrichment fills in its logo.
    val metas = items.filter { !it.meta.logo.isNullOrBlank() }.map { item ->
        val date = item.releaseDate ?: return@map item.meta
        item.meta.copy(
            releaseInfo = format(date),
            released = date.toString()
        )
    }
    if (metas.isEmpty()) return null
    return HomeRow.Catalog(
        CatalogRow(
            addonId = LATEST_RELEASE_ADDON_ID,
            addonName = "Trakt",
            addonBaseUrl = "",
            catalogId = LATEST_RELEASE_CATALOG_ID,
            catalogName = title,
            type = ContentType.UNKNOWN,
            rawType = "all",
            items = metas,
            hasMore = false,
            supportsSkip = false
        )
    )
}

internal fun buildLatestReleaseHomeRow(
    context: Context,
    items: List<CalendarItem>
): HomeRow.Catalog? {
    return buildLatestReleaseHomeRow(
        items = items,
        title = localizedLatestReleaseTitle(context),
        format = ::formatCalendarReleaseDate
    )
}

private fun localizedLatestReleaseTitle(context: Context): String {
    val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
        ?: return context.getString(R.string.home_row_latest_releases)
    val config = Configuration(context.resources.configuration)
    config.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(config)
        .getString(R.string.home_row_latest_releases)
}
