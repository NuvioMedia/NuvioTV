@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.metrics.performance.PerformanceMetricsState
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
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.imageLoader
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import android.view.KeyEvent as AndroidKeyEvent
import kotlinx.coroutines.flow.distinctUntilChanged


private const val MODERN_HERO_RAPID_NAV_THRESHOLD_MS = 130L
private const val MODERN_HERO_RAPID_NAV_SETTLE_MS = 170L

private fun buildPrefetchRequest(
    context: android.content.Context,
    url: String,
    widthPx: Int,
    heightPx: Int
): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .size(width = widthPx, height = heightPx)
    .build()

@Composable
fun ModernHomeContent(
    uiState: HomeUiState,
    focusState: HomeScreenFocusState,
    enrichingItemId: String? = null,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
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
    onItemFocus: (MetaPreview) -> Unit = {},
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit
) {
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val isSidebarExpanded = LocalSidebarExpanded.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val fullScreenBackdrop = uiState.modernHeroFullScreenBackdropEnabled
    val isLandscapeModern = useLandscapePosters
    val expandControlAvailable = !isLandscapeModern
    val trailerPlaybackTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget
    val effectiveAutoplayEnabled =
        uiState.focusedPosterBackdropTrailerEnabled &&
            (isLandscapeModern || uiState.focusedPosterBackdropExpandEnabled)
    val landscapeExpandedCardMode =
        isLandscapeModern &&
            effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
    val effectiveExpandEnabled =
        (uiState.focusedPosterBackdropExpandEnabled && expandControlAvailable) ||
            landscapeExpandedCardMode
    val shouldActivateFocusedPosterFlow =
        effectiveExpandEnabled ||
            (effectiveAutoplayEnabled &&
                trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
    val carouselRows = uiState.modernHomePresentation.rows

    if (carouselRows.isEmpty()) return
    val carouselLookups = uiState.modernHomePresentation.lookups
    val rowByKey = carouselLookups.rowByKey
    val rowKeyByGlobalRowIndex = carouselLookups.rowKeyByGlobalRowIndex
    val firstHeroPreviewByRow = carouselLookups.firstHeroPreviewByRow
    val fallbackBackdropByRow = carouselLookups.fallbackBackdropByRow
    val activeRowKeys = carouselLookups.activeRowKeys
    val activeItemKeysByRow = carouselLookups.activeItemKeysByRow
    val activeCatalogItemIds = carouselLookups.activeCatalogItemIds
    val verticalRowListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val isVerticalRowsScrolling by remember(verticalRowListState) {
        derivedStateOf { verticalRowListState.isScrollInProgress }
    }

    // Tag JankStats with key UI states so jank reports are actionable.
    val currentView = LocalView.current
    val focusManager = LocalFocusManager.current
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
    // Holder for hot-path focus tracking — lambdas read through reference, no stale closure
    val focusHolder = remember {
        object {
            var activeRowKey: String? = null
            var activeItemIndex: Int = 0
        }
    }
    var activeRowKey by remember { mutableStateOf<String?>(null) }
    var activeItemIndex by remember { mutableIntStateOf(0) }
    var pendingRowFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingRowFocusIndex by remember { mutableStateOf<Int?>(null) }
    var pendingRowFocusNonce by remember { mutableIntStateOf(0) }
    var heroItem by remember {
        val initialHero = carouselRows.firstOrNull()?.items?.firstOrNull()?.heroPreview
        mutableStateOf<HeroPreview?>(initialHero)
    }
    val heroTransitioningRef = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var restoredFromSavedState by remember { mutableStateOf(false) }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val lastFocusedContinueWatchingIndexRef = remember { java.util.concurrent.atomic.AtomicInteger(-1) }
    val lastKeyRepeatDispatchRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val lastHeroNavigationAtMsRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val heroFocusSettleDelayMsRef = remember { java.util.concurrent.atomic.AtomicLong(MODERN_HERO_FOCUS_DEBOUNCE_MS) }
    var focusedCatalogSelection by remember { mutableStateOf<FocusedCatalogSelection?>(null) }
    var lastRequestedTrailerFocusKey by remember { mutableStateOf<String?>(null) }
    var expandedCatalogFocusKey by remember { mutableStateOf<String?>(null) }
    var expansionInteractionNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(
        focusedCatalogSelection?.focusKey,
        expansionInteractionNonce,
        shouldActivateFocusedPosterFlow,
        trailerPlaybackTarget,
        uiState.focusedPosterBackdropExpandDelaySeconds,
        isVerticalRowsScrolling
    ) {
        expandedCatalogFocusKey = null
        if (!shouldActivateFocusedPosterFlow) return@LaunchedEffect
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val selection = focusedCatalogSelection ?: return@LaunchedEffect
        delay(uiState.focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(0) * 1000L)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (shouldActivateFocusedPosterFlow &&
            !isVerticalRowsScrolling &&
            focusedCatalogSelection?.focusKey == selection.focusKey
        ) {
            expandedCatalogFocusKey = selection.focusKey
        }
    }

    LaunchedEffect(
        focusedCatalogSelection?.focusKey,
        effectiveAutoplayEnabled,
        isVerticalRowsScrolling
    ) {
        if (!effectiveAutoplayEnabled) {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (isVerticalRowsScrolling) {
            return@LaunchedEffect
        }
        val selection = focusedCatalogSelection ?: run {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (selection.focusKey == lastRequestedTrailerFocusKey) {
            return@LaunchedEffect
        }
        onRequestTrailerPreview(
            selection.payload.itemId,
            selection.payload.trailerTitle,
            selection.payload.trailerReleaseInfo,
            selection.payload.trailerApiType
        )
        lastRequestedTrailerFocusKey = selection.focusKey
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus, focusState.focusedRowIndex, focusState.focusedItemIndex) {
        focusedItemByRow.keys.retainAll(activeRowKeys)
        itemFocusRequesters.keys.retainAll(activeRowKeys)
        rowListStates.keys.retainAll(activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(activeRowKeys)
        carouselRows.forEach { row ->
            val rowRequesters = itemFocusRequesters[row.key] ?: return@forEach
            val allowedKeys = activeItemKeysByRow[row.key] ?: emptySet()
            rowRequesters.keys.retainAll(allowedKeys)
        }
        if (focusedCatalogSelection?.payload?.itemId !in activeCatalogItemIds) {
            focusedCatalogSelection = null
            expandedCatalogFocusKey = null
        }

        carouselRows.forEach { row ->
            if (row.items.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }
        }

        if (!restoredFromSavedState && focusState.hasSavedFocus) {
            val savedRowKey = when {
                focusState.focusedRowIndex == -1 && uiState.continueWatchingItems.isNotEmpty() -> "continue_watching"
                focusState.focusedRowIndex >= 0 -> rowKeyByGlobalRowIndex[focusState.focusedRowIndex]
                else -> null
            }

            val resolvedRow = savedRowKey?.let(rowByKey::get) ?: carouselRows.first()
            val resolvedIndex = focusState.focusedItemIndex
                .coerceAtLeast(0)
                .coerceAtMost((resolvedRow.items.size - 1).coerceAtLeast(0))

            focusHolder.activeRowKey = resolvedRow.key
            focusHolder.activeItemIndex = resolvedIndex
            activeRowKey = resolvedRow.key
            activeItemIndex = resolvedIndex
            focusedItemByRow[resolvedRow.key] = resolvedIndex
            heroItem = resolvedRow.items.getOrNull(resolvedIndex)?.heroPreview
                ?: resolvedRow.items.firstOrNull()?.heroPreview
            pendingRowFocusKey = resolvedRow.key
            pendingRowFocusIndex = resolvedIndex
            pendingRowFocusNonce++
            restoredFromSavedState = true
            return@LaunchedEffect
        }

        val hadActiveRow = focusHolder.activeRowKey != null
        val existingActive = focusHolder.activeRowKey?.let(rowByKey::get)
        val resolvedActive = existingActive ?: carouselRows.first()
        val resolvedIndex = focusedItemByRow[resolvedActive.key]
            ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0))
            ?: 0
        focusHolder.activeRowKey = resolvedActive.key
        focusHolder.activeItemIndex = resolvedIndex
        activeRowKey = resolvedActive.key
        activeItemIndex = resolvedIndex
        focusedItemByRow[resolvedActive.key] = resolvedIndex
        heroItem = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
            ?: resolvedActive.items.firstOrNull()?.heroPreview
        if (!focusState.hasSavedFocus && (!hadActiveRow || existingActive == null)) {
            pendingRowFocusKey = resolvedActive.key
            pendingRowFocusIndex = resolvedIndex
            pendingRowFocusNonce++
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

    val activeRow by remember(carouselRows, rowByKey, activeRowKey) {
        derivedStateOf {
            val activeKey = activeRowKey
            if (activeKey == null) {
                null
            } else {
                rowByKey[activeKey] ?: carouselRows.firstOrNull()
            }
        }
    }
    val clampedActiveItemIndex by remember(activeRow, activeItemIndex) {
        derivedStateOf {
            activeRow?.let { row ->
                activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            } ?: 0
        }
    }

    LaunchedEffect(activeRow?.key, activeRow?.items?.size) {
        val row = activeRow ?: return@LaunchedEffect
        val clampedIndex = focusHolder.activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        if (focusHolder.activeItemIndex != clampedIndex) {
            focusHolder.activeItemIndex = clampedIndex
            activeItemIndex = clampedIndex
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
        heroTransitioningRef.set(true)
        val settleDelayMs = heroFocusSettleDelayMsRef.get()
        delay(settleDelayMs)
        if (isVerticalRowsScrolling) {
            heroTransitioningRef.set(false)
            return@LaunchedEffect
        }
        if (System.currentTimeMillis() - lastHeroNavigationAtMsRef.get() < settleDelayMs) {
            heroTransitioningRef.set(false)
            return@LaunchedEffect
        }
        val row = latestHeroRow ?: run { heroTransitioningRef.set(false); return@LaunchedEffect }
        val latestKey = row.items.getOrNull(latestHeroIndex)?.key ?: row.items.firstOrNull()?.key
        if (latestKey != targetHeroKey) {
            heroTransitioningRef.set(false)
            return@LaunchedEffect
        }
        val latestHero =
            row.items.getOrNull(latestHeroIndex)?.heroPreview ?: row.items.firstOrNull()?.heroPreview
        if (latestHero != null && heroItem != latestHero) {
            heroItem = latestHero
        }
        heroTransitioningRef.set(false)
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
                .filter { it.globalRowIndex >= 0 }
                .associate { rowState -> rowState.key to (focusedItemByRow[rowState.key] ?: 0) }

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

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val posterCardCornerRadius = remember(uiState.posterCardCornerRadiusDp) { uiState.posterCardCornerRadiusDp.dp }
        val rowHorizontalPadding = 52.dp

        val activeCarouselItem = remember(activeRow, clampedActiveItemIndex) {
            activeRow?.items?.getOrNull(clampedActiveItemIndex)
        }
        val activeItemId = activeCarouselItem?.metaPreview?.id
        val enrichmentActive = enrichingItemId != null && enrichingItemId == activeItemId
        // When enrichment is active use heroItem (frozen), when done use activeCarouselItem
        // which already has the enriched data from uiState update
        val resolvedHero = if (enrichmentActive) heroItem else activeCarouselItem?.heroPreview ?: heroItem
        val activeRowFallbackBackdrop = remember(activeRow?.key, activeRow?.items?.size) {
            activeRow?.items?.firstNotNullOfOrNull { item ->
                item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
            }
        }
        val heroBackdrop = remember(resolvedHero, activeRowFallbackBackdrop, heroItem) {
            firstNonBlank(
                resolvedHero?.backdrop,
                resolvedHero?.imageUrl,
                resolvedHero?.poster,
                if (heroItem == null) activeRowFallbackBackdrop else null
            )
        }
        val expandedFocusedSelection = remember(focusedCatalogSelection, expandedCatalogFocusKey) {
            focusedCatalogSelection?.takeIf { it.focusKey == expandedCatalogFocusKey }
        }
        val heroTrailerUrl by remember(expandedFocusedSelection) {
            derivedStateOf {
                expandedFocusedSelection?.payload?.itemId?.let { trailerPreviewUrls[it] }
            }
        }
        val heroTrailerAudioUrl by remember(expandedFocusedSelection) {
            derivedStateOf {
                expandedFocusedSelection?.payload?.itemId?.let { trailerPreviewAudioUrls[it] }
            }
        }
        val expandedCatalogTrailerUrl = heroTrailerUrl
        val expandedCatalogTrailerAudioUrl = heroTrailerAudioUrl
        val shouldPlayHeroTrailer = remember(
            effectiveAutoplayEnabled,
            trailerPlaybackTarget,
            heroTrailerUrl,
            isVerticalRowsScrolling,
            isSidebarExpanded
        ) {
            effectiveAutoplayEnabled &&
                !isSidebarExpanded &&
                !isVerticalRowsScrolling &&
                trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
                !heroTrailerUrl.isNullOrBlank()
        }
        var heroTrailerFirstFrameRendered by remember(heroTrailerUrl) { mutableStateOf(false) }
        LaunchedEffect(shouldPlayHeroTrailer) {
            if (!shouldPlayHeroTrailer) {
                heroTrailerFirstFrameRendered = false
            }
        }

        val isTrailerPlayingFullscreen = fullScreenBackdrop && shouldPlayHeroTrailer && heroTrailerFirstFrameRendered
        BackHandler(enabled = isTrailerPlayingFullscreen) {
            focusedCatalogSelection = null
            expandedCatalogFocusKey = null
        }
        val liveHeroSceneState = remember(
            heroBackdrop,
            resolvedHero,
            enrichmentActive,
            shouldPlayHeroTrailer,
            heroTrailerFirstFrameRendered,
            heroTrailerUrl,
            heroTrailerAudioUrl,
            uiState.focusedPosterBackdropTrailerMuted,
            fullScreenBackdrop
        ) {
            ModernHeroSceneState(
                heroBackdrop = heroBackdrop,
                preview = if (enrichmentActive) null else resolvedHero,
                enrichmentActive = enrichmentActive,
                shouldPlayTrailer = shouldPlayHeroTrailer,
                trailerFirstFrameRendered = heroTrailerFirstFrameRendered,
                trailerUrl = heroTrailerUrl,
                trailerAudioUrl = heroTrailerAudioUrl,
                trailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                fullScreenBackdrop = fullScreenBackdrop
            )
        }
        var stableHeroSceneState by remember { mutableStateOf(liveHeroSceneState) }
        LaunchedEffect(liveHeroSceneState, isVerticalRowsScrolling) {
            if (!isVerticalRowsScrolling || stableHeroSceneState.preview == null) {
                stableHeroSceneState = liveHeroSceneState
            }
        }
        val heroSceneState = if (isVerticalRowsScrolling) stableHeroSceneState else liveHeroSceneState

        val prefetchContext = LocalContext.current
        LaunchedEffect(heroSceneState.heroBackdrop) {
            HeroBackdropState.update(heroSceneState.heroBackdrop)
        }
        if (!fullScreenBackdrop && !heroSceneState.heroBackdrop.isNullOrBlank()) {
            val screenConf = LocalConfiguration.current
            val density = LocalDensity.current
            val screenWPx = remember(screenConf, density) { with(density) { screenConf.screenWidthDp.dp.roundToPx() } }
            val screenHPx = remember(screenConf, density) { with(density) { screenConf.screenHeightDp.dp.roundToPx() } }
            val heroUrl = heroSceneState.heroBackdrop!!
            val req = remember(prefetchContext, heroUrl, screenWPx, screenHPx) {
                buildPrefetchRequest(prefetchContext, heroUrl, screenWPx, screenHPx)
            }
            LaunchedEffect(req) {
                prefetchContext.imageLoader.enqueue(req)
            }
        }

        val catalogBottomPadding = 0.dp
        val heroToCatalogGap = 16.dp
        val rowTitleBottom = 14.dp
        val rowsViewportHeightFraction = if (useLandscapePosters) 0.49f else 0.52f
        val rowsViewportHeight = maxHeight * rowsViewportHeightFraction
        val localDensity = LocalDensity.current
        val rowTitleLineHeight = MaterialTheme.typography.titleMedium.lineHeight
        val rowTitleHeight = with(localDensity) {
            runCatching { rowTitleLineHeight.toDp() }
                .getOrDefault(24.dp)
        }
        val heroBackdropHeight = (maxHeight - rowsViewportHeight + rowTitleHeight + rowTitleBottom)
            .coerceAtMost(maxHeight)
        val verticalRowBringIntoViewSpec = remember(localDensity, defaultBringIntoViewSpec) {
            val topInsetPx = with(localDensity) { MODERN_ROW_HEADER_FOCUS_INSET.toPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float = offset - topInsetPx
            }
        }
        val bgColor = NuvioColors.Background
        val contentFocusRequester = LocalContentFocusRequester.current
        val focusRestorerRequester by remember(carouselRows, uiCaches) {
            derivedStateOf {
                val rowKey = activeRowKey
                if (rowKey != null) {
                    val row = carouselRows.firstOrNull { it.key == rowKey }
                    val rowListState = uiCaches.rowListStates[rowKey]
                    val firstVisibleIndex = rowListState?.firstVisibleItemIndex ?: 0
                    val safeIndex = firstVisibleIndex.coerceIn(0, ((row?.items?.size ?: 1) - 1).coerceAtLeast(0))
                    val itemKey = row?.items?.getOrNull(safeIndex)?.key
                    if (itemKey != null) {
                        uiCaches.itemFocusRequesters[rowKey]?.get(itemKey) ?: FocusRequester.Default
                    } else FocusRequester.Default
                } else FocusRequester.Default
            }
        }
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
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(maxHeight)
            } else {
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 56.dp)
                    .fillMaxWidth(MODERN_HERO_MEDIA_WIDTH_FRACTION)
                    .height(heroBackdropHeight)
            }
        }

        ModernHeroScene(
            state = heroSceneState,
            bgColor = bgColor,
            modifier = heroMediaModifier,
            requestWidthPx = heroMediaWidthPx,
            requestHeightPx = heroMediaHeightPx,
            onTrailerEnded = { expandedCatalogFocusKey = null },
            onFirstFrameRendered = { heroTrailerFirstFrameRendered = true },
        )
        val trailerContentAlpha by animateFloatAsState(
            targetValue = if (fullScreenBackdrop && shouldPlayHeroTrailer && heroTrailerFirstFrameRendered) 0f else 1f,
            animationSpec = tween(durationMillis = 480),
            label = "trailerContentFade"
        )

        HeroTitleBlock(
            preview = heroSceneState.preview,
            enrichmentActive = heroSceneState.enrichmentActive,
            portraitMode = !useLandscapePosters,
            trailerPlaying = heroSceneState.fullScreenBackdrop &&
                heroSceneState.shouldPlayTrailer &&
                heroSceneState.trailerFirstFrameRendered,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = rowHorizontalPadding,
                    end = 48.dp,
                    bottom = catalogBottomPadding + rowsViewportHeight + heroToCatalogGap
                )
                .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec) {
            LazyColumn(
                state = verticalRowListState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(rowsViewportHeight)
                    .padding(bottom = catalogBottomPadding)
                    .clipToBounds()
                    .graphicsLayer { alpha = trailerContentAlpha }
                    .focusRequester(contentFocusRequester)
                    .focusRestorer { focusRestorerRequester }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                       
                    
                        if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                            native.repeatCount > 0 &&
                            (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP ||
                                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
                                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
                        ) {
                            val isVertical = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP
                            val gateMs = if (isVertical) 112L else 80L
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastKeyRepeatDispatchRef.get() < gateMs) {
                                return@onPreviewKeyEvent true // consume, too soon
                            }
                            lastKeyRepeatDispatchRef.set(now)
                            val direction = when (native.keyCode) {
                                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                                AndroidKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                                else -> null
                            }
                            if (direction != null) {
                                focusManager.moveFocus(direction)
                            }
                            return@onPreviewKeyEvent true
                        }
                        false
                    },
                contentPadding = PaddingValues(bottom = rowsViewportHeight),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                itemsIndexed(
                    items = carouselRows,
                    key = { _, row -> row.key },
                    contentType = { _, _ -> "modern_home_row" }
                ) { _, row ->
                    val stableOnContinueWatchingOptions = remember(Unit) {
                        { item: ContinueWatchingItem -> optionsItem = item }
                    }
                    val stableOnRowItemFocused = remember(Unit) {
                        { rowKey: String, index: Int, isContinueWatchingRow: Boolean ->
                            val rowBecameActive = focusHolder.activeRowKey != rowKey
                            val itemChanged = focusHolder.activeItemIndex != index
                            if (rowBecameActive || itemChanged) {
                                val now = System.currentTimeMillis()
                                val timeSinceLastHeroNav = now - lastHeroNavigationAtMsRef.get()
                                heroFocusSettleDelayMsRef.set(
                                    if (lastHeroNavigationAtMsRef.get() != 0L &&
                                        timeSinceLastHeroNav in 1 until MODERN_HERO_RAPID_NAV_THRESHOLD_MS
                                    ) MODERN_HERO_RAPID_NAV_SETTLE_MS
                                    else MODERN_HERO_FOCUS_DEBOUNCE_MS
                                )
                                lastHeroNavigationAtMsRef.set(now)
                                focusHolder.activeRowKey = rowKey
                                focusHolder.activeItemIndex = index
                                activeRowKey = rowKey
                                activeItemIndex = index
                            }
                            if (focusedItemByRow[rowKey] != index) {
                                focusedItemByRow[rowKey] = index
                            }
                            if (isContinueWatchingRow) {
                                if (lastFocusedContinueWatchingIndexRef.get() != index) {
                                    lastFocusedContinueWatchingIndexRef.set(index)
                                }
                                if (focusedCatalogSelection != null) {
                                    focusedCatalogSelection = null
                                }
                            }
                        }
                    }
                    ModernRowSection(
                        row = row,
                        isActiveRow = activeRowKey == row.key,
                        isVerticalRowsScrolling = isVerticalRowsScrolling,
                        rowTitleBottom = rowTitleBottom,
                        defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                        focusStateCatalogRowScrollIndex = remember(focusState.catalogRowScrollStates, row.key) {
                            focusState.catalogRowScrollStates[row.key] ?: 0
                        },
                        uiCaches = uiCaches,
                        pendingRowFocusKey = pendingRowFocusKey,
                        pendingRowFocusIndex = pendingRowFocusIndex,
                        pendingRowFocusNonce = pendingRowFocusNonce,
                        onPendingRowFocusCleared = remember(Unit) {
                            {
                                pendingRowFocusKey = null
                                pendingRowFocusIndex = null
                            }
                        },
                        onRowItemFocused = stableOnRowItemFocused,
                        useLandscapePosters = useLandscapePosters,
                        showLabels = uiState.posterLabelsEnabled,
                        posterCardCornerRadius = posterCardCornerRadius,
                        focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                        effectiveExpandEnabled = effectiveExpandEnabled,
                        effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                        trailerPlaybackTarget = trailerPlaybackTarget,
                        expandedCatalogFocusKey = expandedCatalogFocusKey,
                        expandedTrailerPreviewUrl = expandedCatalogTrailerUrl,
                        expandedTrailerPreviewAudioUrl = expandedCatalogTrailerAudioUrl,
                        portraitCatalogCardWidth = portraitCatalogCardWidth,
                        portraitCatalogCardHeight = portraitCatalogCardHeight,
                        landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                        landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                        continueWatchingCardWidth = continueWatchingCardWidth,
                        continueWatchingCardHeight = continueWatchingCardHeight,
                        blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onContinueWatchingOptions = stableOnContinueWatchingOptions,
                        isCatalogItemWatched = isCatalogItemWatched,
                        onCatalogItemLongPress = onCatalogItemLongPress,
                        onItemFocus = onItemFocus,
                        onPreloadAdjacentItem = onPreloadAdjacentItem,
                        onCatalogSelectionFocused = remember(Unit) {
                            { selection: FocusedCatalogSelection ->
                                if (focusedCatalogSelection != selection) {
                                    focusedCatalogSelection = selection
                                }
                            }
                        },
                        onNavigateToDetail = onNavigateToDetail,
                        onLoadMoreCatalog = onLoadMoreCatalog,
                        onBackdropInteraction = remember(Unit) { { expansionInteractionNonce++ } },
                        onExpandedCatalogFocusKeyChange = remember(Unit) { { expandedCatalogFocusKey = it } }
                    )
                }
            }
        }
    }

    val selectedOptionsItem = optionsItem
    if (selectedOptionsItem != null) {
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                val targetIndex = if (uiState.continueWatchingItems.size <= 1) {
                    null
                } else {
                    minOf(lastFocusedContinueWatchingIndexRef.get(), uiState.continueWatchingItems.size - 2)
                        .coerceAtLeast(0)
                }
                pendingRowFocusKey = if (targetIndex != null) "continue_watching" else null
                pendingRowFocusIndex = targetIndex
                pendingRowFocusNonce++
                onRemoveContinueWatching(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.season(),
                    selectedOptionsItem.episode(),
                    selectedOptionsItem is ContinueWatchingItem.NextUp
                )
                optionsItem = null
            },
            onDetails = {
                onNavigateToDetail(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.contentType(),
                    ""
                )
                optionsItem = null
            },
            onStartFromBeginning = {
                onContinueWatchingStartFromBeginning(selectedOptionsItem)
                optionsItem = null
            },
            showPlayManually = showContinueWatchingManualPlayOption,
            onPlayManually = {
                onContinueWatchingPlayManually(selectedOptionsItem)
                optionsItem = null
            }
        )
    }
}
