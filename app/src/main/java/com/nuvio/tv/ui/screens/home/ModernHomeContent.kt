package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
private const val MODERN_HERO_TEXT_WIDTH_FRACTION = 0.42f
private const val MODERN_HERO_BACKDROP_HEIGHT_FRACTION = 0.62f
private const val MODERN_TRAILER_OVERSCAN_ZOOM = 1.35f
private const val MODERN_HERO_FOCUS_DEBOUNCE_MS = 90L

private data class HeroPreview(
    val title: String,
    val logo: String?,
    val description: String?,
    val contentTypeText: String?,
    val yearText: String?,
    val imdbText: String?,
    val genres: List<String>,
    val poster: String?,
    val backdrop: String?,
    val imageUrl: String?
)

private sealed class ModernPayload {
    data class ContinueWatching(val item: ContinueWatchingItem) : ModernPayload()
    data class Catalog(
        val itemId: String,
        val itemType: String,
        val addonBaseUrl: String,
        val trailerTitle: String,
        val trailerReleaseInfo: String?,
        val trailerApiType: String
    ) : ModernPayload()
}

private data class FocusedCatalogSelection(
    val focusKey: String,
    val payload: ModernPayload.Catalog
)

private data class ModernCarouselItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload
)

private data class HeroCarouselRow(
    val key: String,
    val title: String,
    val globalRowIndex: Int,
    val items: List<ModernCarouselItem>,
    val catalogId: String? = null,
    val addonId: String? = null,
    val apiType: String? = null,
    val supportsSkip: Boolean = false,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false
)

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ModernHomeContent(
    uiState: HomeUiState,
    focusState: HomeScreenFocusState,
    trailerPreviewUrls: Map<String, String>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRequestTrailerPreview: (String, String, String?, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit
) {
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val showNextRowPreview = uiState.modernNextRowPreviewEnabled
    val showCatalogTypeSuffixInModern = uiState.catalogTypeSuffixEnabled
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
    val visibleCatalogRows = remember(uiState.catalogRows) {
        uiState.catalogRows.filter { it.items.isNotEmpty() }
    }
    val carouselRows = remember(
        uiState.continueWatchingItems,
        visibleCatalogRows,
        useLandscapePosters,
        showCatalogTypeSuffixInModern
    ) {
        buildList {
            if (uiState.continueWatchingItems.isNotEmpty()) {
                add(
                    HeroCarouselRow(
                        key = "continue_watching",
                        title = "Continue Watching",
                        globalRowIndex = -1,
                        items = uiState.continueWatchingItems.map { item ->
                            buildContinueWatchingItem(
                                item = item,
                                useLandscapePosters = useLandscapePosters
                            )
                        }
                    )
                )
            }

            visibleCatalogRows.forEachIndexed { index, row ->
                val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                add(
                    HeroCarouselRow(
                        key = catalogRowKey(row),
                        title = catalogRowTitle(
                            row = row,
                            showCatalogTypeSuffix = showCatalogTypeSuffixInModern
                        ),
                        globalRowIndex = index,
                        catalogId = row.catalogId,
                        addonId = row.addonId,
                        apiType = row.apiType,
                        supportsSkip = row.supportsSkip,
                        hasMore = row.hasMore,
                        isLoading = row.isLoading,
                        items = row.items.map { item ->
                            val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                            rowItemOccurrenceCounts[item.id] = occurrence + 1
                            buildCatalogItem(
                                item = item,
                                row = row,
                                useLandscapePosters = useLandscapePosters,
                                occurrence = occurrence
                            )
                        }
                    )
                )
            }
        }
    }

    if (carouselRows.isEmpty()) return
    val rowIndexByKey = remember(carouselRows) {
        buildMap(carouselRows.size) {
            carouselRows.forEachIndexed { index, row ->
                put(row.key, index)
            }
        }
    }

    val focusedItemByRow = remember { mutableMapOf<String, Int>() }
    val itemFocusRequesters = remember { mutableMapOf<String, MutableMap<String, FocusRequester>>() }
    val rowListStates = remember { mutableMapOf<String, LazyListState>() }
    val loadMoreRequestedTotals = remember { mutableMapOf<String, Int>() }
    var activeRowKey by remember { mutableStateOf<String?>(null) }
    var activeItemIndex by remember { mutableIntStateOf(0) }
    var pendingRowFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingRowFocusIndex by remember { mutableStateOf<Int?>(null) }
    var heroItem by remember { mutableStateOf<HeroPreview?>(null) }
    var restoredFromSavedState by remember { mutableStateOf(false) }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    var lastFocusedContinueWatchingIndex by remember { mutableStateOf(-1) }
    var focusedCatalogSelection by remember { mutableStateOf<FocusedCatalogSelection?>(null) }
    var expandedCatalogFocusKey by remember { mutableStateOf<String?>(null) }
    var expansionInteractionNonce by remember { mutableIntStateOf(0) }

    fun requesterFor(rowKey: String, itemKey: String): FocusRequester {
        val byIndex = itemFocusRequesters.getOrPut(rowKey) { mutableMapOf() }
        return byIndex.getOrPut(itemKey) { FocusRequester() }
    }

    fun moveToRow(direction: Int): Boolean {
        val currentRowKey = activeRowKey ?: return false
        val currentIndex = rowIndexByKey[currentRowKey] ?: -1
        if (currentIndex < 0) return false
        val targetIndex = currentIndex + direction
        if (targetIndex !in carouselRows.indices) return false
        val targetRow = carouselRows[targetIndex]
        if (targetRow.items.isEmpty()) return false
        val currentItemIndex = if (activeRowKey == currentRowKey) {
            activeItemIndex
        } else {
            focusedItemByRow[currentRowKey] ?: 0
        }
        val rememberedTargetIndex = focusedItemByRow[targetRow.key]
        val targetItemIndex = (rememberedTargetIndex ?: currentItemIndex)
            .coerceIn(0, (targetRow.items.size - 1).coerceAtLeast(0))

        activeRowKey = targetRow.key
        activeItemIndex = targetItemIndex
        focusedItemByRow[targetRow.key] = targetItemIndex
        heroItem = targetRow.items.getOrNull(targetItemIndex)?.heroPreview
            ?: targetRow.items.firstOrNull()?.heroPreview
        pendingRowFocusKey = targetRow.key
        pendingRowFocusIndex = targetItemIndex
        focusedCatalogSelection = null
        expandedCatalogFocusKey = null
        return true
    }

    LaunchedEffect(
        focusedCatalogSelection?.focusKey,
        expansionInteractionNonce,
        shouldActivateFocusedPosterFlow,
        trailerPlaybackTarget,
        uiState.focusedPosterBackdropExpandDelaySeconds
    ) {
        expandedCatalogFocusKey = null
        if (!shouldActivateFocusedPosterFlow) return@LaunchedEffect
        val selection = focusedCatalogSelection ?: return@LaunchedEffect
        delay(uiState.focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(1) * 1000L)
        if (shouldActivateFocusedPosterFlow &&
            focusedCatalogSelection?.focusKey == selection.focusKey
        ) {
            expandedCatalogFocusKey = selection.focusKey
        }
    }

    LaunchedEffect(
        focusedCatalogSelection?.focusKey,
        effectiveAutoplayEnabled
    ) {
        if (!effectiveAutoplayEnabled) {
            return@LaunchedEffect
        }
        val selection = focusedCatalogSelection ?: return@LaunchedEffect
        onRequestTrailerPreview(
            selection.payload.itemId,
            selection.payload.trailerTitle,
            selection.payload.trailerReleaseInfo,
            selection.payload.trailerApiType
        )
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus, focusState.focusedRowIndex, focusState.focusedItemIndex) {
        val activeKeys = carouselRows.map { it.key }.toSet()
        focusedItemByRow.keys.retainAll(activeKeys)
        itemFocusRequesters.keys.retainAll(activeKeys)
        rowListStates.keys.retainAll(activeKeys)
        loadMoreRequestedTotals.keys.retainAll(activeKeys)
        carouselRows.forEach { row ->
            val rowRequesters = itemFocusRequesters[row.key] ?: return@forEach
            val activeItemKeys = row.items.mapTo(mutableSetOf()) { it.key }
            rowRequesters.keys.retainAll(activeItemKeys)
        }
        val activeCatalogItemIds = carouselRows
            .flatMap { row ->
                row.items.mapNotNull { item ->
                    (item.payload as? ModernPayload.Catalog)?.itemId
                }
            }
            .toSet()
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
                focusState.focusedRowIndex >= 0 -> visibleCatalogRows.getOrNull(focusState.focusedRowIndex)?.let { catalogRowKey(it) }
                else -> null
            }

            val resolvedRow = carouselRows.firstOrNull { it.key == savedRowKey } ?: carouselRows.first()
            val resolvedIndex = focusState.focusedItemIndex
                .coerceAtLeast(0)
                .coerceAtMost((resolvedRow.items.size - 1).coerceAtLeast(0))

            activeRowKey = resolvedRow.key
            activeItemIndex = resolvedIndex
            focusedItemByRow[resolvedRow.key] = resolvedIndex
            heroItem = resolvedRow.items.getOrNull(resolvedIndex)?.heroPreview
                ?: resolvedRow.items.firstOrNull()?.heroPreview
            pendingRowFocusKey = resolvedRow.key
            pendingRowFocusIndex = resolvedIndex
            restoredFromSavedState = true
            return@LaunchedEffect
        }

        val hadActiveRow = activeRowKey != null
        val existingActive = activeRowKey?.let { key -> carouselRows.firstOrNull { it.key == key } }
        val resolvedActive = existingActive ?: carouselRows.first()
        activeRowKey = resolvedActive.key
        val resolvedIndex = focusedItemByRow[resolvedActive.key]
            ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0))
            ?: 0
        activeItemIndex = resolvedIndex
        focusedItemByRow[resolvedActive.key] = resolvedIndex
        heroItem = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
            ?: resolvedActive.items.firstOrNull()?.heroPreview
        if (!focusState.hasSavedFocus && !hadActiveRow) {
            pendingRowFocusKey = resolvedActive.key
            pendingRowFocusIndex = resolvedIndex
        }
    }

    val rowByKey = remember(carouselRows) {
        carouselRows.associateBy { it.key }
    }
    val activeRow = remember(carouselRows, rowByKey, activeRowKey) {
        val activeKey = activeRowKey
        if (activeKey == null) {
            null
        } else {
            rowByKey[activeKey] ?: carouselRows.firstOrNull()
        }
    }
    val clampedActiveItemIndex = activeRow?.let { row ->
        activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
    } ?: 0

    LaunchedEffect(activeRow?.key, activeRow?.items?.size) {
        val row = activeRow ?: return@LaunchedEffect
        val clampedIndex = activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        if (activeItemIndex != clampedIndex) {
            activeItemIndex = clampedIndex
        }
        focusedItemByRow[row.key] = clampedIndex
    }

    LaunchedEffect(activeRow?.key, clampedActiveItemIndex) {
        val row = activeRow ?: return@LaunchedEffect
        val candidateHero =
            row.items.getOrNull(clampedActiveItemIndex)?.heroPreview ?: row.items.firstOrNull()?.heroPreview
        if (candidateHero == null || heroItem == candidateHero) return@LaunchedEffect
        delay(MODERN_HERO_FOCUS_DEBOUNCE_MS)
        val latestHero =
            row.items.getOrNull(clampedActiveItemIndex)?.heroPreview ?: row.items.firstOrNull()?.heroPreview
        if (latestHero != null && heroItem != latestHero) {
            heroItem = latestHero
        }
    }
    val nextRow = remember(carouselRows, activeRow?.key, rowIndexByKey) {
        val index = activeRow?.key?.let { key -> rowIndexByKey[key] ?: -1 } ?: -1
        if (index in carouselRows.indices && index + 1 < carouselRows.size) {
            carouselRows[index + 1]
        } else {
            null
        }
    }

    val latestActiveRow by rememberUpdatedState(activeRow)
    val latestActiveItemIndex by rememberUpdatedState(clampedActiveItemIndex)
    val latestCarouselRows by rememberUpdatedState(carouselRows)
    DisposableEffect(Unit) {
        onDispose {
            val row = latestActiveRow
            val focusedRowIndex = row?.globalRowIndex ?: 0
            val catalogRowScrollStates = latestCarouselRows
                .filter { it.globalRowIndex >= 0 }
                .associate { rowState -> rowState.key to (focusedItemByRow[rowState.key] ?: 0) }

            onSaveFocusState(
                0,
                0,
                focusedRowIndex,
                latestActiveItemIndex,
                catalogRowScrollStates
            )
        }
    }

    val portraitBaseWidth = uiState.posterCardWidthDp.dp
    val portraitBaseHeight = uiState.posterCardHeightDp.dp
    val modernPosterScale = if (useLandscapePosters) {
        if (showNextRowPreview) 1.18f else 1.34f
    } else {
        if (showNextRowPreview) 1.14f else 1.08f
    }
    val modernCatalogCardWidth = if (useLandscapePosters) {
        portraitBaseWidth * 1.24f * modernPosterScale
    } else {
        portraitBaseWidth * 0.84f * modernPosterScale
    }
    val modernCatalogCardHeight = if (useLandscapePosters) {
        modernCatalogCardWidth / 1.77f
    } else {
        portraitBaseHeight * 0.84f * modernPosterScale
    }
    val previewVisibleHeightFraction = if (useLandscapePosters) 0.18f else 0.15f

    @Composable
    fun ModernActiveRowContent(activeRowStateKey: String?, activeRowTitleBottom: Dp) {
        val row = activeRowStateKey?.let { rowByKey[it] }
        val continueWatchingScale = 1.34f
        val continueWatchingCardWidth = portraitBaseWidth * 1.24f * continueWatchingScale
        val continueWatchingCardHeight = continueWatchingCardWidth / 1.77f

        Column {
            Text(
                text = row?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioColors.TextPrimary,
                modifier = Modifier.padding(start = 52.dp, bottom = activeRowTitleBottom)
            )

            row?.let { resolvedRow ->
                val rowListState = rowListStates.getOrPut(resolvedRow.key) {
                    LazyListState(
                        firstVisibleItemIndex = focusState.catalogRowScrollStates[resolvedRow.key] ?: 0
                    )
                }
                val currentRowState = rememberUpdatedState(resolvedRow)

                LaunchedEffect(resolvedRow.key, pendingRowFocusKey, pendingRowFocusIndex) {
                    if (pendingRowFocusKey != resolvedRow.key) return@LaunchedEffect
                    val targetIndex = (pendingRowFocusIndex ?: 0)
                        .coerceIn(0, (resolvedRow.items.size - 1).coerceAtLeast(0))
                    val targetItemKey = resolvedRow.items.getOrNull(targetIndex)?.key ?: return@LaunchedEffect
                    val requester = requesterFor(resolvedRow.key, targetItemKey)
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
                            .coerceIn(0, (resolvedRow.items.size - 1).coerceAtLeast(0))
                        val fallbackItemKey = resolvedRow.items.getOrNull(fallbackIndex)?.key
                        didFocus = runCatching {
                            if (fallbackItemKey != null) {
                                requesterFor(resolvedRow.key, fallbackItemKey).requestFocus()
                            }
                            true
                        }.getOrDefault(false)
                    }
                    if (didFocus) {
                        pendingRowFocusKey = null
                        pendingRowFocusIndex = null
                    }
                }

                LaunchedEffect(
                    resolvedRow.key,
                    rowListState
                ) {
                    snapshotFlow {
                        val lastVisible = rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val total = rowListState.layoutInfo.totalItemsCount
                        lastVisible to total
                    }
                        .distinctUntilChanged()
                        .collect { (lastVisible, total) ->
                            if (total <= 0) return@collect
                            val rowState = currentRowState.value
                            val catalogId = rowState.catalogId
                            val addonId = rowState.addonId
                            val apiType = rowState.apiType
                            val isNearEnd = lastVisible >= total - 4
                            if (!isNearEnd) {
                                loadMoreRequestedTotals.remove(rowState.key)
                                return@collect
                            }
                            val lastRequestedTotal = loadMoreRequestedTotals[rowState.key]
                            if (rowState.supportsSkip &&
                                rowState.hasMore &&
                                !rowState.isLoading &&
                                !catalogId.isNullOrBlank() &&
                                !addonId.isNullOrBlank() &&
                                !apiType.isNullOrBlank() &&
                                lastRequestedTotal != total
                            ) {
                                loadMoreRequestedTotals[rowState.key] = total
                                onLoadMoreCatalog(catalogId, addonId, apiType)
                            }
                        }
                }

                LazyRow(
                    state = rowListState,
                    modifier = Modifier.focusRestorer {
                        val rememberedIndex = (focusedItemByRow[resolvedRow.key] ?: 0)
                            .coerceIn(0, (resolvedRow.items.size - 1).coerceAtLeast(0))
                        val fallbackIndex = rowListState.firstVisibleItemIndex
                            .coerceIn(0, (resolvedRow.items.size - 1).coerceAtLeast(0))
                        val restoreIndex = if (rememberedIndex in resolvedRow.items.indices) {
                            rememberedIndex
                        } else {
                            fallbackIndex
                        }
                        val itemKey = resolvedRow.items.getOrNull(restoreIndex)?.key ?: resolvedRow.items.first().key
                        requesterFor(resolvedRow.key, itemKey)
                    },
                    contentPadding = PaddingValues(horizontal = 52.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = resolvedRow.items,
                        key = { _, item -> item.key },
                        contentType = { _, item ->
                            when (item.payload) {
                                is ModernPayload.ContinueWatching -> "modern_cw_card"
                                is ModernPayload.Catalog -> "modern_catalog_card"
                            }
                        }
                    ) { index, item ->
                        val requester = requesterFor(resolvedRow.key, item.key)
                        val onFocused = {
                            val rowBecameActive = activeRowKey != resolvedRow.key
                            if (focusedItemByRow[resolvedRow.key] != index) {
                                focusedItemByRow[resolvedRow.key] = index
                            }
                            if (rowBecameActive) {
                                activeRowKey = resolvedRow.key
                            }
                            if (rowBecameActive || activeItemIndex != index) {
                                activeItemIndex = index
                            }
                            if (resolvedRow.key == "continue_watching") {
                                if (lastFocusedContinueWatchingIndex != index) {
                                    lastFocusedContinueWatchingIndex = index
                                }
                                if (focusedCatalogSelection != null) {
                                    focusedCatalogSelection = null
                                }
                            }
                        }
                        when (val payload = item.payload) {
                            is ModernPayload.ContinueWatching -> {
                                ContinueWatchingCard(
                                    item = payload.item,
                                    onClick = { onContinueWatchingClick(payload.item) },
                                    onLongPress = { optionsItem = payload.item },
                                    cardWidth = continueWatchingCardWidth,
                                    imageHeight = continueWatchingCardHeight,
                                    modifier = Modifier
                                        .focusRequester(requester)
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                onFocused()
                                            }
                                        }
                                        .onPreviewKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            when (event.key) {
                                                Key.DirectionUp -> moveToRow(-1)
                                                Key.DirectionDown -> moveToRow(1)
                                                else -> false
                                            }
                                        }
                                )
                            }

                            is ModernPayload.Catalog -> {
                                val focusKey = "${resolvedRow.key}::${payload.itemId}"
                                val suppressCardExpansionForHeroTrailer =
                                    effectiveAutoplayEnabled &&
                                        trailerPlaybackTarget ==
                                            FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
                                val isBackdropExpanded =
                                    effectiveExpandEnabled &&
                                        expandedCatalogFocusKey == focusKey &&
                                        !suppressCardExpansionForHeroTrailer
                                val playTrailerInExpandedCard =
                                    effectiveAutoplayEnabled &&
                                        trailerPlaybackTarget ==
                                            FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD &&
                                        isBackdropExpanded
                                val trailerPreviewUrl = if (playTrailerInExpandedCard) {
                                    trailerPreviewUrls[payload.itemId]
                                } else {
                                    null
                                }
                                ModernCarouselCard(
                                    item = item,
                                    useLandscapePosters = useLandscapePosters,
                                    showLabels = uiState.posterLabelsEnabled,
                                    cardCornerRadius = uiState.posterCardCornerRadiusDp.dp,
                                    cardWidth = modernCatalogCardWidth,
                                    cardHeight = modernCatalogCardHeight,
                                    focusedPosterBackdropExpandEnabled = effectiveExpandEnabled,
                                    isBackdropExpanded = isBackdropExpanded,
                                    playTrailerInExpandedCard = playTrailerInExpandedCard,
                                    focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                                    trailerPreviewUrl = trailerPreviewUrl,
                                    focusRequester = requester,
                                    onFocused = {
                                        onFocused()
                                        val nextSelection = FocusedCatalogSelection(
                                            focusKey = focusKey,
                                            payload = payload
                                        )
                                        if (focusedCatalogSelection != nextSelection) {
                                            focusedCatalogSelection = nextSelection
                                        }
                                    },
                                    onClick = {
                                        onNavigateToDetail(
                                            payload.itemId,
                                            payload.itemType,
                                            payload.addonBaseUrl
                                        )
                                    },
                                    onMoveUp = { moveToRow(-1) },
                                    onMoveDown = { moveToRow(1) },
                                    onBackdropInteraction = {
                                        expansionInteractionNonce++
                                    },
                                    onTrailerEnded = {
                                        expandedCatalogFocusKey = null
                                    }
                                )
                            }
                        }
                    }
                }

                if (showNextRowPreview) {
                    val rowIndex = rowIndexByKey[resolvedRow.key] ?: -1
                    val previewRow = carouselRows.getOrNull(rowIndex + 1)
                    val previewCardWidth = modernCatalogCardWidth
                    val previewCardHeight = modernCatalogCardHeight
                    ModernNextRowPreviewStrip(
                        previewRow = previewRow,
                        rowHorizontalPadding = 52.dp,
                        rowItemSpacing = 12.dp,
                        previewVisibleHeight = previewCardHeight * previewVisibleHeightFraction,
                        previewCardWidth = previewCardWidth,
                        previewCardHeight = previewCardHeight
                    )
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val rowHorizontalPadding = 52.dp

        val resolvedHero = heroItem
            ?: activeRow?.items?.getOrNull(clampedActiveItemIndex)?.heroPreview
            ?: activeRow?.items?.firstOrNull()?.heroPreview
        val fallbackBackdrop = remember(activeRow?.key, activeRow?.items) {
            activeRow?.items?.firstNotNullOfOrNull { item ->
                item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
            }
        }
        val heroBackdrop = resolvedHero?.backdrop?.takeIf { it.isNotBlank() } ?: fallbackBackdrop
        val expandedFocusedSelection = focusedCatalogSelection?.takeIf {
            it.focusKey == expandedCatalogFocusKey
        }
        val heroTrailerUrl = expandedFocusedSelection?.payload?.itemId?.let { trailerPreviewUrls[it] }
        val shouldPlayHeroTrailer = effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
            !heroTrailerUrl.isNullOrBlank()
        var heroTrailerFirstFrameRendered by remember(heroTrailerUrl) { mutableStateOf(false) }
        LaunchedEffect(shouldPlayHeroTrailer) {
            if (!shouldPlayHeroTrailer) {
                heroTrailerFirstFrameRendered = false
            }
        }
        val heroTransitionProgress by animateFloatAsState(
            targetValue = if (shouldPlayHeroTrailer && heroTrailerFirstFrameRendered) 1f else 0f,
            animationSpec = tween(durationMillis = 480),
            label = "heroBackdropTrailerCrossfadeProgress"
        )
        val heroBackdropAlpha = 1f - heroTransitionProgress
        val heroTrailerAlpha = heroTransitionProgress
        val shouldRenderPreviewRow = showNextRowPreview && nextRow != null
        val catalogBottomPadding = if (shouldRenderPreviewRow) 0.dp else 30.dp
        val heroToCatalogGap = if (shouldRenderPreviewRow) 16.dp else 28.dp
        val activeRowTitleBottom = if (shouldRenderPreviewRow) 10.dp else 14.dp
        val localDensity = LocalDensity.current
        val bgColor = NuvioColors.Background
        val heroMediaWidthPx = remember(maxWidth, localDensity) {
            with(localDensity) { (maxWidth * 0.75f).roundToPx() }
        }
        val heroMediaHeightPx = remember(maxHeight, localDensity) {
            with(localDensity) { (maxHeight * MODERN_HERO_BACKDROP_HEIGHT_FRACTION).roundToPx() }
        }

        val heroMediaModifier = Modifier
            .align(Alignment.TopEnd)
            .fillMaxWidth(0.75f)
            .fillMaxHeight(MODERN_HERO_BACKDROP_HEIGHT_FRACTION)

        ModernHeroMediaLayer(
            heroBackdrop = heroBackdrop,
            heroBackdropAlpha = heroBackdropAlpha,
            shouldPlayHeroTrailer = shouldPlayHeroTrailer,
            heroTrailerUrl = heroTrailerUrl,
            heroTrailerAlpha = heroTrailerAlpha,
            muted = uiState.focusedPosterBackdropTrailerMuted,
            bgColor = bgColor,
            onTrailerEnded = { expandedCatalogFocusKey = null },
            onFirstFrameRendered = { heroTrailerFirstFrameRendered = true },
            modifier = heroMediaModifier,
            requestWidthPx = heroMediaWidthPx,
            requestHeightPx = heroMediaHeightPx
        )
        val leftGradient = remember(bgColor) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to bgColor.copy(alpha = 0.96f),
                    0.18f to bgColor.copy(alpha = 0.86f),
                    0.31f to bgColor.copy(alpha = 0.70f),
                    0.40f to bgColor.copy(alpha = 0.55f),
                    0.48f to bgColor.copy(alpha = 0.38f),
                    0.56f to bgColor.copy(alpha = 0.22f),
                    0.66f to Color.Transparent,
                    1.0f to Color.Transparent
                )
            )
        }
        val bottomGradient = remember(bgColor) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.44f to Color.Transparent,
                    0.62f to bgColor.copy(alpha = 0.38f),
                    0.78f to bgColor.copy(alpha = 0.74f),
                    0.92f to bgColor.copy(alpha = 0.94f),
                    1.0f to bgColor.copy(alpha = 1.0f)
                )
            )
        }
        val dimColor = remember(bgColor) { bgColor.copy(alpha = 0.08f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(dimColor)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(leftGradient)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bottomGradient)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = catalogBottomPadding)
        ) {
            HeroTitleBlock(
                preview = resolvedHero,
                portraitMode = !useLandscapePosters,
                shouldRenderPreviewRow = shouldRenderPreviewRow,
                modifier = Modifier
                    .padding(start = rowHorizontalPadding, end = 48.dp, bottom = heroToCatalogGap)
                    .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
            )

            AnimatedContent(
                targetState = activeRow?.key,
                transitionSpec = {
                    val initialIndex = rowIndexByKey[initialState] ?: -1
                    val targetIndex = rowIndexByKey[targetState] ?: -1
                    val movingDown = targetIndex > initialIndex
                    (
                        slideInVertically(
                            animationSpec = tween(durationMillis = 240),
                            initialOffsetY = { fullHeight ->
                                if (movingDown) fullHeight / 4 else -fullHeight / 4
                            }
                        ) + fadeIn(animationSpec = tween(durationMillis = 200))
                    ) togetherWith (
                        slideOutVertically(
                            animationSpec = tween(durationMillis = 200),
                            targetOffsetY = { fullHeight ->
                                if (movingDown) -fullHeight / 4 else fullHeight / 4
                            }
                        ) + fadeOut(animationSpec = tween(durationMillis = 180))
                    )
                },
                label = "modernActiveRowContent"
            ) { activeRowStateKey ->
                ModernActiveRowContent(
                    activeRowStateKey = activeRowStateKey,
                    activeRowTitleBottom = activeRowTitleBottom
                )
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
                    minOf(lastFocusedContinueWatchingIndex, uiState.continueWatchingItems.size - 2)
                        .coerceAtLeast(0)
                }
                pendingRowFocusKey = if (targetIndex != null) "continue_watching" else null
                pendingRowFocusIndex = targetIndex
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
            }
        )
    }
}

