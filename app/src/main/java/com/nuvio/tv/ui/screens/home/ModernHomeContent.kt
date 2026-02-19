package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.flow.distinctUntilChanged

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
private const val MODERN_HERO_TEXT_WIDTH_FRACTION = 0.42f
private const val MODERN_HERO_BACKDROP_HEIGHT_FRACTION = 0.72f

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
        val addonBaseUrl: String
    ) : ModernPayload()
}

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
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit
) {
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val showNextRowPreview = uiState.modernNextRowPreviewEnabled
    val showCatalogTypeSuffixInModern = uiState.catalogTypeSuffixEnabled
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
                        items = uiState.continueWatchingItems.mapIndexed { index, item ->
                            buildContinueWatchingItem(
                                index = index,
                                item = item,
                                useLandscapePosters = useLandscapePosters
                            )
                        }
                    )
                )
            }

            visibleCatalogRows.forEachIndexed { index, row ->
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
                        items = row.items.mapIndexed { itemIndex, item ->
                            buildCatalogItem(
                                index = itemIndex,
                                item = item,
                                row = row,
                                useLandscapePosters = useLandscapePosters
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

    val focusedItemByRow = remember { mutableStateMapOf<String, Int>() }
    val itemFocusRequesters = remember { mutableMapOf<String, MutableMap<Int, FocusRequester>>() }
    val rowListStates = remember { mutableMapOf<String, LazyListState>() }
    var activeRowKey by remember { mutableStateOf<String?>(null) }
    var pendingRowFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingRowFocusIndex by remember { mutableStateOf<Int?>(null) }
    var heroItem by remember { mutableStateOf<HeroPreview?>(null) }
    var restoredFromSavedState by remember { mutableStateOf(false) }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    var lastFocusedContinueWatchingIndex by remember { mutableStateOf(-1) }

    fun requesterFor(rowKey: String, index: Int): FocusRequester {
        val byIndex = itemFocusRequesters.getOrPut(rowKey) { mutableMapOf() }
        return byIndex.getOrPut(index) { FocusRequester() }
    }

    fun moveToRow(direction: Int): Boolean {
        val currentRowKey = activeRowKey ?: return false
        val currentIndex = rowIndexByKey[currentRowKey] ?: -1
        if (currentIndex < 0) return false
        val targetIndex = currentIndex + direction
        if (targetIndex !in carouselRows.indices) return false
        val targetRow = carouselRows[targetIndex]
        if (targetRow.items.isEmpty()) return false
        val currentItemIndex = focusedItemByRow[currentRowKey] ?: 0
        val rememberedTargetIndex = focusedItemByRow[targetRow.key]
        val targetItemIndex = (rememberedTargetIndex ?: currentItemIndex)
            .coerceIn(0, (targetRow.items.size - 1).coerceAtLeast(0))

        activeRowKey = targetRow.key
        focusedItemByRow[targetRow.key] = targetItemIndex
        heroItem = targetRow.items.getOrNull(targetItemIndex)?.heroPreview
            ?: targetRow.items.firstOrNull()?.heroPreview
        pendingRowFocusKey = targetRow.key
        pendingRowFocusIndex = targetItemIndex
        return true
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus, focusState.focusedRowIndex, focusState.focusedItemIndex) {
        val activeKeys = carouselRows.map { it.key }.toSet()
        focusedItemByRow.keys.retainAll(activeKeys)
        itemFocusRequesters.keys.retainAll(activeKeys)
        rowListStates.keys.retainAll(activeKeys)

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
        focusedItemByRow[resolvedActive.key] = resolvedIndex
        heroItem = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
            ?: resolvedActive.items.firstOrNull()?.heroPreview
        if (!focusState.hasSavedFocus && !hadActiveRow) {
            pendingRowFocusKey = resolvedActive.key
            pendingRowFocusIndex = resolvedIndex
        }
    }

    val activeRow = remember(carouselRows, activeRowKey) {
        val activeKey = activeRowKey
        if (activeKey == null) {
            null
        } else {
            carouselRows.firstOrNull { it.key == activeKey } ?: carouselRows.firstOrNull()
        }
    }
    val activeItemIndex = activeRow?.let { row ->
        focusedItemByRow[row.key]?.coerceIn(0, (row.items.size - 1).coerceAtLeast(0)) ?: 0
    } ?: 0
    val nextRow = remember(carouselRows, activeRow?.key, rowIndexByKey) {
        val index = activeRow?.key?.let { key -> rowIndexByKey[key] ?: -1 } ?: -1
        if (index in carouselRows.indices && index + 1 < carouselRows.size) {
            carouselRows[index + 1]
        } else {
            null
        }
    }

    DisposableEffect(activeRow?.key, activeItemIndex, carouselRows) {
        onDispose {
            val row = activeRow
            val focusedRowIndex = row?.globalRowIndex ?: 0
            val catalogRowScrollStates = carouselRows
                .filter { it.globalRowIndex >= 0 }
                .associate { rowState -> rowState.key to (focusedItemByRow[rowState.key] ?: 0) }

            onSaveFocusState(
                0,
                0,
                focusedRowIndex,
                activeItemIndex,
                catalogRowScrollStates
            )
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val previewRowEnabled = showNextRowPreview
        val posterScale = when {
            previewRowEnabled -> 1f
            useLandscapePosters -> 1.34f
            else -> 1.08f
        }
        val rowHorizontalPadding = 52.dp
        val rowItemSpacing = 12.dp
        val portraitBaseWidth = uiState.posterCardWidthDp.dp
        val portraitBaseHeight = uiState.posterCardHeightDp.dp
        val activeCardWidth = if (useLandscapePosters) {
            portraitBaseWidth * 1.24f * posterScale
        } else {
            portraitBaseWidth * 0.84f * posterScale
        }
        val activeCardHeight = if (useLandscapePosters) {
            activeCardWidth / 1.77f
        } else {
            portraitBaseHeight * 0.84f * posterScale
        }
        val previewCardWidth = activeCardWidth
        val previewCardHeight = activeCardHeight
        val continueWatchingScale = if (previewRowEnabled) 1f else 1.34f
        val continueWatchingCardWidth = portraitBaseWidth * 1.24f * continueWatchingScale
        val continueWatchingCardHeight = continueWatchingCardWidth / 1.77f
        val cardCornerRadius = uiState.posterCardCornerRadiusDp.dp
        val previewVisibleHeight = if (useLandscapePosters) {
            previewCardHeight * 0.30f
        } else {
            previewCardHeight * 0.22f
        }

        val resolvedHero = heroItem ?: activeRow?.items?.firstOrNull()?.heroPreview
        val fallbackBackdrop = remember(activeRow?.key, activeRow?.items) {
            activeRow?.items?.firstNotNullOfOrNull { item ->
                item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
            }
        }
        val heroBackdrop = resolvedHero?.backdrop?.takeIf { it.isNotBlank() } ?: fallbackBackdrop
        val shouldRenderPreviewRow = showNextRowPreview && nextRow != null
        val catalogBottomPadding = if (shouldRenderPreviewRow) 12.dp else 18.dp
        val heroToCatalogGap = if (shouldRenderPreviewRow) 14.dp else 18.dp
        val localContext = LocalContext.current
        val bgColor = NuvioColors.Background

        Crossfade(
            targetState = heroBackdrop,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.75f)
                .fillMaxHeight(MODERN_HERO_BACKDROP_HEIGHT_FRACTION),
            animationSpec = tween(durationMillis = 350),
            label = "modernHeroBackground"
        ) { imageUrl ->
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(localContext)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.TopEnd
                )
                // Left edge fade - rounded arc inward
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            // Strong solid cover at the left edge, then arc inward
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0.0f to bgColor.copy(alpha = 0.96f),
                                    0.10f to bgColor.copy(alpha = 0.72f),
                                    0.30f to Color.Transparent
                                ),
                                size = size
                            )
                            drawRect(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0.0f to bgColor.copy(alpha = 0.78f),
                                        0.55f to bgColor.copy(alpha = 0.52f),
                                        0.80f to bgColor.copy(alpha = 0.16f),
                                        1.0f to Color.Transparent
                                    ),
                                    center = Offset(0f, size.height / 2f),
                                    radius = size.height * 1.0f
                                ),
                                size = size
                            )
                        }
                )
                // Bottom edge fade
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.78f to Color.Transparent,
                                0.90f to bgColor.copy(alpha = 0.72f),
                                0.96f to bgColor.copy(alpha = 0.98f),
                                1.0f to bgColor
                            )
                        )
                )
            }
        }
        val leftGradient = remember(bgColor) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to bgColor.copy(alpha = 0.96f),
                    0.20f to bgColor.copy(alpha = 0.86f),
                    0.35f to bgColor.copy(alpha = 0.70f),
                    0.45f to bgColor.copy(alpha = 0.55f),
                    0.55f to bgColor.copy(alpha = 0.38f),
                    0.65f to bgColor.copy(alpha = 0.22f),
                    0.75f to Color.Transparent,
                    1.0f to Color.Transparent
                )
            )
        }
        val bottomGradient = remember(bgColor) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.38f to Color.Transparent,
                    0.56f to bgColor.copy(alpha = 0.38f),
                    0.72f to bgColor.copy(alpha = 0.74f),
                    0.86f to bgColor.copy(alpha = 0.94f),
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
                val row = carouselRows.firstOrNull { it.key == activeRowStateKey }

                Column {
                    Text(
                        text = row?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = NuvioColors.TextPrimary,
                        modifier = Modifier.padding(start = rowHorizontalPadding, bottom = 6.dp)
                    )

                    row?.let { resolvedRow ->
                        val rowListState = rowListStates.getOrPut(resolvedRow.key) {
                            LazyListState(
                                firstVisibleItemIndex = focusState.catalogRowScrollStates[resolvedRow.key] ?: 0
                            )
                        }

                        LaunchedEffect(resolvedRow.key, pendingRowFocusKey, pendingRowFocusIndex) {
                            if (pendingRowFocusKey != resolvedRow.key) return@LaunchedEffect
                            val targetIndex = (pendingRowFocusIndex ?: 0)
                                .coerceIn(0, (resolvedRow.items.size - 1).coerceAtLeast(0))
                            val requester = requesterFor(resolvedRow.key, targetIndex)
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
                                    val targetVisible = rowListState.layoutInfo.visibleItemsInfo
                                        .any { it.index == targetIndex }
                                    if (!targetVisible) {
                                        runCatching { rowListState.scrollToItem(targetIndex) }
                                        didScrollToTarget = true
                                    }
                                }
                                withFrameNanos { }
                            }
                            if (!didFocus) {
                                val fallbackIndex = rowListState.layoutInfo.visibleItemsInfo
                                    .firstOrNull()
                                    ?.index
                                    ?.coerceIn(0, (resolvedRow.items.size - 1).coerceAtLeast(0))
                                    ?: 0
                                didFocus = runCatching {
                                    requesterFor(resolvedRow.key, fallbackIndex).requestFocus()
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
                            rowListState,
                            resolvedRow.items.size,
                            resolvedRow.supportsSkip,
                            resolvedRow.hasMore,
                            resolvedRow.isLoading
                        ) {
                            snapshotFlow {
                                val lastVisible = rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                val total = rowListState.layoutInfo.totalItemsCount
                                lastVisible to total
                            }
                                .distinctUntilChanged()
                                .collect { (lastVisible, total) ->
                                    if (total <= 0) return@collect
                                    val catalogId = resolvedRow.catalogId
                                    val addonId = resolvedRow.addonId
                                    val apiType = resolvedRow.apiType
                                    if (lastVisible >= total - 4 &&
                                        resolvedRow.supportsSkip &&
                                        resolvedRow.hasMore &&
                                        !resolvedRow.isLoading &&
                                        !catalogId.isNullOrBlank() &&
                                        !addonId.isNullOrBlank() &&
                                        !apiType.isNullOrBlank()
                                    ) {
                                        onLoadMoreCatalog(catalogId, addonId, apiType)
                                    }
                                }
                        }

                        LazyRow(
                            state = rowListState,
                            modifier = Modifier.focusRestorer {
                                val restoreIndex = (focusedItemByRow[resolvedRow.key] ?: 0)
                                    .coerceIn(0, (resolvedRow.items.size - 1).coerceAtLeast(0))
                                requesterFor(resolvedRow.key, restoreIndex)
                            },
                            contentPadding = PaddingValues(horizontal = rowHorizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(rowItemSpacing)
                        ) {
                            itemsIndexed(
                                items = resolvedRow.items,
                                key = { index, item -> "${resolvedRow.key}_${item.key}_$index" }
                            ) { index, item ->
                                val requester = requesterFor(resolvedRow.key, index)
                                val onFocused = {
                                    focusedItemByRow[resolvedRow.key] = index
                                    activeRowKey = resolvedRow.key
                                    heroItem = item.heroPreview
                                    if (resolvedRow.key == "continue_watching") {
                                        lastFocusedContinueWatchingIndex = index
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
                                        ModernCarouselCard(
                                            item = item,
                                            useLandscapePosters = useLandscapePosters,
                                            showLabels = uiState.posterLabelsEnabled,
                                            cardCornerRadius = cardCornerRadius,
                                            cardWidth = activeCardWidth,
                                            cardHeight = activeCardHeight,
                                            focusRequester = requester,
                                            onFocused = onFocused,
                                            onClick = {
                                                onNavigateToDetail(
                                                    payload.itemId,
                                                    payload.itemType,
                                                    payload.addonBaseUrl
                                                )
                                            },
                                            onMoveUp = { moveToRow(-1) },
                                            onMoveDown = { moveToRow(1) }
                                        )
                                    }
                                }
                            }
                        }

                        if (showNextRowPreview) {
                            val rowIndex = rowIndexByKey[resolvedRow.key] ?: -1
                            val previewRow = carouselRows.getOrNull(rowIndex + 1)
                            if (previewRow != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 16.dp)
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
                                            key = { index, item -> "${previewRow.key}_${item.key}_$index" }
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
                    }
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
    index: Int,
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
            description = item.info.episodeDescription ?: item.info.episodeTitle,
            contentTypeText = item.info.contentType.replaceFirstChar { ch -> ch.uppercase() },
            yearText = null,
            imdbText = null,
            genres = emptyList(),
            poster = item.info.poster,
            backdrop = item.info.backdrop,
            imageUrl = if (useLandscapePosters) {
                item.info.backdrop ?: item.info.poster
            } else {
                item.info.poster ?: item.info.backdrop
            }
        )
    }

    val imageUrl = when (item) {
        is ContinueWatchingItem.InProgress -> if (useLandscapePosters) {
            if (isSeriesType(item.progress.contentType)) {
                item.episodeThumbnail ?: item.progress.poster ?: item.progress.backdrop
            } else {
                item.progress.backdrop ?: item.progress.poster
            }
        } else {
            if (isSeriesType(item.progress.contentType)) {
                heroPreview.poster ?: item.progress.poster
            } else {
                item.progress.poster ?: item.progress.backdrop
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            item.info.thumbnail ?: item.info.poster ?: item.info.backdrop
        } else {
            item.info.poster ?: item.info.backdrop
        }
    }

    return ModernCarouselItem(
        key = "cw_${index}_$imageUrl",
        title = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.name
            is ContinueWatchingItem.NextUp -> item.info.name
        },
        subtitle = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.episodeDisplayString ?: item.progress.episodeTitle
            is ContinueWatchingItem.NextUp -> "S${item.info.season}E${item.info.episode}"
        },
        imageUrl = imageUrl,
        heroPreview = heroPreview.copy(imageUrl = imageUrl ?: heroPreview.imageUrl),
        payload = ModernPayload.ContinueWatching(item)
    )
}

private fun buildCatalogItem(
    index: Int,
    item: MetaPreview,
    row: CatalogRow,
    useLandscapePosters: Boolean
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
        key = "catalog_${row.key()}_${item.id}_$index",
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
            addonBaseUrl = row.addonBaseUrl
        )
    )
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
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Boolean,
    onMoveDown: () -> Boolean
) {
    val cardShape = RoundedCornerShape(cardCornerRadius)
    val landscapeLogoGradient = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.58f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.75f)
            )
        )
    }

    Column(
        modifier = Modifier.width(cardWidth),
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
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                if (useLandscapePosters && !item.heroPreview.logo.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(landscapeLogoGradient)
                    )
                    AsyncImage(
                        model = item.heroPreview.logo,
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

        if (showLabels) {
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
private fun PreviewCarouselCard(
    imageUrl: String?,
    cardWidth: Dp,
    cardHeight: Dp
) {
    Box(
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .clip(RoundedCornerShape(10.dp))
    ) {
        AsyncImage(
            model = imageUrl,
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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy((8.dp * titleScale))
    ) {
        if (!preview.logo.isNullOrBlank()) {
            AsyncImage(
                model = preview.logo,
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
