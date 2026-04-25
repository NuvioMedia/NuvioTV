package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.LocalContentFocusRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import com.nuvio.tv.ui.util.dpadVerticalFastScroll
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Collection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.CollectionRowSection
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.LocalVerticalScrollSuppressImages
import com.nuvio.tv.ui.components.PosterCardStyle

private class FocusSnapshot(
    var rowIndex: Int,
    var itemIndex: Int,
    var rowKey: String? = null
)

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ClassicHomeContent(
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    focusState: HomeScreenFocusState,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = {},
    showContinueWatchingManualPlayOption: Boolean = false,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onRequestTrailerPreview: (MetaPreview) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {},
    catalogSeeAllLabel: String? = null,
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit,
    scrollToTopTrigger: Int = 0,
    onRequestLazyCatalogLoad: (String) -> Unit = {}
) {

    // Nested prefetch: when LazyColumn prefetches a row ahead of scrolling,
    // pre-compose up to 2 ContentCards in its nested LazyRow across multiple frames.
    // This spreads the composition work and prevents frame spikes when a new row scrolls in.
    val nestedPrefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }

    val columnListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset,
        prefetchStrategy = nestedPrefetchStrategy
    )
    val isVerticalScrollingState = remember(columnListState) {
        androidx.compose.runtime.derivedStateOf { columnListState.isScrollInProgress }
    }

    // Scroll to top when triggered from sidebar Home button.
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            columnListState.scrollToItem(0, 0)
        }
    }

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
    val rowEntryFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val rowFocusedItemIndex = remember { mutableMapOf<String, Int>() }

    var restoringFocus by remember { mutableStateOf(focusState.hasSavedFocus) }
    val heroFocusRequester = remember { FocusRequester() }
    val shouldRequestInitialFocus = remember(focusState) {
        !focusState.hasSavedFocus &&
            focusState.verticalScrollIndex == 0 &&
            focusState.verticalScrollOffset == 0
    }
    val visibleHomeRows = remember(uiState.homeRows, uiState.catalogRows) {
        if (uiState.homeRows.isNotEmpty()) {
            uiState.homeRows
        } else {
            uiState.catalogRows.filter { it.items.isNotEmpty() }.map { HomeRow.Catalog(it) }
        }
    }
    val visibleRowKeys = remember(visibleHomeRows) {
        visibleHomeRows.mapTo(mutableSetOf()) { row ->
            when (row) {
                is HomeRow.Catalog -> "${row.row.addonId}_${row.row.apiType}_${row.row.catalogId}"
                is HomeRow.CollectionRow -> "collection_${row.collection.id}"
                is HomeRow.PlaceholderCatalog -> row.catalogKey
            }
        }
    }

    LaunchedEffect(visibleRowKeys) {
        rowStates.keys.retainAll(visibleRowKeys)
        rowFocusRequesters.keys.retainAll(visibleRowKeys)
        rowEntryFocusRequesters.keys.retainAll(visibleRowKeys)
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

    val heroExpected = uiState.heroSectionEnabled
    val heroResolved = !heroExpected || heroVisible
    var heroDeferTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(shouldRequestInitialFocus, heroExpected) {
        if (!shouldRequestInitialFocus || !heroExpected) return@LaunchedEffect
        delay(2000)
        heroDeferTimedOut = true
    }
    val deferContentFocus = shouldRequestInitialFocus && !heroResolved && !heroDeferTimedOut

    LaunchedEffect(shouldRequestInitialFocus, heroVisible) {
        if (!shouldRequestInitialFocus || !heroVisible) return@LaunchedEffect
        columnListState.scrollToItem(0)
        repeat(8) {
            withFrameNanos { }
            val focused = runCatching { heroFocusRequester.requestFocus(); true }
                .getOrDefault(false)
            if (focused) return@LaunchedEffect
        }
    }

    val contentFocusRequester = LocalContentFocusRequester.current

    // Surfaced from [Modifier.dpadVerticalFastScroll] so cards inside the
    // LazyColumn can hide their focus chrome while the list is being dragged
    // by a held DPAD_UP / DPAD_DOWN (see [LocalFastScrollActive] below).
    var isFastScrolling by remember { mutableStateOf(false) }

    // Stabilize map references to avoid recomposing every row when a single trailer URL changes.
    val stableTrailerPreviewUrls = remember { androidx.compose.runtime.mutableStateOf(trailerPreviewUrls) }
        .apply { value = trailerPreviewUrls }
    val stableTrailerPreviewAudioUrls = remember { androidx.compose.runtime.mutableStateOf(trailerPreviewAudioUrls) }
        .apply { value = trailerPreviewAudioUrls }

    if (deferContentFocus) {
        // Show spinner while waiting for hero data to arrive — prevents
        // content rows from claiming focus before the hero is ready.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator()
        }
        return
    }

    // Lazy catalog loading: trigger load after scroll settles
    val latestOnRequestLazyCatalogLoad = rememberUpdatedState(onRequestLazyCatalogLoad)
    val latestVisibleHomeRows = rememberUpdatedState(visibleHomeRows)
    LaunchedEffect(columnListState) {
        val prefetchAhead = 2
        snapshotFlow {
            val scrolling = columnListState.isScrollInProgress
            val info = columnListState.layoutInfo
            val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: -1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(scrolling, firstVisible, lastVisible)
        }.collect { (scrolling, firstVisible, lastVisible) ->
            if (scrolling || lastVisible < 0) return@collect
            delay(150)
            if (columnListState.isScrollInProgress) return@collect
            val rows = latestVisibleHomeRows.value
            // Offset for hero + CW sections that precede homeRows in LazyColumn
            val heroOffset = if (uiState.heroSectionEnabled && uiState.heroItems.isNotEmpty()) 1 else 0
            val cwOffset = if (uiState.continueWatchingItems.isNotEmpty()) 1 else 0
            val rowsOffset = heroOffset + cwOffset
            for (idx in firstVisible.coerceAtLeast(0)..(lastVisible + prefetchAhead)) {
                val rowIdx = idx - rowsOffset
                val row = rows.getOrNull(rowIdx) ?: continue
                if (row is HomeRow.Catalog && row.row.isLoading &&
                    row.row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
                ) {
                    val key = "${row.row.addonId}_${row.row.apiType}_${row.row.catalogId}"
                    latestOnRequestLazyCatalogLoad.value(key)
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalVerticalScrollSuppressImages provides (uiState.memoryOnlyVerticalScroll && isVerticalScrollingState.value),
        LocalFastScrollActive provides isFastScrolling
    ) {
    LazyColumn(
        state = columnListState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(contentFocusRequester)
            .focusRestorer()
            .dpadVerticalFastScroll(
                scrollableState = columnListState,
                onFastScrollingChanged = { isFastScrolling = it },
                resolveVerticalLanding = { sign ->
                    // Pick the item currently occupying the leading edge of
                    // the viewport, then map its LazyColumn key back to the
                    // matching FocusRequester. Hero has its own requester;
                    // row items carry requesters in [rowEntryFocusRequesters].
                    // Continue Watching is a full-width section with no direct
                    // requester, so if it ends up at the edge we fall through
                    // to the nearest requester-bearing neighbour instead of
                    // dropping focus.
                    val layoutInfo = columnListState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    val lastIdx = layoutInfo.totalItemsCount - 1
                    val viewportEnd = layoutInfo.viewportEndOffset
                    val lastItemAtBottom = lastIdx >= 0 &&
                        visibleItems.lastOrNull { it.index == lastIdx }?.let {
                            it.offset + it.size <= viewportEnd
                        } == true
                    val upwardTopItem = if (sign < 0) {
                        visibleItems.firstOrNull()?.takeIf {
                            it.offset > -it.size / 2
                        }
                    } else null
                    val target = when {
                        lastItemAtBottom -> visibleItems.lastOrNull { it.index == lastIdx }
                        upwardTopItem != null -> upwardTopItem
                        else ->
                            visibleItems.firstOrNull { it.offset >= 0 }
                                ?: visibleItems.firstOrNull()
                    }
                    fun requesterForKey(k: String?): FocusRequester? = when {
                        k == null -> null
                        k == "hero_carousel" -> heroFocusRequester
                        rowEntryFocusRequesters.containsKey(k) -> rowEntryFocusRequesters[k]
                        else -> null
                    }
                    if (target == null) null
                    else requesterForKey(target.key as? String)
                        ?: visibleItems.firstNotNullOfOrNull { requesterForKey(it.key as? String) }
                },
            ),
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
                val firstRowKey = visibleHomeRows.firstOrNull()?.let { row ->
                    when (row) {
                        is HomeRow.Catalog -> "${row.row.addonId}_${row.row.apiType}_${row.row.catalogId}"
                        is HomeRow.CollectionRow -> "collection_${row.collection.id}"
                        is HomeRow.PlaceholderCatalog -> row.catalogKey
                    }
                }
                val cwDownRequester = firstRowKey?.let { rowEntryFocusRequesters.getOrPut(it) { FocusRequester() } }
                ContinueWatchingSection(
                    items = uiState.continueWatchingItems,
                    onItemClick = { item ->
                        onContinueWatchingClick(item)
                    },
                    onStartFromBeginning = onContinueWatchingStartFromBeginning,
                    showManualPlayOption = showContinueWatchingManualPlayOption,
                    onPlayManually = onContinueWatchingPlayManually,
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
                            is ContinueWatchingItem.NextUp -> item.info.seedSeason
                        }
                        val episode = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.episode
                            is ContinueWatchingItem.NextUp -> item.info.seedEpisode
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
                    },
                    blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                    downFocusRequester = cwDownRequester
                )
            }
        }

        itemsIndexed(
            items = visibleHomeRows,
            key = { _, item ->
                when (item) {
                    is HomeRow.Catalog -> {
                        val r = item.row
                        "${r.addonId}_${r.apiType}_${r.catalogId}"
                    }
                    is HomeRow.CollectionRow -> "collection_${item.collection.id}"
                    is HomeRow.PlaceholderCatalog -> item.catalogKey
                }
            },
            contentType = { _, item ->
                when (item) {
                    is HomeRow.Catalog -> "catalog_row"
                    is HomeRow.CollectionRow -> "collection_row"
                    is HomeRow.PlaceholderCatalog -> "catalog_row"
                }
            }
        ) { index, homeRow ->
            when (homeRow) {
                is HomeRow.Catalog -> {
                    val catalogRow = homeRow.row
                    val catalogKey = "${catalogRow.addonId}_${catalogRow.apiType}_${catalogRow.catalogId}"
                    // Match by saved row key first, fall back to index
                    val shouldRestoreFocus = restoringFocus &&
                        (currentFocusSnapshot.rowKey == catalogKey || index == focusState.focusedRowIndex)
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
                        trailerPreviewUrls = stableTrailerPreviewUrls.value,
                        trailerPreviewAudioUrls = stableTrailerPreviewAudioUrls.value,
                        onRequestTrailerPreview = onRequestTrailerPreview,
                        onItemFocus = onItemFocus,
                        isItemWatched = isCatalogItemWatched,
                        onItemLongPress = onCatalogItemLongPress,
                        seeAllLabel = catalogSeeAllLabel,
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
                        entryFocusRequester = rowEntryFocusRequesters.getOrPut(catalogKey) { FocusRequester() },
                        listState = listState,
                        enableRowFocusRestorer = true,
                        focusedItemIndex = focusedItemIndex,
                        restorerFocusedIndex = rowFocusedItemIndex[catalogKey] ?: -1,
                        onItemFocused = { itemIndex ->
                            if (restoringFocus) restoringFocus = false
                            currentFocusSnapshot.rowIndex = index
                            currentFocusSnapshot.itemIndex = itemIndex
                            currentFocusSnapshot.rowKey = catalogKey
                            rowFocusedItemIndex[catalogKey] = itemIndex
                        }
                    )
                }

                is HomeRow.CollectionRow -> {
                    val collectionKey = "collection_${homeRow.collection.id}"
                    // Match by saved row key first, fall back to index
                    val shouldRestoreCollectionFocus = restoringFocus &&
                        (currentFocusSnapshot.rowKey == collectionKey || index == focusState.focusedRowIndex)
                    val collectionFocusedItemIndex = if (shouldRestoreCollectionFocus) {
                        focusState.focusedItemIndex
                    } else {
                        -1
                    }
                    val listState = rowStates.getOrPut(collectionKey) {
                        LazyListState(
                            firstVisibleItemIndex = focusState.catalogRowScrollStates[collectionKey] ?: 0
                        )
                    }

                    CollectionRowSection(
                        collection = homeRow.collection,
                        onFolderClick = onNavigateToFolderDetail,
                        listState = listState,
                        focusedItemIndex = collectionFocusedItemIndex,
                        entryFocusRequester = rowEntryFocusRequesters.getOrPut(collectionKey) { FocusRequester() },
                        onItemFocused = { itemIndex ->
                            if (restoringFocus) restoringFocus = false
                            currentFocusSnapshot.rowIndex = index
                            currentFocusSnapshot.itemIndex = itemIndex
                            currentFocusSnapshot.rowKey = collectionKey
                            rowFocusedItemIndex[collectionKey] = itemIndex
                        }
                    )
                }

                is HomeRow.PlaceholderCatalog -> { }
            }
        }
    }
    } // CompositionLocalProvider
}