private fun buildContinueWatchingItem(
    item: ContinueWatchingItem,
    useLandscapePosters: Boolean
): ModernCarouselItem {
    val heroPreview = when (item) {
        is ContinueWatchingItem.InProgress -> HeroPreview(
            title = item.progress.name,
            logo = item.progress.logo,
            description = item.episodeDescription ?: item.progress.episodeTitle,
            contentTypeText = item.progress.contentType.replaceFirstChar { ch -> ch.uppercase() },
            yearText = null,
            imdbText = null,
            genres = emptyList(),
            poster = item.progress.poster,
            backdrop = item.progress.backdrop,
            imageUrl = if (useLandscapePosters) {
                item.progress.backdrop ?: item.progress.poster
            } else {
                item.progress.poster ?: item.progress.backdrop
            }
        )
        is ContinueWatchingItem.NextUp -> HeroPreview(
            title = item.info.name,
            logo = item.info.logo,
            description = item.info.episodeDescription
                ?: item.info.episodeTitle
                ?: item.info.airDateLabel?.let { "Airs $it" },
            contentTypeText = item.info.contentType.replaceFirstChar { ch -> ch.uppercase() },
            yearText = null,
            imdbText = null,
            genres = emptyList(),
            poster = item.info.poster,
            backdrop = item.info.backdrop,
            imageUrl = if (useLandscapePosters) {
                firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
            } else {
                firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
            }
        )
    }

    val imageUrl = when (item) {
        is ContinueWatchingItem.InProgress -> if (useLandscapePosters) {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(item.episodeThumbnail, item.progress.poster, item.progress.backdrop)
            } else {
                firstNonBlank(item.progress.backdrop, item.progress.poster)
            }
        } else {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(heroPreview.poster, item.progress.poster, item.progress.backdrop)
            } else {
                firstNonBlank(item.progress.poster, item.progress.backdrop)
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            if (item.info.hasAired) {
                firstNonBlank(item.info.thumbnail, item.info.poster, item.info.backdrop)
            } else {
                firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
            }
        } else {
            firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
        }
    }

    return ModernCarouselItem(
        key = continueWatchingItemKey(item),
        title = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.name
            is ContinueWatchingItem.NextUp -> item.info.name
        },
        subtitle = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.episodeDisplayString ?: item.progress.episodeTitle
            is ContinueWatchingItem.NextUp -> {
                val code = "S${item.info.season}E${item.info.episode}"
                if (item.info.hasAired) {
                    code
                } else {
                    item.info.airDateLabel?.let { "$code • Airs $it" } ?: "$code • Upcoming"
                }
            }
        },
        imageUrl = imageUrl,
        heroPreview = heroPreview.copy(imageUrl = imageUrl ?: heroPreview.imageUrl),
        payload = ModernPayload.ContinueWatching(item)
    )
}

