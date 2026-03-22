@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.focusGroup
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
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

private const val MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS = 140L
private const val POSTER_PREFETCH_DISTANCE = 8

internal val LocalVerticalRowsScrolling = androidx.compose.runtime.compositionLocalOf { false }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernContinueWatchingRowItem(
    payload: ModernPayload.ContinueWatching,
    requester: FocusRequester,
    cardWidth: Dp,
    imageHeight: Dp,
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
    payload: ModernPayload.Catalog,
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
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusKey = payload.focusKey
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
        latestOnCatalogSelectionFocused(
            FocusedCatalogSelection(
                focusKey = focusKey,
                payload = payload
            )
        )
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
        focusRequester = requester,
        onFocused = {
            focusEventId += 1
        },
        onFocusStateChanged = { focused ->
            isCardFocused = focused
        },
        onClick = {
            onNavigateToDetail(
                payload.itemId,
                payload.itemType,
                payload.addonBaseUrl
            )
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
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit
) {
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val rowListStates = uiCaches.rowListStates
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals

    val rowKey = row.key
    Column {
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
                prefetchStrategy = LazyListPrefetchStrategy(nestedPrefetchItemCount = 2)
            )
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
            val targetItemKey = row.items.getOrNull(targetIndex)?.key ?: return@LaunchedEffect
            val requester = uiCaches.requesterFor(row.key, targetItemKey)
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
                val fallbackItemKey = row.items.getOrNull(fallbackIndex)?.key
                didFocus = runCatching {
                    if (fallbackItemKey != null) {
                        uiCaches.requesterFor(row.key, fallbackItemKey).requestFocus()
                    }
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
            rowItemCount,
            portraitCatalogCardWidth,
            portraitCatalogCardHeight,
            landscapeCatalogCardWidth,
            landscapeCatalogCardHeight,
            continueWatchingCardWidth,
            continueWatchingCardHeight
        ) {
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
            LazyRow(
                state = rowListState,
                modifier = Modifier.focusRestorer().focusGroup(),
                contentPadding = PaddingValues(horizontal = rowStartPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = row.items,
                    key = { _, item -> item.key },
                    contentType = { _, item ->
                        when (item.payload) {
                            is ModernPayload.ContinueWatching -> "modern_cw_card"
                            is ModernPayload.Catalog -> "modern_catalog_card"
                        }
                    }
                ) { index, item ->
                    val requester = uiCaches.requesterFor(row.key, item.key)
                    val isContinueWatchingRow = row.key == "continue_watching"
                    val onFocused = remember(row.key, index, isContinueWatchingRow) {
                        { onRowItemFocused(row.key, index, isContinueWatchingRow) }
                    }

                    when (val payload = item.payload) {
                        is ModernPayload.ContinueWatching -> {
                            ModernContinueWatchingRowItem(
                                payload = payload,
                                requester = requester,
                                cardWidth = continueWatchingCardWidth,
                                imageHeight = continueWatchingCardHeight,
                                onFocused = onFocused,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onShowOptions = onContinueWatchingOptions
                            )
                        }

                        is ModernPayload.Catalog -> {
                            val nextCatalogItem = row.items.getOrNull(index + 1)?.metaPreview
                            val metaPreview = item.metaPreview ?: return@itemsIndexed
                            val isWatched = isCatalogItemWatched(metaPreview)
                            val onLongPress: () -> Unit = remember(metaPreview, payload.addonBaseUrl) {
                                {
                                    onCatalogItemLongPress(metaPreview, payload.addonBaseUrl)
                                    Unit
                                }
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
                                    expandedCatalogFocusKey == payload.focusKey,
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
                                onLongPress = onLongPress,
                                onBackdropInteraction = onBackdropInteraction,
                                onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange
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
    val imageUrl = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
    } else {
        item.imageUrl ?: item.heroPreview.poster ?: item.heroPreview.backdrop
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
                .crossfade(false)
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
    // Freeze the logo URL for row cards - enrichment updates must not cause flickering.
    // The first non-blank value wins and is never replaced.
    val frozenLogoUrl = remember(item.key) { mutableStateOf(item.heroPreview.logo) }
    if (frozenLogoUrl.value.isNullOrBlank() && !item.heroPreview.logo.isNullOrBlank()) {
        frozenLogoUrl.value = item.heroPreview.logo
    }
    val effectiveLogoUrl = frozenLogoUrl.value

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
    val imageCacheKey = "${imageUrl}_${requestWidthPx}x${requestHeightPx}"
    val isImageCached = remember(imageModel) {
        context.imageLoader.memoryCache?.get(MemoryCache.Key(imageCacheKey)) != null
    }

    val safeImageModel = if (isVerticalRowsScrolling && !isImageCached) null else imageModel
    val hasImage = !imageUrl.isNullOrBlank() && safeImageModel != null
    val hasLandscapeLogo =
        (useLandscapeOverlayTreatment || isBackdropExpanded) &&
            !effectiveLogoUrl.isNullOrBlank() &&
            !landscapeLogoLoadFailed
    var isFocused by remember { mutableStateOf(false) }
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
    val titleStyle = remember(titleMedium) {
        titleMedium.copy(fontWeight = FontWeight.Medium)
    }

    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Column(
        modifier = modifier.width(animatedCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
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
                }
                .clip(cardShape)
                .border(if (isFocused) focusedBorder.border else BorderStroke(0.dp, Color.Transparent), cardShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        if (longPressTriggered) {
                            longPressTriggered = false
                        } else {
                            onClick()
                        }
                    }
                )
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
                    if (hasImage) {
                        AsyncImage(
                            model = safeImageModel,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            placeholder = backgroundPainter,
                            error = backgroundPainter,
                            fallback = backgroundPainter,
                            contentScale = ContentScale.Crop
                        )
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

        if (showLabels && !isBackdropExpanded) {
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
