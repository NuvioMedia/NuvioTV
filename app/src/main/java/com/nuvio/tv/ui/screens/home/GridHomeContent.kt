package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.State
import androidx.compose.foundation.lazy.grid.items
import com.nuvio.tv.LocalContentFocusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.GridContinueWatchingSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.collectionFolderCardImageUrl
import com.nuvio.tv.ui.components.rememberArtworkBackedCardGlow
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GridHomeContent(
    uiState: HomeUiState,
    gridFocusState: HomeScreenFocusState,
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
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    onItemFocus: (com.nuvio.tv.domain.model.MetaPreview) -> Unit = {},
    catalogSeeAllLabel: String? = null,
    onSaveGridFocusState: (Int, Int, String?) -> Unit,
    scrollToTopTrigger: Int = 0
) {
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = gridFocusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = gridFocusState.verticalScrollOffset
    )
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val lastFocusedGridItemKey = remember { mutableStateOf(gridFocusState.focusedItemKey) }

    // Scroll to top when triggered from sidebar Home button.
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            gridState.scrollToItem(0, 0)
        }
    }

    // Save scroll state when leaving
    DisposableEffect(Unit) {
        onDispose {
            onSaveGridFocusState(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
                lastFocusedGridItemKey.value
            )
        }
    }

    LaunchedEffect(gridFocusState.verticalScrollIndex, gridFocusState.verticalScrollOffset) {
        val targetIndex = gridFocusState.verticalScrollIndex
        val targetOffset = gridFocusState.verticalScrollOffset
        if (gridState.firstVisibleItemIndex == targetIndex &&
            gridState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            gridState.scrollToItem(targetIndex, targetOffset)
        }
    }

    // Offset for section indices: pre-items + continue watching item (if present)
    val gridItems = uiState.gridItems
    val continueWatchingItems = uiState.continueWatchingItems
    val continueWatchingOffset = if (continueWatchingItems.isNotEmpty()) 1 else 0

    LaunchedEffect(gridItems, gridFocusState.hasSavedFocus, gridFocusState.focusedItemKey) {
        val targetKey = gridFocusState.focusedItemKey ?: return@LaunchedEffect
        if (!gridFocusState.hasSavedFocus) return@LaunchedEffect
        val requester = focusRequesters[targetKey] ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        if (runCatching { requester.requestFocus() }.isSuccess) {
            lastFocusedGridItemKey.value = targetKey
        }
    }

    // Build index-to-section mapping for sticky header
    val sectionMapping = remember(gridItems, continueWatchingOffset) {
        buildSectionMapping(gridItems, continueWatchingOffset)
    }

    // Pre-compute whether hero exists to avoid repeated list scan in derivedStateOf
    val hasHero = remember(gridItems) {
        gridItems.firstOrNull() is GridItem.Hero
    }
    val topPadding = if (hasHero) 0.dp else 24.dp

    // Determine if hero is scrolled past
    val isScrolledPastHeroState = remember(hasHero, gridState) {
        derivedStateOf {
            if (hasHero) {
                gridState.firstVisibleItemIndex > 0
            } else {
                true
            }
        }
    }
    val shouldRequestInitialFocus = remember(
        gridFocusState.hasSavedFocus,
        gridFocusState.verticalScrollIndex,
        gridFocusState.verticalScrollOffset
    ) {
        !gridFocusState.hasSavedFocus &&
            gridFocusState.verticalScrollIndex == 0 &&
            gridFocusState.verticalScrollOffset == 0
    }
    val heroFocusRequester = remember { FocusRequester() }
    val firstGridItemFocusRequester = remember { FocusRequester() }
    val hasContinueWatching = continueWatchingItems.isNotEmpty()
    val hasStandaloneFocusableGridItem = remember(gridItems) {
        gridItems.any { it is GridItem.Content || it is GridItem.SeeAll }
    }

    LaunchedEffect(
        shouldRequestInitialFocus,
        hasHero,
        hasContinueWatching,
        hasStandaloneFocusableGridItem,
            gridItems.size
    ) {
        if (!shouldRequestInitialFocus) return@LaunchedEffect
        if (hasContinueWatching && !hasHero) return@LaunchedEffect
        val targetRequester = when {
            hasHero -> heroFocusRequester
            hasStandaloneFocusableGridItem -> firstGridItemFocusRequester
            else -> null
        } ?: return@LaunchedEffect

        repeat(2) { withFrameNanos { } }
        try {
            targetRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    // Throttle D-pad key repeats to prevent HWUI overload when a key is held down.

    val gridItemsWithKeys = remember(gridItems) {
        val occurrences = mutableMapOf<String, Int>()
        gridItems.map { item ->
            val key = when (item) {
                is GridItem.Hero -> "hero"
                is GridItem.SectionDivider -> "divider_${item.catalogId}_${item.addonId}_${item.type}"
                is GridItem.Content -> {
                    val base = "content_${item.catalogId}_${item.item.id}"
                    val count = occurrences.getOrDefault(base, 0)
                    occurrences[base] = count + 1
                    "${base}_$count"
                }
                is GridItem.SeeAll -> "see_all_${item.catalogId}_${item.addonId}_${item.type}"
                is GridItem.CollectionHeader -> "col_header_${item.collectionId}"
                is GridItem.CollectionFolder -> "col_folder_${item.collectionId}_${item.folder.id}"
            }
            item to key
        }
    }

    // Compute the split point: items before the first section divider/collection header
    // are "pre-section" items (e.g. Hero). Continue Watching goes between them and the rest.
    val firstSectionIndex = remember(gridItemsWithKeys) {
        gridItemsWithKeys.indexOfFirst { (item, _) ->
            item is GridItem.SectionDivider || item is GridItem.CollectionHeader
        }
    }
    val preItems = remember(gridItemsWithKeys, firstSectionIndex) {
        if (firstSectionIndex > 0) gridItemsWithKeys.subList(0, firstSectionIndex)
        else if (firstSectionIndex == 0) emptyList()
        else gridItemsWithKeys // no section divider found — all items are "pre"
    }
    val postItems = remember(gridItemsWithKeys, firstSectionIndex) {
        if (firstSectionIndex >= 0) gridItemsWithKeys.subList(firstSectionIndex, gridItemsWithKeys.size)
        else emptyList()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val contentFocusRequester = LocalContentFocusRequester.current
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridWidth = maxWidth
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = posterCardStyle.width),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocusRequester)
                .focusRestorer()
                .dpadRepeatThrottle(),
            contentPadding = PaddingValues(
                start = 48.dp,
                end = 24.dp,
                top = topPadding,
                bottom = 32.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var firstGridFocusableAssigned = false

            // Emit pre-section items (Hero)
            if (preItems.isNotEmpty()) {
                items(
                    items = preItems,
                    key = { it.second },
                    span = { pair ->
                        val spanCount = when (pair.first) {
                            is GridItem.Hero, is GridItem.SectionDivider, is GridItem.CollectionHeader -> maxLineSpan
                            else -> 1
                        }
                        GridItemSpan(spanCount)
                    },
                    contentType = { pair ->
                        when (pair.first) {
                            is GridItem.Hero -> "hero"
                            is GridItem.SectionDivider -> "divider"
                            is GridItem.Content -> "content"
                            is GridItem.SeeAll -> "see_all"
                            is GridItem.CollectionHeader -> "collection_header"
                            is GridItem.CollectionFolder -> "collection_folder"
                        }
                    }
                ) { (gridItem, itemKey) ->
                    when (gridItem) {
                        is GridItem.Hero -> {
                            HeroCarousel(
                                items = gridItem.items.asStable(),
                                focusRequester = if (shouldRequestInitialFocus) heroFocusRequester else null,
                                onItemClick = remember(onNavigateToDetail) {
                                    { item ->
                                        onNavigateToDetail(
                                            item.id,
                                            item.apiType,
                                            ""
                                        )
                                    }
                                },
                                fullWidth = gridWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> { /* pre-section should only contain Hero */ }
                    }
                }
            }

            // Emit Continue Watching as a dedicated item
            if (continueWatchingItems.isNotEmpty()) {
                item(
                    key = "continue_watching",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "continue_watching"
                ) {
                    GridContinueWatchingSection(
                        modifier = Modifier.fillMaxWidth(),
                        fullWidth = gridWidth,
                        items = continueWatchingItems,
                        focusedItemIndex = if (shouldRequestInitialFocus && !hasHero) 0 else -1,
                        onItemClick = onContinueWatchingClick,
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
                        blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                        useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw
                    )
                }
            }

            // Emit post-section items (SectionDividers, Content, SeeAll, Collections)
            if (postItems.isNotEmpty()) {
                items(
                    items = postItems,
                    key = { it.second },
                    span = { pair ->
                        val spanCount = when (pair.first) {
                            is GridItem.Hero, is GridItem.SectionDivider, is GridItem.CollectionHeader -> maxLineSpan
                            else -> 1
                        }
                        GridItemSpan(spanCount)
                    },
                    contentType = { pair ->
                        when (pair.first) {
                            is GridItem.Hero -> "hero"
                            is GridItem.SectionDivider -> "divider"
                            is GridItem.Content -> "content"
                            is GridItem.SeeAll -> "see_all"
                            is GridItem.CollectionHeader -> "collection_header"
                            is GridItem.CollectionFolder -> "collection_folder"
                        }
                    }
                ) { (gridItem, itemKey) ->
                when (gridItem) {
                    is GridItem.Hero -> {
                        HeroCarousel(
                            items = gridItem.items.asStable(),
                            focusRequester = if (shouldRequestInitialFocus) heroFocusRequester else null,
                            onItemClick = remember(onNavigateToDetail) {
                                { item ->
                                    onNavigateToDetail(
                                        item.id,
                                        item.apiType,
                                        ""
                                    )
                                }
                            },
                            fullWidth = gridWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is GridItem.SectionDivider -> {
                        val strTypeMovie = stringResource(R.string.type_movie)
                        val strTypeSeries = stringResource(R.string.type_series)
                        val typeLabel = when (gridItem.type.lowercase()) {
                            "movie" -> strTypeMovie
                            "series" -> strTypeSeries
                            else -> gridItem.type.replaceFirstChar { it.uppercase() }
                        }
                        val displayName = if (uiState.catalogTypeSuffixEnabled && typeLabel.isNotBlank()) {
                            "${gridItem.catalogName.replaceFirstChar { it.uppercase() }} - $typeLabel"
                        } else {
                            gridItem.catalogName.replaceFirstChar { it.uppercase() }
                        }
                        SectionDivider(
                            catalogName = displayName
                        )
                    }

                    is GridItem.Content -> {
                        val focusRequester = if (
                            shouldRequestInitialFocus &&
                            !hasHero &&
                            !hasContinueWatching &&
                            !firstGridFocusableAssigned
                        ) {
                            firstGridFocusableAssigned = true
                            firstGridItemFocusRequester
                        } else {
                            null
                        }
                        GridContentCard(
                            item = gridItem.item,
                            focusRequester = focusRequester ?: focusRequesters.getOrPut(itemKey) { FocusRequester() },
                            posterCardStyle = posterCardStyle,
                            showLabel = uiState.posterLabelsEnabled,
                            isWatched = isCatalogItemWatched(gridItem.item),
                            onFocused = remember(itemKey, gridItem.item) {
                                {
                                    lastFocusedGridItemKey.value = itemKey
                                    onItemFocus(gridItem.item)
                                }
                            },
                            onClick = remember(gridItem.item, gridItem.addonBaseUrl) {
                                {
                                    onNavigateToDetail(
                                        gridItem.item.id,
                                        gridItem.item.apiType,
                                        gridItem.addonBaseUrl
                                    )
                                }
                            },
                            onLongPress = remember(gridItem.item, gridItem.addonBaseUrl) {
                                {
                                    onCatalogItemLongPress(gridItem.item, gridItem.addonBaseUrl)
                                }
                            }
                        )
                    }

                    is GridItem.SeeAll -> {
                        val focusRequester = if (
                            shouldRequestInitialFocus &&
                            !hasHero &&
                            !hasContinueWatching &&
                            !firstGridFocusableAssigned
                        ) {
                            firstGridFocusableAssigned = true
                            firstGridItemFocusRequester
                        } else {
                            null
                        }
                        SeeAllGridCard(
                            posterCardStyle = posterCardStyle,
                            focusRequester = focusRequester,
                            label = catalogSeeAllLabel,
                            onClick = {
                                onNavigateToCatalogSeeAll(
                                    gridItem.catalogId,
                                    gridItem.addonId,
                                    gridItem.type
                                )
                            }
                        )
                    }

                    is GridItem.CollectionHeader -> {
                        SectionDivider(catalogName = gridItem.title)
                    }

                    is GridItem.CollectionFolder -> {
                        GridCollectionFolderCard(
                            folder = gridItem.folder,
                            collectionTitle = gridItem.collectionTitle,
                            focusGlowEnabled = gridItem.focusGlowEnabled,
                            posterCardStyle = posterCardStyle,
                            focusRequester = focusRequesters.getOrPut(itemKey) { FocusRequester() },
                            onFocused = remember(itemKey) { { lastFocusedGridItemKey.value = itemKey } },
                            onClick = remember(gridItem.collectionId, gridItem.folder.id) {
                                {
                                    onNavigateToFolderDetail(gridItem.collectionId, gridItem.folder.id)
                                }
                            }
                        )
                    }
                }
            }
            }

        } // end LazyVerticalGrid
        } // end BoxWithConstraints

        // Sticky header overlay
        GridStickyHeader(
            gridState = gridState,
            sectionMapping = sectionMapping,
            isScrolledPastHeroState = isScrolledPastHeroState
        )
    }
}

@Composable
private fun GridStickyHeader(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    sectionMapping: SectionMapping,
    isScrolledPastHeroState: androidx.compose.runtime.State<Boolean>
) {
    val currentSectionName by remember(gridState, sectionMapping) {
        derivedStateOf {
            sectionMapping.findSectionForIndex(gridState.firstVisibleItemIndex)?.catalogName
        }
    }

    AnimatedVisibility(
        visible = isScrolledPastHeroState.value && currentSectionName != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        StickyCategoryHeader(
            sectionName = currentSectionName ?: ""
        )
    }
}

@Composable
private fun SectionDivider(
    catalogName: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 12.dp)
    ) {
        Text(
            text = catalogName,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.TextPrimary
        )
    }
}