private fun buildCatalogItem(
    item: MetaPreview,
    row: CatalogRow,
    useLandscapePosters: Boolean,
    occurrence: Int
): ModernCarouselItem {
    val heroPreview = HeroPreview(
        title = item.name,
        logo = item.logo,
        description = item.description,
        contentTypeText = item.apiType.replaceFirstChar { ch -> ch.uppercase() },
        yearText = extractYear(item.releaseInfo),
        imdbText = item.imdbRating?.let { String.format("%.1f", it) },
        genres = item.genres.take(3),
        poster = item.poster,
        backdrop = item.background,
        imageUrl = if (useLandscapePosters) {
            item.background ?: item.poster
        } else {
            item.poster ?: item.background
        }
    )

    return ModernCarouselItem(
        key = "catalog_${row.key()}_${item.id}_${occurrence}",
        title = item.name,
        subtitle = item.releaseInfo,
        imageUrl = if (useLandscapePosters) {
            item.background ?: item.poster
        } else {
            item.poster ?: item.background
        },
        heroPreview = heroPreview,
        payload = ModernPayload.Catalog(
            itemId = item.id,
            itemType = item.type.toApiString(),
            addonBaseUrl = row.addonBaseUrl,
            trailerTitle = item.name,
            trailerReleaseInfo = item.releaseInfo,
            trailerApiType = item.apiType
        )
    )
}

