package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Marks the row as ours: it has no addon behind it, so nothing may look it up as a catalog. */
internal const val HOME_WATCHLIST_ADDON_ID = "nuvio_home_watchlist"
private const val HOME_WATCHLIST_CATALOG_ID = "watchlist"

/** Rating batches and status observers do work per item, so a huge row would stall low power Fire TV sticks. */
private const val HOME_WATCHLIST_ROW_LIMIT = 100

internal fun CatalogRow.isHomeWatchlistRow(): Boolean = addonId == HOME_WATCHLIST_ADDON_ID

internal fun selectHomeWatchlistEntries(
    sourceMode: LibrarySourceMode,
    items: List<LibraryEntry>,
    tabs: List<LibraryListTab>
): List<LibraryEntry> {
    // Saved items have no watchlist concept, so the whole local library stands in for it.
    if (sourceMode == LibrarySourceMode.LOCAL) return items.take(HOME_WATCHLIST_ROW_LIMIT)
    val watchlistKey = tabs.firstOrNull { it.type == LibraryListTab.Type.WATCHLIST }?.key
        ?: return emptyList()
    return items.filter { watchlistKey in it.listKeys }.take(HOME_WATCHLIST_ROW_LIMIT)
}

internal fun buildHomeWatchlistRow(title: String, items: List<MetaPreview>): CatalogRow = CatalogRow(
    addonId = HOME_WATCHLIST_ADDON_ID,
    addonName = "",
    addonBaseUrl = "",
    catalogId = HOME_WATCHLIST_CATALOG_ID,
    catalogName = title,
    type = ContentType.UNKNOWN,
    rawType = HOME_WATCHLIST_CATALOG_ID,
    items = items,
    hasMore = false
)

/** Null without addons, so the row cannot clear the home screen's "no sources" state. */
internal fun HomeViewModel.homeWatchlistRow(): CatalogRow? {
    if (addonsCache.isEmpty()) return null
    val items = synchronized(catalogStateLock) { homeWatchlistItems }
    if (items.isEmpty()) return null
    return buildHomeWatchlistRow(appContext.withAppLocale().getString(R.string.library_watchlist), items)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun HomeViewModel.observeHomeWatchlistRow() {
    viewModelScope.launch {
        layoutPreferenceDataStore.homeWatchlistRowEnabled
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                // Stay unsubscribed from the library while the row is off.
                if (!enabled) return@flatMapLatest flowOf(emptyList<MetaPreview>())
                combine(
                    combine(
                        libraryRepository.sourceMode,
                        libraryRepository.libraryItems,
                        libraryRepository.listTabs
                    ) { sourceMode, items, tabs ->
                        selectHomeWatchlistEntries(sourceMode, items, tabs)
                    },
                    layoutPreferenceDataStore.customPosterUrlPattern,
                    layoutPreferenceDataStore.customPosterEnabledScreens
                ) { entries, pattern, screens ->
                    entries
                        .withCustomPosterUrls(patternForScreen(pattern, CustomPosterScreen.HOME, screens))
                        .map { it.toMetaPreview() }
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .collectLatest { items ->
                val changed = synchronized(catalogStateLock) {
                    (homeWatchlistItems != items).also { homeWatchlistItems = items }
                }
                if (changed) scheduleUpdateCatalogRows()
            }
    }
}
