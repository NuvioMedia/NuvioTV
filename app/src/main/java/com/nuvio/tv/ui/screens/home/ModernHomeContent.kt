@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.FlowPreview::class
)

package com.nuvio.tv.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.metrics.performance.PerformanceMetricsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.request.CachePolicy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.util.asStable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

@Composable
fun ModernHomeContent(
    uiState: HomeUiState,
    focusState: HomeScreenFocusState,
    enrichingItemId: String? = null,
    lastEnrichedPreview: MetaPreview? = null,
    enrichedPreviews: Map<String, MetaPreview> = emptyMap(),
    trailerPreviewUrls: Map<String, String> = emptyMap(),
    trailerPreviewAudioUrls: Map<String, String> = emptyMap(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = {},
    showContinueWatchingManualPlayOption: Boolean = false,
    onRequestTrailerPreview: (String, String, String?, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onItemFocus: (MetaPreview) -> Unit = {},
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit,
    scrollToTopTrigger: Int = 0,
    onRequestLazyCatalogLoad: (String) -> Unit = {},
    onRowItemFocusedCallback: (String, Int, Boolean) -> Unit = { _, _, _ -> }
) {
    val onRowItemFocusedPassedDown = rememberUpdatedState(onRowItemFocusedCallback)
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val sidebarExpanded = LocalSidebarExpanded.current
    val isSidebarExpanded = remember(sidebarExpanded) { derivedStateOf { sidebarExpanded } }
    val lifecycleOwner = LocalLifecycleOwner.current
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val fullScreenBackdrop = uiState.modernHeroFullScreenBackdropEnabled
    val trailerPlaybackTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget
    val effectiveAutoplayEnabled =
        uiState.focusedPosterBackdropTrailerEnabled &&
            (useLandscapePosters || uiState.focusedPosterBackdropExpandEnabled)
    val landscapeExpandedCardMode =
        useLandscapePosters &&
            effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
    val effectiveExpandEnabled =
        (uiState.focusedPosterBackdropExpandEnabled && !useLandscapePosters) ||
            landscapeExpandedCardMode
    val shouldActivateFocusedPosterFlow =
        effectiveExpandEnabled ||
            (effectiveAutoplayEnabled &&
                trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
    val presentation = uiState.modernHomePresentation
    val carouselRows = presentation.rows

    val hasCollections = remember(uiState.homeRows) {
        uiState.homeRows.any { it is HomeRow.CollectionRow }
    }
    val hasCatalogs = uiState.catalogRows.isNotEmpty()
    if (hasCollections && !hasCatalogs && uiState.installedAddonsCount > 0 && uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (carouselRows.list.isEmpty()) {
        if (uiState.heroSectionEnabled && uiState.heroItems.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                com.nuvio.tv.ui.components.HeroCarousel(
                    items = uiState.heroItems,
                    onItemClick = { item ->
                        onNavigateToDetail(item.id, item.apiType, "")
                    },
                    onItemFocus = onItemFocus
                )
            }
        }
        return
    }
    val carouselLookups = presentation.lookups
    val rowIndexByKey = carouselLookups.rowIndexByKey
    val rowByKey = carouselLookups.rowByKey
    val rowKeyByGlobalRowIndex = carouselLookups.rowKeyByGlobalRowIndex
    val activeRowKeys = carouselLookups.activeRowKeys
    val activeCatalogItemIds = carouselLookups.activeCatalogItemIds
    val verticalRowListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val isVerticalRowsScrolling by remember(verticalRowListState) {
        derivedStateOf { verticalRowListState.isScrollInProgress }
    }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            verticalRowListState.scrollToItem(0, 0)
        }
    }

    val currentView = LocalView.current
    val metricsHolder = PerformanceMetricsState.getHolderForHierarchy(currentView)
    LaunchedEffect(isVerticalRowsScrolling) {
        metricsHolder.state?.putState("HomeScrolling", isVerticalRowsScrolling.toString())
    }
    LaunchedEffect(enrichingItemId) {
        metricsHolder.state?.putState("HeroEnriching", (enrichingItemId != null).toString())
    }

    val uiCaches = remember { ModernHomeUiCaches() }
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val rowListStates = uiCaches.rowListStates
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals

    if (focusedItemByRow.isEmpty() && focusState.hasSavedFocus) {
        val savedRowKey = when {
            focusState.focusedRowIndex == -1 -> MODERN_CONTINUE_WATCHING_ROW_KEY
            focusState.focusedRowIndex >= 0 -> rowKeyByGlobalRowIndex[focusState.focusedRowIndex]
            else -> null
        }
        if (savedRowKey != null) {
            focusedItemByRow[savedRowKey] = focusState.focusedItemIndex.coerceAtLeast(0)
        }
    }
    // Holder for hot-path focus tracking — lambdas read through reference, no stale closure
    val focusHolder = remember {
        object {
            var activeRowKey: String? = null
            var activeItemIndex: Int = 0
        }
    }
    val activeRowKey = remember { mutableStateOf<String?>(null) }
    val activeItemIndex = remember { mutableIntStateOf(0) }
    var initialAutoSelectedKey by remember { mutableStateOf<String?>(null) }
    val pendingRowFocusKey = remember { mutableStateOf<String?>(null) }
    val pendingRowFocusIndex = remember { mutableStateOf<Int?>(null) }
    val pendingRowFocusNonce = remember { mutableIntStateOf(0) }
    val heroItem = remember {
        val initialHero = carouselRows.list.firstOrNull()?.items?.list?.firstOrNull()?.heroPreview
        mutableStateOf<HeroPreview?>(initialHero)
    }
    val restoredFromSavedState = remember { mutableStateOf(false) }
    val optionsItem = remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val lastFocusedContinueWatchingIndex = remember { mutableIntStateOf(-1) }
    val lastHeroNavigationAtMs = remember { mutableLongStateOf(0L) }
    val heroFocusSettleDelayMs = remember { mutableLongStateOf(MODERN_HERO_FOCUS_DEBOUNCE_MS) }
    val isFastScrolling = remember { mutableStateOf(false) }
    val focusedCatalogSelection = remember { mutableStateOf<FocusedCatalogSelection?>(null) }
    var lastRequestedTrailerFocusKey by remember { mutableStateOf<String?>(null) }
    val expandedCatalogFocusKey = remember { mutableStateOf<String?>(null) }
    val focusedHeroMediaNonce = remember { mutableIntStateOf(0) }
    var endedCollectionHeroVideoPlaybackKey by remember { mutableStateOf<String?>(null) }
    val expansionInteractionNonce = remember { mutableIntStateOf(0) }

    LaunchedEffect(
        focusedCatalogSelection.value?.focusKey,
        expansionInteractionNonce.intValue,
        shouldActivateFocusedPosterFlow,
        trailerPlaybackTarget,
        uiState.focusedPosterBackdropExpandDelaySeconds,
        isVerticalRowsScrolling
    ) {
        expandedCatalogFocusKey.value = null
        if (!shouldActivateFocusedPosterFlow) return@LaunchedEffect
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val selection = focusedCatalogSelection.value ?: return@LaunchedEffect
        if (selection.payload !is ModernPayload.Catalog) return@LaunchedEffect
        delay(uiState.focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(0) * 1000L)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (shouldActivateFocusedPosterFlow &&
            !isVerticalRowsScrolling &&
            focusedCatalogSelection.value?.focusKey == selection.focusKey
        ) {
            expandedCatalogFocusKey.value = selection.focusKey
        }
    }

    LaunchedEffect(
        focusedCatalogSelection.value?.focusKey,
        effectiveAutoplayEnabled,
        isVerticalRowsScrolling
    ) {
        if (!effectiveAutoplayEnabled) {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val selection = focusedCatalogSelection.value ?: run {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        val payload = selection.payload as? ModernPayload.Catalog ?: run {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (selection.focusKey == lastRequestedTrailerFocusKey) return@LaunchedEffect
        delay(150)
        if (focusedCatalogSelection.value?.focusKey != selection.focusKey) return@LaunchedEffect
        onRequestTrailerPreview(
            payload.itemId,
            payload.trailerTitle,
            payload.trailerReleaseInfo,
            payload.trailerApiType
        )
        lastRequestedTrailerFocusKey = selection.focusKey
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus, focusState.focusedRowIndex, focusState.focusedItemIndex) {
        focusedItemByRow.keys.retainAll(activeRowKeys)
        itemFocusRequesters.keys.retainAll(activeRowKeys)
        rowListStates.keys.retainAll(activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(activeRowKeys)
        carouselRows.list.forEach { row ->
            val rowRequesters = itemFocusRequesters[row.key] ?: return@forEach
            val maxIndex = row.items.list.size
            rowRequesters.keys.removeAll { key ->
                val idx = key.removePrefix("${row.key}_").toIntOrNull()
                idx != null && idx >= maxIndex
            }
        }
        val staleSelection = focusedCatalogSelection.value?.let { selection ->
            when (val payload = selection.payload) {
                is ModernPayload.Catalog -> !payload.itemId.startsWith("__placeholder_") && payload.itemId !in activeCatalogItemIds.set
                is ModernPayload.CollectionFolder -> carouselRows.list.none { row ->
                    row.items.list.any { item ->
                        (item.payload as? ModernPayload.CollectionFolder)?.focusKey == selection.focusKey
                    }
                }
                is ModernPayload.ContinueWatching -> true
            }
        } ?: false
        if (staleSelection) {
            focusedCatalogSelection.value = null
            expandedCatalogFocusKey.value = null
        }
        val currentSelection = focusedCatalogSelection.value
        val currentCatalogPayload = currentSelection?.payload as? ModernPayload.Catalog
        if (currentSelection != null && currentCatalogPayload != null && currentCatalogPayload.itemId.startsWith("__placeholder_")) {
            val activeKey = focusHolder.activeRowKey
            val activeIdx = focusHolder.activeItemIndex
            val activeRow = activeKey?.let { rowByKey.map[it] }
            val realItem = activeRow?.items?.list?.getOrNull(activeIdx)
            val realPayload = realItem?.payload as? ModernPayload.Catalog
            if (realPayload != null && !realPayload.itemId.startsWith("__placeholder_")) {
                focusedCatalogSelection.value = FocusedCatalogSelection(
                    focusKey = realPayload.focusKey,
                    payload = realPayload
                )
                lastRequestedTrailerFocusKey = null
                expandedCatalogFocusKey.value = null
            }
        }

        carouselRows.list.forEach { row ->
            if (row.items.list.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }
        }

        if (!restoredFromSavedState.value && focusState.hasSavedFocus) {
            val savedRowKey = when {
                focusState.focusedRowIndex == -1 && uiState.continueWatchingItems.isNotEmpty() -> "continue_watching"
                focusState.focusedRowIndex >= 0 -> rowKeyByGlobalRowIndex.map[focusState.focusedRowIndex]
                else -> null
            }

            val resolvedRow = savedRowKey?.let { rowByKey.map[it] } ?: carouselRows.list.first()
            val resolvedIndex = focusState.focusedItemIndex
                .coerceAtLeast(0)
                .coerceAtMost((resolvedRow.items.list.size - 1).coerceAtLeast(0))

            focusHolder.activeRowKey = resolvedRow.key
            focusHolder.activeItemIndex = resolvedIndex
            activeRowKey.value = resolvedRow.key
            activeItemIndex.intValue = resolvedIndex
            focusedItemByRow[resolvedRow.key] = resolvedIndex
            heroItem.value = resolvedRow.items.getOrNull(resolvedIndex)?.heroPreview
                ?: resolvedRow.items.firstOrNull()?.heroPreview
            pendingRowFocusKey.value = resolvedRow.key
            pendingRowFocusIndex.value = resolvedIndex
            pendingRowFocusNonce.intValue++
            restoredFromSavedState.value = true
            return@LaunchedEffect
        }

        val hadActiveRow = focusHolder.activeRowKey != null
        val existingActive = focusHolder.activeRowKey?.let { rowByKey[it] }
        val firstRow = carouselRows.first()
        val userStillOnAutoSelected = initialAutoSelectedKey != null &&
            focusHolder.activeRowKey == initialAutoSelectedKey
        val autoSelectedStale = hadActiveRow && existingActive != null &&
            existingActive.key != firstRow.key &&
            rowIndexByKey.getOrDefault(existingActive.key, 0) > 0 &&
            !restoredFromSavedState.value &&
            !focusState.hasSavedFocus &&
            userStillOnAutoSelected
        val resolvedActive = when {
            autoSelectedStale -> firstRow
            existingActive != null -> existingActive
            else -> firstRow
        }
        val resolvedIndex = focusedItemByRow[resolvedActive.key]
            ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0))
            ?: 0
        focusHolder.activeRowKey = resolvedActive.key
        focusHolder.activeItemIndex = resolvedIndex
        activeRowKey.value = resolvedActive.key
        activeItemIndex.intValue = resolvedIndex
        focusedItemByRow[resolvedActive.key] = resolvedIndex
        heroItem.value = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
            ?: resolvedActive.items.firstOrNull()?.heroPreview
        if (!focusState.hasSavedFocus && (!hadActiveRow || existingActive == null || autoSelectedStale)) {
            initialAutoSelectedKey = resolvedActive.key
            pendingRowFocusKey.value = resolvedActive.key
            pendingRowFocusIndex.value = resolvedIndex
            pendingRowFocusNonce.intValue++
        }
    }

    LaunchedEffect(focusState.verticalScrollIndex, focusState.verticalScrollOffset) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (verticalRowListState.firstVisibleItemIndex == targetIndex &&
            verticalRowListState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            verticalRowListState.scrollToItem(targetIndex, targetOffset)
        }
    }

    val activeRow by remember(carouselRows, rowByKey, activeRowKey.value) {
        derivedStateOf {
            val activeKey = activeRowKey.value
            if (activeKey == null) null
            else rowByKey[activeKey] ?: carouselRows.firstOrNull()
        }
    }
    val clampedActiveItemIndex by remember(activeRow, activeItemIndex.intValue) {
        derivedStateOf {
            activeRow?.let { row ->
                activeItemIndex.intValue.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            } ?: 0
        }
    }

    LaunchedEffect(activeRow?.key, activeRow?.items?.size) {
        val row = activeRow ?: return@LaunchedEffect
        val clampedIndex = focusHolder.activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        if (focusHolder.activeItemIndex != clampedIndex) {
            focusHolder.activeItemIndex = clampedIndex
            activeItemIndex.intValue = clampedIndex
        }
        focusedItemByRow[row.key] = clampedIndex
    }

    val activeHeroItemKey by remember(activeRow, clampedActiveItemIndex) {
        derivedStateOf {
            val row = activeRow ?: return@derivedStateOf null
            row.items.getOrNull(clampedActiveItemIndex)?.key ?: row.items.firstOrNull()?.key
        }
    }
    val latestHeroRow by rememberUpdatedState(activeRow)
    val latestHeroIndex by rememberUpdatedState(clampedActiveItemIndex)
    LaunchedEffect(activeHeroItemKey, isVerticalRowsScrolling) {
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val targetHeroKey = activeHeroItemKey ?: return@LaunchedEffect
        val settleDelayMs = heroFocusSettleDelayMs.longValue
        delay(settleDelayMs)
        if (isVerticalRowsScrolling) return@LaunchedEffect
        if (System.currentTimeMillis() - lastHeroNavigationAtMs.longValue < settleDelayMs) return@LaunchedEffect
        val row = latestHeroRow ?: return@LaunchedEffect
        val latestKey = row.items.getOrNull(latestHeroIndex)?.key ?: row.items.firstOrNull()?.key
        if (latestKey != targetHeroKey) return@LaunchedEffect
        val latestHero =
            row.items.getOrNull(latestHeroIndex)?.heroPreview ?: row.items.firstOrNull()?.heroPreview
        if (latestHero != null && heroItem.value != latestHero) {
            heroItem.value = latestHero
        }
    }

    val latestActiveRow by rememberUpdatedState(activeRow)
    val latestActiveItemIndex by rememberUpdatedState(clampedActiveItemIndex)
    val latestCarouselRows by rememberUpdatedState(carouselRows)
    val latestVerticalRowListState by rememberUpdatedState(verticalRowListState)
    DisposableEffect(Unit) {
        onDispose {
            val row = latestActiveRow
            val focusedRowIndex = row?.globalRowIndex ?: 0
            val catalogRowScrollStates = latestCarouselRows
                .associate { rowState ->
                    val scrollIndex = rowListStates[rowState.key]?.firstVisibleItemIndex
                        ?: (focusedItemByRow[rowState.key] ?: 0)
                    rowState.key to scrollIndex
                }

            onSaveFocusState(
                latestVerticalRowListState.firstVisibleItemIndex,
                latestVerticalRowListState.firstVisibleItemScrollOffset,
                focusedRowIndex,
                latestActiveItemIndex,
                catalogRowScrollStates
            )
        }
    }

    val portraitBaseWidth = uiState.posterCardWidthDp.dp
    val portraitBaseHeight = uiState.posterCardHeightDp.dp
    val portraitModernPosterScale = 1.08f
    val landscapeModernPosterScale = 1.34f
    val portraitCatalogCardWidth = portraitBaseWidth * 0.84f * portraitModernPosterScale
    val portraitCatalogCardHeight = portraitBaseHeight * 0.84f * portraitModernPosterScale
    val landscapeCatalogCardWidth = portraitBaseWidth * 1.24f * landscapeModernPosterScale
    val landscapeCatalogCardHeight = landscapeCatalogCardWidth / 1.77f
    val continueWatchingScale = 1.34f
    val continueWatchingCardWidth = portraitBaseWidth * 1.24f * continueWatchingScale
    val continueWatchingCardHeight = continueWatchingCardWidth / 1.77f

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val posterCardCornerRadius = remember(uiState.posterCardCornerRadiusDp) { uiState.posterCardCornerRadiusDp.dp }
        val rowHorizontalPadding = 52.dp

        val activeCarouselItemState = remember(carouselRows, rowByKey, activeRowKey, activeItemIndex) {
            derivedStateOf {
                val activeKey = activeRowKey.value
                val row = if (activeKey == null) null
                else rowByKey[activeKey] ?: carouselRows.firstOrNull()
                
                val index = activeItemIndex.intValue
                val clampedIdx = row?.let {
                    index.coerceIn(0, (it.items.size - 1).coerceAtLeast(0))
                } ?: 0
                row?.items?.getOrNull(clampedIdx)
            }
        }

        val resolvedHeroState = remember(activeCarouselItemState, enrichedPreviews, enrichingItemId, heroItem) {
            derivedStateOf {
                val activeCarouselItem = activeCarouselItemState.value
                val activeItemId = activeCarouselItem?.metaPreview?.id
                val enrichmentActive = enrichingItemId != null && enrichingItemId == activeItemId
                
                val enrichedItem = activeItemId?.let { enrichedPreviews[it] }
                val enrichedHero = if (enrichedItem != null) {
                    HeroPreview(
                        title = enrichedItem.name,
                        logo = enrichedItem.logo,
                        description = enrichedItem.description,
                        contentTypeText = activeCarouselItem?.heroPreview?.contentTypeText,
                        isSeries = isSeriesType(enrichedItem.apiType),
                        yearText = activeCarouselItem?.heroPreview?.yearText,
                        runtimeText = activeCarouselItem?.heroPreview?.runtimeText,
                        imdbText = enrichedItem.imdbRating?.let { String.format(java.util.Locale.US, "%.1f", it) },
                        ageRatingText = enrichedItem.ageRating,
                        statusText = enrichedItem.status,
                        countryText = enrichedItem.country,
                        languageText = enrichedItem.language?.uppercase(),
                        genres = enrichedItem.genres.take(3),
                        poster = enrichedItem.poster,
                        backdrop = enrichedItem.backdropUrl,
                        imageUrl = activeCarouselItem?.heroPreview?.imageUrl,
                        frozenBackdropUrl = activeCarouselItem?.heroPreview?.frozenBackdropUrl,
                        frozenLogoUrl = activeCarouselItem?.heroPreview?.frozenLogoUrl
                    )
                } else null

                val resolvedHero = when {
                    enrichmentActive -> heroItem.value
                    enrichedHero != null -> enrichedHero
                    else -> activeCarouselItem?.heroPreview ?: heroItem.value
                }
                
                val activeRowKeyVal = activeRowKey.value
                val activeRow = activeRowKeyVal?.let { rowByKey[it] }
                val activeRowFallbackBackdrop = activeRow?.items?.firstNotNullOfOrNull { item ->
                    item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
                }
                
                val heroBackdrop = firstNonBlank(
                    resolvedHero?.backdrop,
                    resolvedHero?.imageUrl,
                    resolvedHero?.poster,
                    if (heroItem.value == null) activeRowFallbackBackdrop else null
                )
                
                Triple(heroBackdrop, resolvedHero, enrichmentActive)
            }
        }

        val expandedFocusedSelectionState = remember(focusedCatalogSelection, expandedCatalogFocusKey) {
            derivedStateOf {
                focusedCatalogSelection.value
                    ?.takeIf { it.focusKey == expandedCatalogFocusKey.value }
                    ?.takeIf { it.payload is ModernPayload.Catalog }
            }
        }

        val heroTrailerUrlsState = remember(expandedFocusedSelectionState, trailerPreviewUrls, trailerPreviewAudioUrls) {
            derivedStateOf {
                val expandedFocusedSelection = expandedFocusedSelectionState.value
                val itemId = (expandedFocusedSelection?.payload as? ModernPayload.Catalog)?.itemId
                val url = itemId?.let { trailerPreviewUrls[it] }
                val audioUrl = itemId?.let { trailerPreviewAudioUrls[it] }
                url to audioUrl
            }
        }
        val expandedCatalogTrailerUrl = heroTrailerUrlsState.value.first
        val expandedCatalogTrailerAudioUrl = heroTrailerUrlsState.value.second
        val collectionHeroVideoUrl = (focusedCatalogSelection.value?.payload as? ModernPayload.CollectionFolder)?.heroVideoUrl
        val collectionHeroVideoPlaybackKey = remember(
            focusedCatalogSelection.value?.focusKey,
            collectionHeroVideoUrl,
            focusedHeroMediaNonce.intValue
        ) {
            val focusKey = focusedCatalogSelection.value?.focusKey
            val url = collectionHeroVideoUrl?.takeIf { it.isNotBlank() }
            if (focusKey != null && url != null) "$focusKey::${focusedHeroMediaNonce.intValue}::$url" else null
        }
        val shouldPlayCatalogHeroTrailerState = remember(
            effectiveAutoplayEnabled,
            trailerPlaybackTarget,
            heroTrailerUrlsState,
            isVerticalRowsScrolling,
            isSidebarExpanded
        ) {
            derivedStateOf {
                effectiveAutoplayEnabled &&
                    !isSidebarExpanded.value &&
                    !isVerticalRowsScrolling &&
                    trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
                    !heroTrailerUrlsState.value.first.isNullOrBlank()
            }
        }
        val shouldPlayCollectionHeroVideoState = remember(
            collectionHeroVideoUrl,
            collectionHeroVideoPlaybackKey,
            endedCollectionHeroVideoPlaybackKey,
            isVerticalRowsScrolling,
            isSidebarExpanded
        ) {
            derivedStateOf {
                !isSidebarExpanded.value &&
                    !isVerticalRowsScrolling &&
                    !collectionHeroVideoUrl.isNullOrBlank() &&
                    collectionHeroVideoPlaybackKey != null &&
                    endedCollectionHeroVideoPlaybackKey != collectionHeroVideoPlaybackKey
            }
        }
        val heroMediaDataState = remember(shouldPlayCollectionHeroVideoState, collectionHeroVideoUrl, heroTrailerUrlsState, collectionHeroVideoPlaybackKey) {
            derivedStateOf {
                val shouldPlayCollectionHeroVideo = shouldPlayCollectionHeroVideoState.value
                val (heroTrailerUrl, heroTrailerAudioUrl) = heroTrailerUrlsState.value
                val url = if (shouldPlayCollectionHeroVideo) collectionHeroVideoUrl else heroTrailerUrl
                val audioUrl = if (shouldPlayCollectionHeroVideo) null else heroTrailerAudioUrl
                val playbackKey = if (shouldPlayCollectionHeroVideo) collectionHeroVideoPlaybackKey else heroTrailerUrl
                Triple(url, audioUrl, playbackKey)
            }
        }
        val shouldPlayHeroTrailerState = remember(shouldPlayCatalogHeroTrailerState, shouldPlayCollectionHeroVideoState) {
            derivedStateOf { shouldPlayCatalogHeroTrailerState.value || shouldPlayCollectionHeroVideoState.value }
        }
        val heroMediaMutedState = remember(shouldPlayCollectionHeroVideoState, uiState.focusedPosterBackdropTrailerMuted) {
            derivedStateOf { shouldPlayCollectionHeroVideoState.value || uiState.focusedPosterBackdropTrailerMuted }
        }
        var heroTrailerFirstFrameRendered by remember { mutableStateOf(false) }
        LaunchedEffect(heroMediaDataState.value.third) {
            heroTrailerFirstFrameRendered = false
        }

        val isTrailerPlayingFullscreenState = remember(fullScreenBackdrop, shouldPlayCatalogHeroTrailerState, heroTrailerFirstFrameRendered) {
            derivedStateOf { fullScreenBackdrop && shouldPlayCatalogHeroTrailerState.value && heroTrailerFirstFrameRendered }
        }
        BackHandler(enabled = isTrailerPlayingFullscreenState.value) {
            focusedCatalogSelection.value = null
            expandedCatalogFocusKey.value = null
        }
        val liveHeroSceneState = remember(
            resolvedHeroState,
            shouldPlayHeroTrailerState,
            heroTrailerFirstFrameRendered,
            heroMediaDataState,
            heroMediaMutedState,
            fullScreenBackdrop
        ) {
            derivedStateOf {
                val (heroBackdrop, resolvedHero, enrichmentActive) = resolvedHeroState.value
                val (heroMediaUrl, heroMediaAudioUrl, heroMediaPlaybackKey) = heroMediaDataState.value
                ModernHeroSceneState(
                    heroBackdrop = heroBackdrop,
                    preview = if (enrichmentActive) null else resolvedHero,
                    enrichmentActive = enrichmentActive,
                    shouldPlayTrailer = shouldPlayHeroTrailerState.value,
                    trailerFirstFrameRendered = heroTrailerFirstFrameRendered,
                    trailerUrl = heroMediaUrl,
                    trailerAudioUrl = heroMediaAudioUrl,
                    trailerPlaybackKey = heroMediaPlaybackKey,
                    trailerMuted = heroMediaMutedState.value,
                    fullScreenBackdrop = fullScreenBackdrop
                )
            }
        }
        var stableHeroSceneState by remember { mutableStateOf(liveHeroSceneState.value) }
        val currentLiveHero = liveHeroSceneState.value
        if (!isVerticalRowsScrolling || stableHeroSceneState.preview == null) {
            stableHeroSceneState = currentLiveHero
        }
        val heroSceneState = if (isVerticalRowsScrolling) stableHeroSceneState else currentLiveHero

        LaunchedEffect(heroSceneState.heroBackdrop) {
            HeroBackdropState.update(heroSceneState.heroBackdrop)
        }

        val localDensity = LocalDensity.current
        val rowsViewportHeightFraction = if (useLandscapePosters) 0.49f else 0.52f
        val rowsViewportHeight = maxHeight * rowsViewportHeightFraction
        val rowTitleLineHeight = MaterialTheme.typography.titleMedium.lineHeight
        val rowTitleHeight = remember(rowTitleLineHeight, localDensity) {
            with(localDensity) {
                runCatching { rowTitleLineHeight.toDp() }
                    .getOrDefault(24.dp)
            }
        }
        val heroBackdropHeight = (maxHeight - rowsViewportHeight + rowTitleHeight + 14.dp).coerceAtMost(maxHeight)
        val verticalRowBringIntoViewSpec = remember(localDensity, defaultBringIntoViewSpec) {
            val topInsetPx = with(localDensity) { MODERN_ROW_HEADER_FOCUS_INSET.toPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> = defaultBringIntoViewSpec.scrollAnimationSpec
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - topInsetPx
            }
        }
        val bgColor = NuvioColors.Background
        val contentFocusRequester = LocalContentFocusRequester.current
        val heroMediaWidthPx = remember(maxWidth, localDensity, fullScreenBackdrop) {
            with(localDensity) {
                if (fullScreenBackdrop) maxWidth.roundToPx()
                else (maxWidth * MODERN_HERO_MEDIA_WIDTH_FRACTION).roundToPx()
            }
        }
        val heroMediaHeightPx = remember(heroBackdropHeight, maxHeight, localDensity, fullScreenBackdrop) {
            with(localDensity) {
                if (fullScreenBackdrop) maxHeight.roundToPx()
                else heroBackdropHeight.roundToPx()
            }
        }

        val heroMediaModifier = remember(heroBackdropHeight, maxHeight, fullScreenBackdrop) {
            if (fullScreenBackdrop) {
                Modifier.align(Alignment.TopStart).fillMaxWidth().height(maxHeight)
            } else {
                Modifier.align(Alignment.TopEnd).offset(x = 56.dp).fillMaxWidth(MODERN_HERO_MEDIA_WIDTH_FRACTION).height(heroBackdropHeight)
            }
        }

        ModernHeroScene(
            state = heroSceneState,
            bgColor = bgColor,
            modifier = heroMediaModifier,
            requestWidthPx = heroMediaWidthPx,
            requestHeightPx = heroMediaHeightPx,
            onTrailerEnded = {
                val playbackKey = collectionHeroVideoPlaybackKey
                if (shouldPlayCollectionHeroVideoState.value && playbackKey != null) endedCollectionHeroVideoPlaybackKey = playbackKey
                else expandedCatalogFocusKey.value = null
            },
            onFirstFrameRendered = { heroTrailerFirstFrameRendered = true },
        )
        val trailerContentAlpha by animateFloatAsState(
            targetValue = if (fullScreenBackdrop && shouldPlayCatalogHeroTrailerState.value && heroTrailerFirstFrameRendered) 0f else 1f,
            animationSpec = tween(durationMillis = 480),
            label = "trailerContentFade"
        )

        HeroTitleBlock(
            preview = heroSceneState.preview,
            enrichmentActive = heroSceneState.enrichmentActive,
            portraitMode = !useLandscapePosters,
            trailerPlaying = heroSceneState.fullScreenBackdrop && shouldPlayCatalogHeroTrailerState.value && heroSceneState.trailerFirstFrameRendered,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = rowHorizontalPadding, end = 48.dp, bottom = 0.dp + rowsViewportHeight + 16.dp)
                .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
        )

        val onActiveRowKeyChangeLambda = remember { { key: String? -> focusHolder.activeRowKey = key; activeRowKey.value = key } }
        val onActiveItemIndexChangeLambda = remember { { index: Int -> focusHolder.activeItemIndex = index; activeItemIndex.intValue = index } }
        val onLastHeroNavigationAtMsChangeLambda = remember { { ms: Long -> lastHeroNavigationAtMs.longValue = ms } }
        val onHeroFocusSettleDelayChangeLambda = remember { { delay: Long -> heroFocusSettleDelayMs.longValue = delay } }
        val onLastFocusedContinueWatchingIndexChangeLambda = remember { { index: Int -> lastFocusedContinueWatchingIndex.intValue = index } }
        val onFocusedCatalogSelectionChangeLambda = remember { { selection: FocusedCatalogSelection? -> focusedCatalogSelection.value = selection } }
        val onFocusedHeroMediaNonceChangeLambda = remember { { nonce: Int -> focusedHeroMediaNonce.intValue = nonce } }
        val onExpansionInteractionNonceChangeLambda = remember { { nonce: Int -> expansionInteractionNonce.intValue = nonce } }
        val onFastScrollingChangedLambda = remember { { scrolling: Boolean -> isFastScrolling.value = scrolling } }
        val onExpandedCatalogFocusKeyChangeLambda = remember { { key: String? -> expandedCatalogFocusKey.value = key } }
        val onBackdropInteractionLambda = remember { { expansionInteractionNonce.intValue++; Unit } }
        val onContinueWatchingOptionsLambda = remember { { item: ContinueWatchingItem -> optionsItem.value = item } }
        val onPendingRowFocusClearedLambda = remember {
            {
                pendingRowFocusKey.value = null
                pendingRowFocusIndex.value = null
            }
        }
        val stableOnRequestLazyCatalogLoad = rememberUpdatedState(onRequestLazyCatalogLoad)
        val onRowItemFocusedInternalLambda = remember(onRowItemFocusedPassedDown) {
            { rowKey: String, index: Int, isCw: Boolean ->
                onRowItemFocusedPassedDown.value.invoke(rowKey, index, isCw)
            }
        }
        val stableTrailerContentAlphaLambda = remember { { trailerContentAlpha } }

        ModernHomeRowsList(
            carouselRows = carouselRows,
            verticalRowListState = verticalRowListState,
            uiCaches = uiCaches,
            focusState = focusState,
            activeRowKey = activeRowKey,
            activeItemIndex = activeItemIndex,
            isFastScrolling = isFastScrolling,
            onFastScrollingChanged = onFastScrollingChangedLambda,
            contentFocusRequester = contentFocusRequester,
            rowsViewportHeight = rowsViewportHeight,
            catalogBottomPadding = 0.dp,
            trailerContentAlpha = stableTrailerContentAlphaLambda,
            verticalRowBringIntoViewSpec = verticalRowBringIntoViewSpec,
            onRowItemFocusedInternal = onRowItemFocusedInternalLambda,
            onNavigateToDetail = onNavigateToDetail,
            onNavigateToFolderDetail = onNavigateToFolderDetail,
            onLoadMoreCatalog = onLoadMoreCatalog,
            onContinueWatchingClick = onContinueWatchingClick,
            onContinueWatchingOptions = onContinueWatchingOptionsLambda,
            onRequestLazyCatalogLoad = { stableOnRequestLazyCatalogLoad.value(it) },
            onBackdropInteraction = onBackdropInteractionLambda,
            onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChangeLambda,
            isCatalogItemWatched = isCatalogItemWatched,
            onCatalogItemLongPress = onCatalogItemLongPress,
            onItemFocus = onItemFocus,
            onPreloadAdjacentItem = onPreloadAdjacentItem,
            enrichedPreviews = enrichedPreviews.asStable(),
            trailerPreviewUrls = trailerPreviewUrls.asStable(),
            trailerPreviewAudioUrls = trailerPreviewAudioUrls.asStable(),
            useLandscapePosters = useLandscapePosters,
            showLabels = uiState.posterLabelsEnabled,
            posterCardCornerRadius = posterCardCornerRadius,
            focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
            effectiveExpandEnabled = effectiveExpandEnabled,
            effectiveAutoplayEnabled = effectiveAutoplayEnabled,
            trailerPlaybackTarget = trailerPlaybackTarget,
            expandedCatalogFocusKey = expandedCatalogFocusKey,
            expandedTrailerPreviewUrl = { heroTrailerUrlsState.value.first },
            expandedTrailerPreviewAudioUrl = { heroTrailerUrlsState.value.second },
            portraitCatalogCardWidth = portraitCatalogCardWidth,
            portraitCatalogCardHeight = portraitCatalogCardHeight,
            landscapeCatalogCardWidth = landscapeCatalogCardWidth,
            landscapeCatalogCardHeight = landscapeCatalogCardHeight,
            continueWatchingCardWidth = continueWatchingCardWidth,
            continueWatchingCardHeight = continueWatchingCardHeight,
            blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
            useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
            pendingRowFocusKey = pendingRowFocusKey,
            pendingRowFocusIndex = pendingRowFocusIndex,
            pendingRowFocusNonce = pendingRowFocusNonce,
            onPendingRowFocusCleared = onPendingRowFocusClearedLambda,
            onActiveRowKeyChange = onActiveRowKeyChangeLambda,
            onActiveItemIndexChange = onActiveItemIndexChangeLambda,
            lastHeroNavigationAtMs = lastHeroNavigationAtMs,
            onLastHeroNavigationAtMsChange = onLastHeroNavigationAtMsChangeLambda,
            onHeroFocusSettleDelayChange = onHeroFocusSettleDelayChangeLambda,
            lastFocusedContinueWatchingIndex = lastFocusedContinueWatchingIndex,
            onLastFocusedContinueWatchingIndexChange = onLastFocusedContinueWatchingIndexChangeLambda,
            focusedCatalogSelection = focusedCatalogSelection,
            onFocusedCatalogSelectionChange = onFocusedCatalogSelectionChangeLambda,
            focusedHeroMediaNonce = focusedHeroMediaNonce,
            onFocusedHeroMediaNonceChange = onFocusedHeroMediaNonceChangeLambda,
            onExpansionInteractionNonceChange = onExpansionInteractionNonceChangeLambda,
            isVerticalRowsScrolling = isVerticalRowsScrolling,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }

    val selectedOptionsItem = optionsItem.value
    if (selectedOptionsItem != null) {
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem.value = null },
            onRemove = {
                val targetIndex = if (uiState.continueWatchingItems.size <= 1) null
                else (lastFocusedContinueWatchingIndex.intValue).coerceAtMost(uiState.continueWatchingItems.size - 2).coerceAtLeast(0)
                pendingRowFocusKey.value = if (targetIndex != null) "continue_watching" else null
                pendingRowFocusIndex.value = targetIndex
                pendingRowFocusNonce.intValue++
                onRemoveContinueWatching(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.season(),
                    selectedOptionsItem.episode(),
                    selectedOptionsItem is ContinueWatchingItem.NextUp
                )
                optionsItem.value = null
            },
            onDetails = {
                onNavigateToDetail(selectedOptionsItem.contentId(), selectedOptionsItem.contentType(), "")
                optionsItem.value = null
            },
            onStartFromBeginning = {
                onContinueWatchingStartFromBeginning(selectedOptionsItem)
                optionsItem.value = null
            },
            showPlayManually = showContinueWatchingManualPlayOption,
            onPlayManually = {
                onContinueWatchingPlayManually(selectedOptionsItem)
                optionsItem.value = null
            }
        )
    }
}