private fun continueWatchingItemKey(item: ContinueWatchingItem): String {
    return when (item) {
        is ContinueWatchingItem.InProgress ->
            "cw_inprogress_${item.progress.contentId}_${item.progress.videoId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
        is ContinueWatchingItem.NextUp ->
            "cw_nextup_${item.info.contentId}_${item.info.videoId}_${item.info.season}_${item.info.episode}"
    }
}

private fun catalogRowKey(row: CatalogRow): String {
    return "${row.addonId}_${row.apiType}_${row.catalogId}"
}

private fun catalogRowTitle(
    row: CatalogRow,
    showCatalogTypeSuffix: Boolean
): String {
    val catalogName = row.catalogName.replaceFirstChar { it.uppercase() }
    return if (showCatalogTypeSuffix) {
        "$catalogName - ${row.apiType.replaceFirstChar { it.uppercase() }}"
    } else {
        catalogName
    }
}

private fun CatalogRow.key(): String {
    return "${addonId}_${apiType}_${catalogId}"
}

private fun isSeriesType(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

private fun extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return YEAR_REGEX.find(releaseInfo)?.value
}

private fun ContinueWatchingItem.contentId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentId
        is ContinueWatchingItem.NextUp -> info.contentId
    }
}

private fun ContinueWatchingItem.contentType(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentType
        is ContinueWatchingItem.NextUp -> info.contentType
    }
}