@Composable
private fun StickyCategoryHeader(
    sectionName: String,
    modifier: Modifier = Modifier
) {
    val bgColor = NuvioColors.Background
    val headerGradient = remember(bgColor) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to bgColor,
                0.7f to bgColor.copy(alpha = 0.95f),
                1.0f to Color.Transparent
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(headerGradient)
            .padding(horizontal = 48.dp, vertical = 12.dp)
    ) {
        Text(
            text = sectionName,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioColors.TextPrimary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeeAllGridCard(
    onClick: () -> Unit,
    posterCardStyle: PosterCardStyle,
    focusRequester: FocusRequester? = null,
    label: String? = null,
    modifier: Modifier = Modifier
) {
    val seeAllCardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(posterCardStyle.aspectRatio)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = CardDefaults.shape(
            shape = seeAllCardShape
        ),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                shape = seeAllCardShape
            )
        ),
        scale = CardDefaults.scale(
            focusedScale = posterCardStyle.focusedScale
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = label ?: stringResource(R.string.action_see_all),
                    modifier = Modifier.size(32.dp),
                    tint = NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = label ?: stringResource(R.string.action_see_all),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GridCollectionFolderCard(
    folder: CollectionFolder,
    collectionTitle: String,
    focusGlowEnabled: Boolean,
    posterCardStyle: PosterCardStyle,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    var isFocused by remember { mutableStateOf(false) }
    val cardGlow = rememberArtworkBackedCardGlow(
        imageUrl = folder.coverImageUrl,
        fallbackSeed = "$collectionTitle:${folder.title}:${folder.coverEmoji.orEmpty()}",
        enabled = focusGlowEnabled
    )
    val folderAspectRatio = when (folder.tileShape) {
        PosterShape.LANDSCAPE -> 16f / 9f
        PosterShape.SQUARE -> 1f
        PosterShape.POSTER -> posterCardStyle.aspectRatio
    }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(folderAspectRatio)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                shape = cardShape
            )
        ),
        scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale),
        glow = cardGlow
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val activeImageUrl = collectionFolderCardImageUrl(folder, isFocused)
            if (!activeImageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = activeImageUrl,
                    contentDescription = folder.title,
                    modifier = Modifier.fillMaxSize().clip(cardShape),
                    contentScale = ContentScale.FillBounds
                )
            } else if (!folder.coverEmoji.isNullOrBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = folder.coverEmoji, fontSize = 36.sp)
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = folder.title.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = NuvioColors.TextSecondary
                    )
                }
            }

            // GIF overlay: show on top of cover image or emoji, visible only once loaded
            val focusGifUrl = if (isFocused && folder.focusGifEnabled) folder.focusGifUrl else null
            if (!focusGifUrl.isNullOrBlank()) {
                var gifLoaded by remember(focusGifUrl) { mutableStateOf(false) }
                val gifAlpha by animateFloatAsState(
                    targetValue = if (gifLoaded) 1f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "gifFadeIn"
                )
                AsyncImage(
                    model = focusGifUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(cardShape)
                        .graphicsLayer { alpha = gifAlpha },
                    contentScale = ContentScale.FillBounds,
                    onSuccess = { gifLoaded = true }
                )
            }

            if (!folder.hideTitle) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = folder.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// Section mapping utilities

private data class SectionInfo(
    val catalogName: String,
    val catalogId: String,
    val addonId: String,
    val type: String
)

private class SectionMapping(
    private val indexToSection: Map<Int, SectionInfo>
) {
    private val sortedSectionStarts = indexToSection.keys.sorted()

    fun findSectionForIndex(index: Int): SectionInfo? {
        if (sortedSectionStarts.isEmpty()) return null
        val insertionPoint = sortedSectionStarts.binarySearch(index)
        val targetIdx = if (insertionPoint >= 0) insertionPoint else (-insertionPoint - 2)
        if (targetIdx < 0) return null
        return indexToSection[sortedSectionStarts[targetIdx]]
    }
}

private fun buildSectionMapping(gridItems: List<GridItem>, indexOffset: Int = 0): SectionMapping {
    val mapping = mutableMapOf<Int, SectionInfo>()
    gridItems.forEachIndexed { index, item ->
        if (item is GridItem.SectionDivider) {
            mapping[index + indexOffset] = SectionInfo(
                catalogName = item.catalogName,
                catalogId = item.catalogId,
                addonId = item.addonId,
                type = item.type
            )
        }
    }
    return SectionMapping(mapping)
}
