package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.PosterCardStyle

private class FocusSnapshot(
    var rowIndex: Int,
    var itemIndex: Int
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ClassicHomeContent(
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    focusState: HomeScreenFocusState,
    trailerPreviewUrls: Map<String, String>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onRequestTrailerPreview: (MetaPreview) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit
) {

    val columnListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )

    LaunchedEffect(focusState.verticalScrollIndex, focusState.verticalScrollOffset) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (columnListState.firstVisibleItemIndex == targetIndex &&
            columnListState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            columnListState.scrollToItem(
                targetIndex,
                targetOffset
            )
        }
    }

    val currentFocusSnapshot = remember {
        FocusSnapshot(
            rowIndex = focusState.focusedRowIndex,
            itemIndex = focusState.focusedItemIndex
        )
    }

    // Store scroll state for each row to persist position during recycling
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    var restoringFocus by remember { mutableStateOf(focusState.hasSavedFocus) }
    val heroFocusRequester = remember { FocusRequester() }
    val shouldRequestInitialFocus = remember(focusState) {
        !focusState.hasSavedFocus &&
            focusState.verticalScrollIndex == 0 &&
            focusState.verticalScrollOffset == 0
    }
    val visibleCatalogRows = remember(uiState.catalogRows) {
        uiState.catalogRows.filter { it.items.isNotEmpty() }
    }
    val visibleCatalogKeys = remember(visibleCatalogRows) {
        visibleCatalogRows.mapTo(mutableSetOf()) { "${it.addonId}_${it.apiType}_${it.catalogId}" }
    }

    LaunchedEffect(visibleCatalogKeys) {
        rowStates.keys.retainAll(visibleCatalogKeys)
        rowFocusRequesters.keys.retainAll(visibleCatalogKeys)
    }

    DisposableEffect(Unit) {
        onDispose {
            onSaveFocusState(
                columnListState.firstVisibleItemIndex,
                columnListState.firstVisibleItemScrollOffset,
                currentFocusSnapshot.rowIndex,
                currentFocusSnapshot.itemIndex,
                focusState.catalogRowScrollStates + rowStates.mapValues { it.value.firstVisibleItemIndex }
            )
        }
    }

    val heroVisible = uiState.heroSectionEnabled && uiState.heroItems.isNotEmpty()

    LaunchedEffect(shouldRequestInitialFocus, heroVisible, uiState.heroItems.size) {
        if (!shouldRequestInitialFocus || !heroVisible) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        try {
            heroFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    LazyColumn(
        state = columnListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = if (heroVisible) 0.dp else 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        if (heroVisible) {
            item(key = "hero_carousel", contentType = "hero") {
                HeroCarousel(
                    items = uiState.heroItems,
                    focusRequester = if (shouldRequestInitialFocus) heroFocusRequester else null,
                    onItemFocus = onItemFocus,
                    onItemClick = { item ->
                        onNavigateToDetail(
                            item.id,
                            item.apiType,
                            ""
                        )
                    }
                )
            }
        }

        if (uiState.continueWatchingItems.isNotEmpty()) {
            item(key = "continue_watching", contentType = "continue_watching") {
                ContinueWatchingSection(
                    items = uiState.continueWatchingItems,
                    onItemClick = { item ->
                        onContinueWatchingClick(item)
                    },
                    onDetailsClick = { item ->
                        onNavigateToDetail(
                            when (item) {
                                is ContinueWatchingItem.InProgress -> item.progress.contentId
                                is ContinueWatchingItem.NextUp -> item.info.contentId
                            },
                            when (item) {
                                is ContinueWatchingItem.InProgress -> item.progress.contentType
                                is ContinueWatchingItem.NextUp -> item.info.contentType
                            },
                            ""
                        )
                    },
                    onRemoveItem = { item ->
                        val contentId = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.contentId
                            is ContinueWatchingItem.NextUp -> item.info.contentId
                        }
                        val season = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.season
                            is ContinueWatchingItem.NextUp -> item.info.season
                        }
                        val episode = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.episode
                            is ContinueWatchingItem.NextUp -> item.info.episode
                        }
                        val isNextUp = item is ContinueWatchingItem.NextUp
                        onRemoveContinueWatching(contentId, season, episode, isNextUp)
                    },
                    focusedItemIndex = when {
                        focusState.hasSavedFocus && focusState.focusedRowIndex == -1 -> focusState.focusedItemIndex
                        shouldRequestInitialFocus && !heroVisible -> 0
                        else -> -1
                    },
                    onItemFocused = { itemIndex ->
                        currentFocusSnapshot.rowIndex = -1
                        currentFocusSnapshot.itemIndex = itemIndex
                    }
                )
            }
        }

        itemsIndexed(
            items = visibleCatalogRows,
            key = { _, item -> "${item.addonId}_${item.apiType}_${item.catalogId}" },
            contentType = { _, _ -> "catalog_row" }
        ) { index, catalogRow ->
            val catalogKey = "${catalogRow.addonId}_${catalogRow.apiType}_${catalogRow.catalogId}"
            val shouldRestoreFocus = restoringFocus && index == focusState.focusedRowIndex
            val shouldInitialFocusFirstCatalogRow =
                shouldRequestInitialFocus &&
                    !heroVisible &&
                    uiState.continueWatchingItems.isEmpty() &&
                    index == 0
            val focusedItemIndex = when {
                shouldRestoreFocus -> focusState.focusedItemIndex
                shouldInitialFocusFirstCatalogRow -> 0
                else -> -1
            }

            val listState = rowStates.getOrPut(catalogKey) {
                LazyListState(
                    firstVisibleItemIndex = focusState.catalogRowScrollStates[catalogKey] ?: 0
                )
            }
            val rowFocusRequester = rowFocusRequesters.getOrPut(catalogKey) { FocusRequester() }

            CatalogRowSection(
                catalogRow = catalogRow,
                posterCardStyle = posterCardStyle,
                showPosterLabels = uiState.posterLabelsEnabled,
                showAddonName = uiState.catalogAddonNameEnabled,
                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                focusedPosterBackdropExpandEnabled = uiState.focusedPosterBackdropExpandEnabled,
                focusedPosterBackdropExpandDelaySeconds = uiState.focusedPosterBackdropExpandDelaySeconds,
                focusedPosterBackdropTrailerEnabled = uiState.focusedPosterBackdropTrailerEnabled,
                focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                trailerPreviewUrls = trailerPreviewUrls,
                onRequestTrailerPreview = onRequestTrailerPreview,
                onItemFocus = onItemFocus,
                isItemWatched = isCatalogItemWatched,
                onItemLongPress = onCatalogItemLongPress,
                onItemClick = { id, type, addonBaseUrl ->
                    onNavigateToDetail(id, type, addonBaseUrl)
                },
                onSeeAll = {
                    onNavigateToCatalogSeeAll(
                        catalogRow.catalogId,
                        catalogRow.addonId,
                        catalogRow.apiType
                    )
                },
                rowFocusRequester = rowFocusRequester,
                listState = listState,
                // We don't need initialScrollIndex anymore as listState handles it
                focusedItemIndex = focusedItemIndex,
                onItemFocused = { itemIndex ->
                    if (restoringFocus) restoringFocus = false
                    currentFocusSnapshot.rowIndex = index
                    currentFocusSnapshot.itemIndex = itemIndex
                    // Update the state as well, though getOrPut handles creation
                    // rowStates[catalogKey] already holds the live state object
                }
            )
        }
    }
}