private fun ContinueWatchingItem.season(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.season
        is ContinueWatchingItem.NextUp -> info.season
    }
}

private fun ContinueWatchingItem.episode(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.episode
        is ContinueWatchingItem.NextUp -> info.episode
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModernCarouselCard(
    item: ModernCarouselItem,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    cardCornerRadius: Dp,
    cardWidth: Dp,
    cardHeight: Dp,
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    playTrailerInExpandedCard: Boolean,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrl: String?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Boolean,
    onMoveDown: () -> Boolean,
    onBackdropInteraction: () -> Unit,
    onTrailerEnded: () -> Unit
) {
    val cardShape = RoundedCornerShape(cardCornerRadius)
    val context = LocalContext.current
    val density = LocalDensity.current
    val landscapeLogoGradient = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.58f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.75f)
            )
        )
    }
    val expandedCardWidth = cardHeight * (16f / 9f)
    val targetCardWidth = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        expandedCardWidth
    } else {
        cardWidth
    }
    val animatedCardWidth by if (focusedPosterBackdropExpandEnabled) {
        animateDpAsState(
            targetValue = targetCardWidth,
            label = "modernCardWidth"
        )
    } else {
        rememberUpdatedState(cardWidth)
    }
    val imageUrl = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
    } else {
        item.imageUrl ?: item.heroPreview.poster ?: item.heroPreview.backdrop
    }
    val requestWidthPx = remember(targetCardWidth, density) {
        with(density) { targetCardWidth.roundToPx() }
    }
    val requestHeightPx = remember(cardHeight, density) {
        with(density) { cardHeight.roundToPx() }
    }
    val imageModel = remember(context, imageUrl, requestWidthPx, requestHeightPx) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(false)
            .size(width = requestWidthPx, height = requestHeightPx)
            .build()
    }
    val logoHeight = cardHeight * 0.34f
    val logoHeightPx = remember(logoHeight, density) {
        with(density) { logoHeight.roundToPx() }
    }
    val logoModel = remember(context, item.heroPreview.logo, requestWidthPx, logoHeightPx) {
        item.heroPreview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .size(width = requestWidthPx, height = logoHeightPx)
                .build()
        }
    }
    val shouldPlayTrailerInCard = playTrailerInExpandedCard && !trailerPreviewUrl.isNullOrBlank()

    Column(
        modifier = Modifier.width(animatedCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (focusedPosterBackdropExpandEnabled && shouldResetBackdropTimer(event.key)) {
                        onBackdropInteraction()
                    }
                    when (event.key) {
                        Key.DirectionUp -> onMoveUp()
                        Key.DirectionDown -> onMoveDown()
                        else -> false
                    }
                },
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.BackgroundCard
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = cardShape
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                if (shouldPlayTrailerInCard) {
                    TrailerPlayer(
                        trailerUrl = trailerPreviewUrl,
                        isPlaying = true,
                        onEnded = onTrailerEnded,
                        muted = focusedPosterBackdropTrailerMuted,
                        cropToFill = true,
                        overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (useLandscapePosters && !item.heroPreview.logo.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(landscapeLogoGradient)
                    )
                    AsyncImage(
                        model = logoModel,
                        contentDescription = item.title,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .height(cardHeight * 0.34f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
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
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
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

@Composable
private fun ModernHeroMediaLayer(
    heroBackdrop: String?,
    heroBackdropAlpha: Float,
    shouldPlayHeroTrailer: Boolean,
    heroTrailerUrl: String?,
    heroTrailerAlpha: Float,
    muted: Boolean,
    bgColor: Color,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int
) {
    val localContext = LocalContext.current
    val verticalOverlayGradient = remember(bgColor) {
        Brush.verticalGradient(
            0.78f to Color.Transparent,
            0.90f to bgColor.copy(alpha = 0.72f),
            0.96f to bgColor.copy(alpha = 0.98f),
            1.0f to bgColor
        )
    }
    Box(modifier = modifier) {
        Crossfade(
            targetState = heroBackdrop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = heroBackdropAlpha },
            animationSpec = tween(durationMillis = 350),
            label = "modernHeroBackground"
        ) { imageUrl ->
            val imageModel = remember(localContext, imageUrl, requestWidthPx, requestHeightPx) {
                ImageRequest.Builder(localContext)
                    .data(imageUrl)
                    .crossfade(false)
                    .size(width = requestWidthPx, height = requestHeightPx)
                    .build()
            }
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }

        if (shouldPlayHeroTrailer) {
            TrailerPlayer(
                trailerUrl = heroTrailerUrl,
                isPlaying = true,
                onEnded = onTrailerEnded,
                onFirstFrameRendered = onFirstFrameRendered,
                muted = muted,
                cropToFill = true,
                overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = heroTrailerAlpha }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val horizontalGradient = Brush.horizontalGradient(
                        0.0f to bgColor.copy(alpha = 0.96f),
                        0.10f to bgColor.copy(alpha = 0.72f),
                        0.30f to Color.Transparent
                    )
                    val radialGradient = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to bgColor.copy(alpha = 0.78f),
                            0.55f to bgColor.copy(alpha = 0.52f),
                            0.80f to bgColor.copy(alpha = 0.16f),
                            1.0f to Color.Transparent
                        ),
                        center = Offset(0f, size.height / 2f),
                        radius = size.height
                    )
                    onDrawBehind {
                        drawRect(brush = horizontalGradient, size = size)
                        drawRect(brush = radialGradient, size = size)
                    }
                }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(verticalOverlayGradient)
        )
    }
}

@Composable
private fun PreviewCarouselCard(
    imageUrl: String?,
    cardWidth: Dp,
    cardHeight: Dp
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val requestWidthPx = remember(cardWidth, density) {
        with(density) { cardWidth.roundToPx() }
    }
    val requestHeightPx = remember(cardHeight, density) {
        with(density) { cardHeight.roundToPx() }
    }
    val imageModel = remember(context, imageUrl, requestWidthPx, requestHeightPx) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(false)
            .size(width = requestWidthPx, height = requestHeightPx)
            .build()
    }
    Box(
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .clip(RoundedCornerShape(10.dp))
    ) {
        AsyncImage(
            model = imageModel,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.36f))
        )
    }
}

