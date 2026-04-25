@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.components.rememberArtworkBackedCardGlow
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

private const val MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS = 140L
private const val POSTER_PREFETCH_DISTANCE = 8

internal val LocalVerticalRowsScrolling = androidx.compose.runtime.compositionLocalOf { false }

/**
 * True while the user is actively "fast-scrolling" — i.e. holding DPAD_LEFT/RIGHT or
 * DPAD_UP/DOWN and the LazyColumn-level key handler has taken over to drag the list
 * programmatically instead of letting [androidx.compose.ui.focus.FocusManager.moveFocus]
 * pull focus card-by-card. Cards use this to suppress their focus chrome (border / glow /
 * GIF) during the drag; the chrome snaps back onto whichever card focus lands on when
 * the user releases the key. Defaults to `false`, so any card used outside a modern
 * home row keeps its normal focus visuals.
 */
internal val LocalFastScrollActive = androidx.compose.runtime.compositionLocalOf { false }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernContinueWatchingRowItem(
    payload: ModernPayload.ContinueWatching,
    requester: FocusRequester,
    cardWidth: Dp,
    imageHeight: Dp,
    blurUnwatchedEpisodes: Boolean,
    onFocused: () -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onShowOptions: (ContinueWatchingItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val item = payload.item
    val onClick = remember(item) { { onContinueWatchingClick(item) } }
    val onLongPress = remember(item) { { onShowOptions(item) } }
    var focusEventId by remember { mutableStateOf(0) }
    var isCardFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)

    LaunchedEffect(focusEventId, isCardFocused) {
        if (focusEventId == 0 || !isCardFocused) return@LaunchedEffect
        val targetEventId = focusEventId
        delay(MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS)
        if (!isCardFocused || focusEventId != targetEventId) return@LaunchedEffect
        latestOnFocused()
    }

    ContinueWatchingCard(
        item = item,
        onClick = onClick,
        onLongPress = onLongPress,
        cardWidth = cardWidth,
        imageHeight = imageHeight,
        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged {
                isCardFocused = it.isFocused
                if (it.isFocused) {
                    focusEventId += 1
                }
            }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernCatalogRowItem(
    item: ModernCarouselItem,
    payload: ModernPayload,
    requester: FocusRequester,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    portraitCatalogCardWidth: Dp,
    portraitCatalogCardHeight: Dp,
    landscapeCatalogCardWidth: Dp,
    landscapeCatalogCardHeight: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    isBackdropExpanded: Boolean,
    expandedTrailerPreviewUrl: String?,
    expandedTrailerPreviewAudioUrl: String?,
    isWatched: Boolean,
    onFocused: () -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: () -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    enrichedLogoUrl: String? = null,
    enrichedBackdropUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val focusKey = when (payload) {
        is ModernPayload.Catalog -> payload.focusKey
        is ModernPayload.CollectionFolder -> payload.focusKey
        is ModernPayload.ContinueWatching -> error("Unsupported payload for ModernCatalogRowItem")
    }
    var focusEventId by remember(focusKey) { mutableStateOf(0) }
    var isCardFocused by remember(focusKey) { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestOnItemFocus by rememberUpdatedState(onItemFocus)
    val latestOnPreloadAdjacentItem by rememberUpdatedState(onPreloadAdjacentItem)
    val latestOnCatalogSelectionFocused by rememberUpdatedState(onCatalogSelectionFocused)

    LaunchedEffect(focusEventId, isCardFocused, focusKey) {
        if (focusEventId == 0 || !isCardFocused) return@LaunchedEffect
        val targetEventId = focusEventId
        delay(MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS)
        if (!isCardFocused || focusEventId != targetEventId) return@LaunchedEffect

        latestOnFocused()
        item.metaPreview?.let { latestOnItemFocus(it) }
        latestOnPreloadAdjacentItem()
        if (payload is ModernPayload.Catalog) {
            latestOnCatalogSelectionFocused(
                FocusedCatalogSelection(
                    focusKey = focusKey,
                    payload = payload
                )
            )
        }
    }

    val suppressCardExpansionForHeroTrailer =
        effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
    val effectiveBackdropExpanded = isBackdropExpanded && !suppressCardExpansionForHeroTrailer
    val isSidebarExpanded = LocalSidebarExpanded.current
    val playTrailerInExpandedCard =
        effectiveAutoplayEnabled &&
            !isSidebarExpanded &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD &&
            effectiveBackdropExpanded
    val trailerPreviewUrl = if (playTrailerInExpandedCard) {
        expandedTrailerPreviewUrl
    } else {
        null
    }
    val trailerPreviewAudioUrl = if (playTrailerInExpandedCard) {
        expandedTrailerPreviewAudioUrl
    } else {
        null
    }
    val cardMetrics = remember(
        item,
        useLandscapePosters,
        portraitCatalogCardWidth,
        portraitCatalogCardHeight,
        landscapeCatalogCardWidth,
        landscapeCatalogCardHeight
    ) {
        item.catalogCardMetrics(
            useLandscapePosters = useLandscapePosters,
            portraitCardWidth = portraitCatalogCardWidth,
            portraitCardHeight = portraitCatalogCardHeight,
            landscapeCardWidth = landscapeCatalogCardWidth,
            landscapeCardHeight = landscapeCatalogCardHeight
        )
    }

    ModernCarouselCard(
        item = item,
        useLandscapeOverlayTreatment = useLandscapePosters,
        showLabels = showLabels,
        cardCornerRadius = posterCardCornerRadius,
        cardWidth = cardMetrics.width,
        cardHeight = cardMetrics.height,
        modifier = modifier,
        focusedPosterBackdropExpandEnabled = effectiveExpandEnabled,
        isBackdropExpanded = effectiveBackdropExpanded,
        playTrailerInExpandedCard = playTrailerInExpandedCard,
        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
        trailerPreviewUrl = trailerPreviewUrl,
        trailerPreviewAudioUrl = trailerPreviewAudioUrl,
        isWatched = isWatched,
        enrichedLogoUrl = enrichedLogoUrl,
        enrichedBackdropUrl = enrichedBackdropUrl,
        focusRequester = requester,
        onFocused = {
            focusEventId += 1
        },
        onFocusStateChanged = { focused ->
            isCardFocused = focused
        },
        onClick = {
            latestOnFocused()
            item.metaPreview?.let { latestOnItemFocus(it) }
            when (payload) {
                is ModernPayload.Catalog -> onNavigateToDetail(
                    payload.itemId,
                    payload.itemType,
                    payload.addonBaseUrl
                )
                is ModernPayload.CollectionFolder -> onNavigateToFolderDetail(
                    payload.collectionId,
                    payload.folderId
                )
                is ModernPayload.ContinueWatching -> Unit
            }
        },
        onLongPress = onLongPress,
        onBackdropInteraction = onBackdropInteraction,
        onTrailerEnded = { onExpandedCatalogFocusKeyChange(null) }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernRowSection(
    row: HeroCarouselRow,
    isActiveRow: Boolean,
    isVerticalRowsScrolling: Boolean,
    rowTitleBottom: Dp,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    focusStateCatalogRowScrollIndex: Int,
    uiCaches: ModernHomeUiCaches,
    pendingRowFocusKey: String?,
    pendingRowFocusIndex: Int?,
    pendingRowFocusNonce: Int,
    onPendingRowFocusCleared: () -> Unit,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    expandedCatalogFocusKey: String?,
    expandedTrailerPreviewUrl: String?,
    expandedTrailerPreviewAudioUrl: String?,
    portraitCatalogCardWidth: Dp,
    portraitCatalogCardHeight: Dp,
    landscapeCatalogCardWidth: Dp,
    landscapeCatalogCardHeight: Dp,
    continueWatchingCardWidth: Dp,
    continueWatchingCardHeight: Dp,
    blurUnwatchedEpisodes: Boolean,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    enrichedPreviews: Map<String, MetaPreview> = emptyMap(),
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit
) {
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val rowListStates = uiCaches.rowListStates
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals

    val rowKey = row.key
    // Blocks vertical focus exit during placeholder→data transition.
    val blockingFocusExit = remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.then(
            if (blockingFocusExit.value) {
                Modifier.focusProperties {
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }
            } else Modifier
        )
    ) {
        val titleMediumStyle = MaterialTheme.typography.titleMedium
        val rowTitleStyle = remember(titleMediumStyle) {
            titleMediumStyle.copy(fontWeight = FontWeight.SemiBold)
        }
        val rowTitle = remember(row.title) { row.title }
        val textColor = remember { NuvioColors.TextPrimary }
        val textModifier = remember(rowTitleBottom) {
            Modifier.padding(start = 52.dp, bottom = rowTitleBottom)
        }
        Text(
            text = rowTitle,
            style = rowTitleStyle,
            color = textColor,
            modifier = textModifier
        )

        val rowListState = rowListStates.getOrPut(row.key) {
            LazyListState(
                firstVisibleItemIndex = focusStateCatalogRowScrollIndex,
                prefetchStrategy = LazyListPrefetchStrategy(nestedPrefetchItemCount = 4)
            )
        }

        // When fresh data prepends new items to a row the user hasn't
        // scrolled, snap back to position 0 so the newest content is visible.
        val firstItemKey = row.items.firstOrNull()?.key
        LaunchedEffect(row.key, firstItemKey) {
            if (firstItemKey == null) return@LaunchedEffect
            val state = rowListState
            // Only reset if the user hasn't scrolled at all.
            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
                state.scrollToItem(0)
            }
        }

        // When placeholder items are replaced by real data and this row
        // is the active row, re-request focus on the first real item.
        val wasPlaceholderRef = remember { mutableStateOf(row.isLoading && firstItemKey?.startsWith("placeholder_") == true) }
        val needsFocusRestore = remember { mutableStateOf(false) }
        val wasPlaceholder = wasPlaceholderRef.value
        val isNowReal = !row.isLoading || firstItemKey?.startsWith("placeholder_") != true
        if (wasPlaceholder && isNowReal && isActiveRow) {
            needsFocusRestore.value = true
            blockingFocusExit.value = true
        }
        wasPlaceholderRef.value = row.isLoading && firstItemKey?.startsWith("placeholder_") == true

        // Restore focus after placeholder→data transition using a retry loop
        // that starts immediately (no pre-delay) to minimize the visible jump.
        LaunchedEffect(needsFocusRestore.value, row.key) {
            if (!needsFocusRestore.value) return@LaunchedEffect
            needsFocusRestore.value = false
            if (row.items.isEmpty()) {
                blockingFocusExit.value = false
                return@LaunchedEffect
            }
            // Use index-based key matching the LazyRow key scheme
            val targetStableKey = "${row.key}_0"
            repeat(15) {
                val requester = uiCaches.itemFocusRequesters[row.key]?.get(targetStableKey)
                if (requester != null) {
                    val ok = runCatching { requester.requestFocus(); true }.getOrDefault(false)
                    if (ok) {
                        blockingFocusExit.value = false
                        return@LaunchedEffect
                    }
                }
                withFrameNanos { }
            }
            blockingFocusExit.value = false
        }

        val isRowScrollingState = remember(rowListState) {
            derivedStateOf { rowListState.isScrollInProgress }
        }
        val isRowScrolling by isRowScrollingState
        val currentRowState = rememberUpdatedState(row)
        val loadMoreCatalogId = row.catalogId
        val loadMoreAddonId = row.addonId
        val loadMoreApiType = row.apiType
        val canObserveLoadMore = row.supportsSkip &&
            row.hasMore &&
            !loadMoreCatalogId.isNullOrBlank() &&
            !loadMoreAddonId.isNullOrBlank() &&
            !loadMoreApiType.isNullOrBlank()

        LaunchedEffect(row.key, pendingRowFocusNonce) {
            if (pendingRowFocusKey != row.key) return@LaunchedEffect
            val targetIndex = (pendingRowFocusIndex ?: 0)
                .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            val targetStableKey = "${row.key}_$targetIndex"
            val requester = uiCaches.requesterFor(row.key, targetStableKey)
            var didFocus = false
            var didScrollToTarget = false
            repeat(20) {
                didFocus = runCatching {
                    requester.requestFocus()
                    true
                }.getOrDefault(false)
                if (didFocus) {
                    return@repeat
                }
                if (!didScrollToTarget) {
                    runCatching { rowListState.scrollToItem(targetIndex) }
                    didScrollToTarget = true
                }
                withFrameNanos { }
            }
            if (!didFocus) {
                val fallbackIndex = rowListState.firstVisibleItemIndex
                    .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                val fallbackStableKey = "${row.key}_$fallbackIndex"
                didFocus = runCatching {
                    uiCaches.requesterFor(row.key, fallbackStableKey).requestFocus()
                    true
                }.getOrDefault(false)
            }
            if (didFocus) {
                onPendingRowFocusCleared()
            }
        }

        if (canObserveLoadMore) {
            LaunchedEffect(
                row.key,
                rowListState,
                canObserveLoadMore
            ) {
                snapshotFlow {
                    val layoutInfo = rowListState.layoutInfo
                    val total = layoutInfo.totalItemsCount
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible to total
                }
                    .distinctUntilChanged()
                    .collect { (lastVisible, total) ->
                        if (total <= 0) return@collect
                        val rowState = currentRowState.value
                        val isNearEnd = lastVisible >= total - 4
                        if (!isNearEnd) {
                            loadMoreRequestedTotals.remove(rowState.key)
                            return@collect
                        }
                        val lastRequestedTotal = loadMoreRequestedTotals[rowState.key]
                        if (rowState.hasMore &&
                            !rowState.isLoading &&
                            lastRequestedTotal != total
                        ) {
                            loadMoreRequestedTotals[rowState.key] = total
                            onLoadMoreCatalog(
                                loadMoreCatalogId,
                                loadMoreAddonId,
                                loadMoreApiType
                            )
                        }
                    }
            }
        }

        val density = LocalDensity.current
        val rowStartPadding = 52.dp
        val context = LocalContext.current
        val imageLoader = context.imageLoader

        val rowItemCount = row.items.size
        LaunchedEffect(
            row.key,
            isActiveRow,
            isVerticalRowsScrolling,
            rowItemCount,
            portraitCatalogCardWidth,
            portraitCatalogCardHeight,
            landscapeCatalogCardWidth,
            landscapeCatalogCardHeight,
            continueWatchingCardWidth,
            continueWatchingCardHeight
        ) {
            if (!isActiveRow || isVerticalRowsScrolling) return@LaunchedEffect
            delay(150) // Wait before spamming image requests to survive rapid vertical D-pad scrolls!
            val cwWidthPx = with(density) { continueWatchingCardWidth.roundToPx() }
            val cwHeightPx = with(density) { continueWatchingCardHeight.roundToPx() }
            fun imageUrlAndKey(item: ModernCarouselItem): Pair<String, String>? {
                val url = item.imageUrl ?: return null
                return when (item.payload) {
                    is ModernPayload.Catalog -> {
                        val metrics = item.catalogCardMetrics(
                            useLandscapePosters = useLandscapePosters,
                            portraitCardWidth = portraitCatalogCardWidth,
                            portraitCardHeight = portraitCatalogCardHeight,
                            landscapeCardWidth = landscapeCatalogCardWidth,
                            landscapeCardHeight = landscapeCatalogCardHeight
                        )
                        val widthPx = with(density) { metrics.width.roundToPx() }
                        val heightPx = with(density) { metrics.height.roundToPx() }
                        url to "${url}_${widthPx}x${heightPx}"
                    }
                    is ModernPayload.CollectionFolder -> {
                        val metrics = item.catalogCardMetrics(
                            useLandscapePosters = useLandscapePosters,
                            portraitCardWidth = portraitCatalogCardWidth,
                            portraitCardHeight = portraitCatalogCardHeight,
                            landscapeCardWidth = landscapeCatalogCardWidth,
                            landscapeCardHeight = landscapeCatalogCardHeight
                        )
                        val widthPx = with(density) { metrics.width.roundToPx() }
                        val heightPx = with(density) { metrics.height.roundToPx() }
                        url to "${url}_${widthPx}x${heightPx}"
                    }
                    is ModernPayload.ContinueWatching -> url to "${url}_${cwWidthPx}x${cwHeightPx}"
                }
            }
            fun enqueueIfNeeded(item: ModernCarouselItem, widthPx: Int, heightPx: Int) {
                val (url, cacheKey) = imageUrlAndKey(item) ?: return
                if (imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null) return
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .memoryCacheKey(cacheKey)
                        .size(width = widthPx, height = heightPx)
                        .build()
                )
            }
            // Prefetch initial visible + ahead items immediately when row appears
            val items = currentRowState.value.items
            for (i in 0 until minOf(POSTER_PREFETCH_DISTANCE, items.size)) {
                val item = items.getOrNull(i) ?: continue
                val (wPx, hPx) = when (item.payload) {
                    is ModernPayload.Catalog -> {
                        val metrics = item.catalogCardMetrics(
                            useLandscapePosters = useLandscapePosters,
                            portraitCardWidth = portraitCatalogCardWidth,
                            portraitCardHeight = portraitCatalogCardHeight,
                            landscapeCardWidth = landscapeCatalogCardWidth,
                            landscapeCardHeight = landscapeCatalogCardHeight
                        )
                        with(density) { metrics.width.roundToPx() } to with(density) { metrics.height.roundToPx() }
                    }
                    is ModernPayload.CollectionFolder -> {
                        val metrics = item.catalogCardMetrics(
                            useLandscapePosters = useLandscapePosters,
                            portraitCardWidth = portraitCatalogCardWidth,
                            portraitCardHeight = portraitCatalogCardHeight,
                            landscapeCardWidth = landscapeCatalogCardWidth,
                            landscapeCardHeight = landscapeCatalogCardHeight
                        )
                        with(density) { metrics.width.roundToPx() } to with(density) { metrics.height.roundToPx() }
                    }
                    is ModernPayload.ContinueWatching -> cwWidthPx to cwHeightPx
                }
                enqueueIfNeeded(item, wPx, hPx)
            }
            snapshotFlow {
                rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            }
                .distinctUntilChanged()
                .collect { lastVisibleIndex ->
                    val currentItems = currentRowState.value.items
                    for (i in (lastVisibleIndex + 1)..(lastVisibleIndex + POSTER_PREFETCH_DISTANCE)) {
                        val item = currentItems.getOrNull(i) ?: continue
                        val (wPx, hPx) = when (item.payload) {
                            is ModernPayload.Catalog -> {
                                val metrics = item.catalogCardMetrics(
                                    useLandscapePosters = useLandscapePosters,
                                    portraitCardWidth = portraitCatalogCardWidth,
                                    portraitCardHeight = portraitCatalogCardHeight,
                                    landscapeCardWidth = landscapeCatalogCardWidth,
                                    landscapeCardHeight = landscapeCatalogCardHeight
                                )
                                with(density) { metrics.width.roundToPx() } to with(density) { metrics.height.roundToPx() }
                            }
                            is ModernPayload.CollectionFolder -> {
                                val metrics = item.catalogCardMetrics(
                                    useLandscapePosters = useLandscapePosters,
                                    portraitCardWidth = portraitCatalogCardWidth,
                                    portraitCardHeight = portraitCatalogCardHeight,
                                    landscapeCardWidth = landscapeCatalogCardWidth,
                                    landscapeCardHeight = landscapeCatalogCardHeight
                                )
                                with(density) { metrics.width.roundToPx() } to with(density) { metrics.height.roundToPx() }
                            }
                            is ModernPayload.ContinueWatching -> cwWidthPx to cwHeightPx
                        }
                        enqueueIfNeeded(item, wPx, hPx)
                    }
                }
        }

        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, rowStartPadding) {
            val parentStartOffsetPx = with(density) { rowStartPadding.roundToPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float {
                    val childSize = abs(size)
                    val childSmallerThanParent = childSize <= containerSize
                    val initialTarget = parentStartOffsetPx.toFloat()
                    val spaceAvailable = containerSize - initialTarget

                    val targetForLeadingEdge =
                        if (childSmallerThanParent && spaceAvailable < childSize) {
                            containerSize - childSize
                        } else {
                            initialTarget
                        }

                    return offset - targetForLeadingEdge
                }
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            val lastFocusedIdx = focusedItemByRow[rowKey] ?: 0
            val restoreIdx = lastFocusedIdx.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            val restoreStableKey = "${row.key}_$restoreIdx"
            val restoreFocusRequester = uiCaches.requesterFor(rowKey, restoreStableKey)

            LazyRow(
                state = rowListState,
                modifier = Modifier
                    .focusRestorer(restoreFocusRequester)
                    .focusGroup()
                    .then(
                        if (row.isLoading) {
                            Modifier.onPreviewKeyEvent { event ->
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight
                            }
                        } else Modifier
                    ),
                contentPadding = PaddingValues(horizontal = rowStartPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = row.items,
                    key = { index, _ -> "${row.key}_$index" },
                    contentType = { _, item ->
                        when (val payload = item.payload) {
                            is ModernPayload.ContinueWatching -> "modern_cw_card"
                            is ModernPayload.Catalog -> payload.itemType
                            is ModernPayload.CollectionFolder -> "modern_collection_folder_card"
                        }
                    }
                ) { index, item ->
                    val stableItemKey = "${row.key}_$index"
                    val requester = uiCaches.requesterFor(row.key, stableItemKey)
                    val isContinueWatchingRow = row.key == MODERN_CONTINUE_WATCHING_ROW_KEY
                    val onFocused = remember(row.key, index, isContinueWatchingRow) {
                        { onRowItemFocused(row.key, index, isContinueWatchingRow) }
                    }
                    val isCwPayload = item.payload is ModernPayload.ContinueWatching

                    when (val payload = item.payload) {
                        is ModernPayload.ContinueWatching -> {
                            ModernContinueWatchingRowItem(
                                payload = payload,
                                requester = requester,
                                cardWidth = continueWatchingCardWidth,
                                imageHeight = continueWatchingCardHeight,
                                blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                                onFocused = onFocused,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onShowOptions = onContinueWatchingOptions
                            )
                        }

                        is ModernPayload.Catalog,
                        is ModernPayload.CollectionFolder -> {
                            val nextCatalogItem = row.items.getOrNull(index + 1)?.metaPreview
                            val metaPreview = item.metaPreview
                            val isWatched = metaPreview?.let(isCatalogItemWatched) ?: false
                            val onLongPress: () -> Unit = when {
                                payload is ModernPayload.Catalog && metaPreview != null -> remember(metaPreview, payload.addonBaseUrl) {
                                    {
                                        onCatalogItemLongPress(metaPreview, payload.addonBaseUrl)
                                        Unit
                                    }
                                }
                                else -> remember(Unit) { {} }
                            }
                            val expandedFocusKey = when (payload) {
                                is ModernPayload.Catalog -> payload.focusKey
                                is ModernPayload.CollectionFolder -> payload.focusKey
                                is ModernPayload.ContinueWatching -> null
                            }
                            ModernCatalogRowItem(
                                item = item,
                                payload = payload,
                                requester = requester,
                                useLandscapePosters = useLandscapePosters,
                                showLabels = showLabels,
                                posterCardCornerRadius = posterCardCornerRadius,
                                portraitCatalogCardWidth = portraitCatalogCardWidth,
                                portraitCatalogCardHeight = portraitCatalogCardHeight,
                                landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                                landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                                focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                                effectiveExpandEnabled = effectiveExpandEnabled,
                                effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                                trailerPlaybackTarget = trailerPlaybackTarget,
                                isBackdropExpanded = effectiveExpandEnabled && !isRowScrolling &&
                                    expandedCatalogFocusKey == expandedFocusKey,
                                expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                                expandedTrailerPreviewAudioUrl = expandedTrailerPreviewAudioUrl,
                                isWatched = isWatched,
                                onFocused = onFocused,
                                onItemFocus = onItemFocus,
                                onPreloadAdjacentItem = remember(nextCatalogItem, onPreloadAdjacentItem) {
                                     { nextCatalogItem?.let(onPreloadAdjacentItem) }
                                },
                                onCatalogSelectionFocused = onCatalogSelectionFocused,
                                onNavigateToDetail = onNavigateToDetail,
                                onNavigateToFolderDetail = onNavigateToFolderDetail,
                                onLongPress = onLongPress,
                                onBackdropInteraction = onBackdropInteraction,
                                onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                                enrichedLogoUrl = (payload as? ModernPayload.Catalog)?.itemId?.let { enrichedPreviews[it]?.logo },
                                enrichedBackdropUrl = (payload as? ModernPayload.Catalog)?.itemId?.let { enrichedPreviews[it]?.backdropUrl }
                            )
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModernCarouselCard(
    item: ModernCarouselItem,
    useLandscapeOverlayTreatment: Boolean,
    showLabels: Boolean,
    cardCornerRadius: Dp,
    cardWidth: Dp,
    cardHeight: Dp,
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    playTrailerInExpandedCard: Boolean,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrl: String?,
    trailerPreviewAudioUrl: String?,
    isWatched: Boolean,
    enrichedLogoUrl: String? = null,
    enrichedBackdropUrl: String? = null,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onTrailerEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = remember(cardCornerRadius) { RoundedCornerShape(cardCornerRadius) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val expandedCardWidth = cardHeight * (16f / 9f)
    val targetCardWidth = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        expandedCardWidth
    } else {
        cardWidth
    }
    val animatedCardWidthState = if (focusedPosterBackdropExpandEnabled) {
        animateDpAsState(
            targetValue = targetCardWidth,
            label = "modernCardWidth"
        )
    } else {
        rememberUpdatedState(cardWidth)
    }
    val animatedCardWidth by animatedCardWidthState
    // Freeze the logo URL for row cards - enrichment updates must not cause flickering.
    // The first non-blank value wins and is never replaced.
    // Primary source of truth is the data-layer frozen value (survives navigation);
    // the remember-state acts as a secondary guard within the same composition.
    val dataFrozenLogo = item.heroPreview.frozenLogoUrl
    val frozenLogoUrl = remember(item.key) { mutableStateOf(dataFrozenLogo ?: item.heroPreview.logo) }
    if (frozenLogoUrl.value.isNullOrBlank() && !item.heroPreview.logo.isNullOrBlank()) {
        frozenLogoUrl.value = item.heroPreview.logo
    }
    if (!useLandscapeOverlayTreatment && !enrichedLogoUrl.isNullOrBlank() && frozenLogoUrl.value != enrichedLogoUrl) {
        frozenLogoUrl.value = enrichedLogoUrl
    }
    val effectiveLogoUrl = frozenLogoUrl.value
    // Freeze the backdrop URL for landscape cards - prevents image reload when enrichment updates backdrop.
    val dataFrozenBackdrop = item.heroPreview.frozenBackdropUrl
    val frozenBackdropUrl = remember(item.key) { mutableStateOf(dataFrozenBackdrop ?: item.heroPreview.backdrop) }
    if (frozenBackdropUrl.value.isNullOrBlank() && !item.heroPreview.backdrop.isNullOrBlank()) {
        frozenBackdropUrl.value = item.heroPreview.backdrop
    }
    if (!useLandscapeOverlayTreatment && !enrichedBackdropUrl.isNullOrBlank() && frozenBackdropUrl.value != enrichedBackdropUrl) {
        frozenBackdropUrl.value = enrichedBackdropUrl
    }
    val effectiveBackdropUrl = frozenBackdropUrl.value
    var isFocused by remember { mutableStateOf(false) }
    val payload = item.payload as? ModernPayload.CollectionFolder
    val baseImageUrl = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
    } else if (useLandscapeOverlayTreatment) {
        effectiveBackdropUrl ?: item.heroPreview.poster
    } else {
        item.imageUrl ?: item.heroPreview.poster ?: item.heroPreview.backdrop
    }
    val imageUrl = when {
        payload == null -> baseImageUrl
        !payload.focusGifEnabled -> baseImageUrl
        isFocused -> payload.focusGifUrl ?: baseImageUrl
        else -> baseImageUrl ?: payload.focusGifUrl
    }
    val imageContentScale = when (item.payload) {
        is ModernPayload.CollectionFolder -> ContentScale.FillBounds
        else -> ContentScale.Crop
    }
    // Keep decode target stable across expand/collapse to avoid recreating image requests/painters
    // purely due to animated width changes.
    val maxRequestCardWidth = if (focusedPosterBackdropExpandEnabled) {
        maxOf(cardWidth, expandedCardWidth)
    } else {
        cardWidth
    }
    val requestWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { maxRequestCardWidth.roundToPx() }
    }
    val requestHeightPx = remember(cardHeight, density) {
        with(density) { cardHeight.roundToPx() }
    }
    val imageModel = remember(context, imageUrl, requestWidthPx, requestHeightPx) {
        imageUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .memoryCacheKey("${it}_${requestWidthPx}x${requestHeightPx}")
                .size(width = requestWidthPx, height = requestHeightPx)
                .build()
        }
    }
    val logoHeight = cardHeight * 0.34f
    val logoHeightPx = remember(logoHeight, density) {
        with(density) { logoHeight.roundToPx() }
    }
    val maxLogoWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { (maxRequestCardWidth * 0.62f).roundToPx() }
    }

    val logoModel = remember(context, effectiveLogoUrl, maxLogoWidthPx, logoHeightPx) {
        effectiveLogoUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .memoryCacheKey("${it}_${maxLogoWidthPx}x${logoHeightPx}")
                .size(width = maxLogoWidthPx, height = logoHeightPx)
                .build()
        }
    }
    var landscapeLogoLoadFailed by remember(effectiveLogoUrl) { mutableStateOf(false) }
    val shouldPlayTrailerInCard = playTrailerInExpandedCard && !trailerPreviewUrl.isNullOrBlank()
    val isVerticalRowsScrolling = LocalVerticalRowsScrolling.current

    // Coil 3's AsyncImage is skippable — it compares ImageRequest structurally and won't
    // re-trigger a failed memory-only request when policies change. We solve this by
    // building a restricted request during scroll and using Compose's `key()` on the
    // scroll state around AsyncImage so that stopping the scroll destroys the old
    // (memory-only) AsyncImage and creates a fresh one with the full request.
    val scrollAwareImageModel = if (!isVerticalRowsScrolling || imageModel == null) {
        imageModel
    } else {
        remember(imageModel) {
            (imageModel as? ImageRequest)?.newBuilder()
                ?.memoryCachePolicy(CachePolicy.ENABLED)
                ?.diskCachePolicy(CachePolicy.DISABLED)
                ?.networkCachePolicy(CachePolicy.DISABLED)
                ?.build()
                ?: imageModel
        }
    }
    // When true, wrap AsyncImage in key(scrollPhaseKey) to force re-creation on scroll stop.
    val scrollPhaseKey = isVerticalRowsScrolling
    val hasImage = !imageUrl.isNullOrBlank()
    val hasLandscapeLogo =
        (useLandscapeOverlayTreatment || isBackdropExpanded) &&
            !effectiveLogoUrl.isNullOrBlank() &&
            !landscapeLogoLoadFailed
    var longPressTriggered by remember { mutableStateOf(false) }
    val backgroundCardColor = NuvioColors.BackgroundCard
    val focusRingColor = NuvioColors.FocusRing
    val titleMedium = MaterialTheme.typography.titleMedium
    val backgroundPainter = remember(backgroundCardColor) { androidx.compose.ui.graphics.painter.ColorPainter(backgroundCardColor) }
    val focusedBorder = remember(cardShape, focusRingColor) {
        Border(
            border = BorderStroke(2.dp, focusRingColor),
            shape = cardShape
        )
    }
    // While the user is dragging the list via held DPAD (see LazyColumn-level fast
    // scroll takeover in ModernHomeContent), hide focus chrome entirely — the list is
    // sliding like a touch swipe and showing a border / glow jittering across every
    // card the drag passes over would break that illusion. The chrome reappears the
    // moment the user releases the key, when requestFocus lands focus on whichever
    // card is visible at the leading edge.
    val isFastScrolling = LocalFastScrollActive.current
    val transparentFocusBorder = remember(cardShape) {
        Border(
            border = BorderStroke(0.dp, Color.Transparent),
            shape = cardShape
        )
    }
    val effectiveFocusedBorder = if (isFastScrolling) transparentFocusBorder else focusedBorder
    val noFocusGlow = remember { CardDefaults.glow(focusedGlow = androidx.tv.material3.Glow.None) }
    val cardGlow = when (payload) {
        is ModernPayload.CollectionFolder -> rememberArtworkBackedCardGlow(
            imageUrl = imageUrl,
            fallbackSeed = "${item.title}:${payload.collectionTitle}",
            enabled = payload.focusGlowEnabled
        )
        else -> noFocusGlow
    }
    val effectiveCardGlow = if (isFastScrolling) noFocusGlow else cardGlow
    val titleStyle = remember(titleMedium) {
        titleMedium.copy(fontWeight = FontWeight.Medium)
    }

    Column(
        modifier = modifier.width(animatedCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            onClick = {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    onClick()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    onFocusStateChanged(it.isFocused)
                    if (it.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        if (focusedPosterBackdropExpandEnabled && shouldResetBackdropTimer(event.key)) {
                            onBackdropInteraction()
                        }
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                        val isLongPress = native.isLongPress || native.repeatCount > 0
                        if (isLongPress && isSelectKey(native.keyCode)) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (native.action == AndroidKeyEvent.ACTION_UP &&
                        longPressTriggered &&
                        isSelectKey(native.keyCode)
                    ) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            shape = CardDefaults.shape(cardShape),
            colors = CardDefaults.colors(
                containerColor = backgroundCardColor,
                focusedContainerColor = backgroundCardColor
            ),
            border = CardDefaults.border(focusedBorder = effectiveFocusedBorder),
            scale = CardDefaults.scale(focusedScale = 1f),
            glow = effectiveCardGlow
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val mediaLayerModifier = remember(hasLandscapeLogo) {
                    if (hasLandscapeLogo) {
                        Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(brush = MODERN_LANDSCAPE_LOGO_GRADIENT, size = size)
                                }
                            }
                    } else {
                        Modifier.fillMaxSize()
                    }
                }

                Box(modifier = mediaLayerModifier) {
                    val isPlaceholderItem = imageUrl?.startsWith("placeholder://") == true
                    if (isPlaceholderItem) {
                        // Horizontal sweeping shimmer for placeholder cards
                        val shimmerTransition = rememberInfiniteTransition(label = "placeholderShimmer")
                        val shimmerOffset by shimmerTransition.animateFloat(
                            initialValue = -1f,
                            targetValue = 2f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 1600, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "shimmerOffset"
                        )
                        val shimmerBrush = remember(shimmerOffset) {
                            Brush.linearGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.4f to Color.White.copy(alpha = 0.07f),
                                    0.5f to Color.White.copy(alpha = 0.13f),
                                    0.6f to Color.White.copy(alpha = 0.07f),
                                    1.0f to Color.Transparent
                                ),
                                start = Offset(shimmerOffset * 1000f, 0f),
                                end = Offset((shimmerOffset + 0.6f) * 1000f, 0f)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(shimmerBrush)
                        )
                    } else if (hasImage) {
                        key(scrollPhaseKey) {
                            AsyncImage(
                                model = scrollAwareImageModel,
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize(),
                                placeholder = backgroundPainter,
                                error = backgroundPainter,
                                fallback = backgroundPainter,
                                contentScale = imageContentScale
                            )
                        }
                    } else {
                        MonochromePosterPlaceholder()
                    }

                    if (shouldPlayTrailerInCard) {
                        TrailerPlayer(
                            trailerUrl = trailerPreviewUrl,
                            trailerAudioUrl = trailerPreviewAudioUrl,
                            isPlaying = true,
                            onEnded = onTrailerEnded,
                            muted = focusedPosterBackdropTrailerMuted,
                            cropToFill = true,
                            overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (hasLandscapeLogo) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = item.title,
                        onError = { landscapeLogoLoadFailed = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .height(cardHeight * 0.34f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else if (useLandscapeOverlayTreatment || isBackdropExpanded) {
                    Text(
                        text = item.title,
                        style = titleStyle,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 12.dp)
                    )
                }

                if (isWatched) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.episodes_cd_watched),
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 8.dp, top = 8.dp)
                            .zIndex(2f)
                            .size(21.dp)
                            .drawBehind {
                                drawCircle(
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    radius = size.minDimension / 2f + 1.5f
                                )
                            }
                    )
                }
            }
        }

        if (showLabels && !isBackdropExpanded && item.title.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    text = item.title,
                    style = titleStyle,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


private fun shouldResetBackdropTimer(key: Key): Boolean {
    return when (key) {
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionLeft,
        Key.DirectionRight,
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        Key.Back -> true
        else -> false
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