@Composable
private fun ModernNextRowPreviewStrip(
    previewRow: HeroCarouselRow?,
    rowHorizontalPadding: Dp,
    rowItemSpacing: Dp,
    previewVisibleHeight: Dp,
    previewCardWidth: Dp,
    previewCardHeight: Dp
) {
    if (previewRow == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Text(
            text = previewRow.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(start = rowHorizontalPadding, bottom = 10.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewVisibleHeight)
                .clipToBounds()
        ) {
            LazyRow(
                userScrollEnabled = false,
                contentPadding = PaddingValues(horizontal = rowHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(rowItemSpacing)
            ) {
                itemsIndexed(
                    previewRow.items.take(12),
                    key = { _, item -> item.key },
                    contentType = { _, _ -> "modern_preview_card" }
                ) { _, item ->
                    PreviewCarouselCard(
                        imageUrl = item.imageUrl ?: item.heroPreview.poster ?: item.heroPreview.backdrop,
                        cardWidth = previewCardWidth,
                        cardHeight = previewCardHeight
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

@Composable
private fun HeroTitleBlock(
    preview: HeroPreview?,
    portraitMode: Boolean,
    shouldRenderPreviewRow: Boolean,
    modifier: Modifier = Modifier
) {
    if (preview == null) return

    val descriptionMaxLines = if (portraitMode) 4 else 5
    val descriptionScale = if (portraitMode) 0.90f else 1f
    val titleScale = if (portraitMode) 0.92f else 1f
    val metaScale = if (portraitMode && shouldRenderPreviewRow) 0.90f else 1f
    val context = LocalContext.current
    val density = LocalDensity.current
    val logoMaxWidthPx = remember(density) { with(density) { 220.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 100.dp.roundToPx() } }
    val logoModel = remember(context, preview.logo, logoMaxWidthPx, logoHeightPx) {
        preview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .size(width = logoMaxWidthPx, height = logoHeightPx)
                .build()
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy((8.dp * titleScale))
    ) {
        if (!preview.logo.isNullOrBlank()) {
            AsyncImage(
                model = logoModel,
                contentDescription = preview.title,
                modifier = Modifier
                    .height(100.dp)
                    .widthIn(min = 100.dp, max = 220.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else {
            Text(
                text = preview.title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = MaterialTheme.typography.headlineLarge.fontSize * titleScale,
                    lineHeight = MaterialTheme.typography.headlineLarge.lineHeight * titleScale
                ),
                color = NuvioColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp * metaScale)
        ) {
            var hasLeadingMeta = false

            preview.contentTypeText?.takeIf { it.isNotBlank() }?.let { contentType ->
                Text(
                    text = contentType,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                hasLeadingMeta = true
            }

            preview.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { genre ->
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Text(
                    text = genre,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                hasLeadingMeta = true
            }

            val yearText = preview.yearText
            val imdbText = preview.imdbText
            val hasYearOrImdb = !yearText.isNullOrBlank() || !imdbText.isNullOrBlank()
            if (hasYearOrImdb) {
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp * metaScale)
                ) {
                    if (!yearText.isNullOrBlank()) {
                        Text(
                            text = yearText,
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (!imdbText.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp * metaScale)
                        ) {
                            val imdbModel = remember {
                                ImageRequest.Builder(context)
                                    .data(com.nuvio.tv.R.raw.imdb_logo_2016)
                                    .decoderFactory(SvgDecoder.Factory())
                                    .build()
                            }
                            AsyncImage(
                                model = imdbModel,
                                contentDescription = "IMDb",
                                modifier = Modifier.size(30.dp * metaScale),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                text = imdbText,
                                style = MaterialTheme.typography.labelMedium,
                                color = NuvioColors.TextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
                hasLeadingMeta = true
            }
        }

        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize * descriptionScale,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * descriptionScale
                ),
                color = NuvioColors.TextPrimary,
                maxLines = descriptionMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeroMetaDivider(scale: Float) {
    Box(
        modifier = Modifier
            .size((4.dp * scale).coerceAtLeast(2.dp))
            .clip(RoundedCornerShape(percent = 50))
            .background(NuvioColors.TextTertiary.copy(alpha = 0.78f))
    )
}
