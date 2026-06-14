package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.localizedContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.R
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ClassicHomeContent
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.GridHomeContent
import com.nuvio.tv.ui.screens.home.HomeScreenFocusState
import com.nuvio.tv.ui.screens.home.key
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.screens.home.ModernHomeContent
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FolderDetailScreen(
    viewModel: FolderDetailViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rowsFocusState by viewModel.rowsFocusState.collectAsStateWithLifecycle()
    val followLayoutFocusState by viewModel.followLayoutFocusState.collectAsStateWithLifecycle()
    val tabFocusStates by viewModel.tabFocusStates.collectAsStateWithLifecycle()
    val folder = uiState.folder

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (folder == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.folder_detail_not_found), color = NuvioTheme.colors.TextSecondary)
        }
        return
    }

    val isItemWatched: (MetaPreview) -> Boolean = remember(uiState.movieWatchedStatus) {
        { item -> uiState.movieWatchedStatus[com.nuvio.tv.ui.screens.home.homeItemStatusKey(item.id, item.apiType)] == true }
    }

    val enrichingItemId by viewModel.enrichingItemId.collectAsStateWithLifecycle()
    val enrichedPreviews by viewModel.enrichedPreviews.collectAsStateWithLifecycle()
    val failedEnrichmentIds by viewModel.failedEnrichmentIds.collectAsStateWithLifecycle()
    val trailerPreviewUrls by viewModel.trailerPreviewUrls.collectAsStateWithLifecycle()
    val trailerPreviewAudioUrls by viewModel.trailerPreviewAudioUrls.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()

    if (uiState.viewMode == FolderViewMode.FOLLOW_LAYOUT) {
        FollowLayoutContent(
            uiState = uiState,
            focusState = followLayoutFocusState,
            enrichingItemId = enrichingItemId,
            enrichedPreviews = enrichedPreviews,
            failedEnrichmentIds = failedEnrichmentIds,
            onNavigateToDetail = onNavigateToDetail,
            onLoadMoreCatalog = viewModel::loadMoreForCatalog,
            onSaveFocusState = { vi, vo, rk, ikm, m, ri, ii ->
                viewModel.saveFollowLayoutFocusState(vi, vo, rk, ikm, m, ri, ii)
            },
            onSaveGridFocusState = viewModel::saveFollowLayoutGridFocusState,
            onItemFocus = viewModel::onItemFocused,
            onPreloadAdjacentItem = viewModel::preloadAdjacentItem,
            onCatalogItemLongPress = { item, addonBaseUrl ->
                viewModel.posterOptions.show(item, addonBaseUrl)
            },
            trailerPreviewUrls = trailerPreviewUrls,
            trailerPreviewAudioUrls = trailerPreviewAudioUrls,
            onRequestTrailerPreview = viewModel::requestTrailerPreview,
            scrollToTopTrigger = scrollToTopTrigger
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = NuvioTheme.spacing.xl)
        ) {
            when (uiState.viewMode) {
                FolderViewMode.TABBED_GRID -> TabbedGridContent(
                    uiState = uiState,
                    folder = folder,
                    tabFocusState = tabFocusStates[uiState.selectedTabIndex] ?: FolderDetailGridFocusState(),
                    onSelectTab = viewModel::selectTab,
                    onNavigateToDetail = onNavigateToDetail,
                    isItemWatched = isItemWatched,
                    onLoadMore = { viewModel.loadMoreItems(uiState.selectedTabIndex) },
                    onSaveFocusState = { verticalIndex, verticalOffset, focusedItemKey ->
                        viewModel.saveTabFocusState(
                            tabIndex = uiState.selectedTabIndex,
                            verticalScrollIndex = verticalIndex,
                            verticalScrollOffset = verticalOffset,
                            focusedItemKey = focusedItemKey
                        )
                    },
                    onItemLongPress = { item, addonBaseUrl ->
                        viewModel.posterOptions.show(item, addonBaseUrl)
                    }
                )
                FolderViewMode.ROWS -> {
                    FolderHeader(folder = folder)
                    RowsContent(
                        uiState = uiState,
                        focusState = rowsFocusState,
                        onNavigateToDetail = onNavigateToDetail,
                        isItemWatched = isItemWatched,
                        onLoadMoreCatalog = viewModel::loadMoreForCatalog,
                        onSaveFocusState = { vi, vo, rk, ikm, m, ri, ii ->
                            viewModel.saveRowsFocusState(vi, vo, rk, ikm, m, ri, ii)
                        },
                        onItemFocus = viewModel::onItemFocused,
                        onItemLongPress = { item, addonBaseUrl ->
                            viewModel.posterOptions.show(item, addonBaseUrl)
                        }
                    )
                }
                FolderViewMode.FOLLOW_LAYOUT -> {} // handled above
            }
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsStateWithLifecycle()
    com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            onNavigateToDetail(id, type, addonBaseUrl)
        }
    )
}

@Composable
private fun FolderHeader(folder: com.nuvio.tv.domain.model.CollectionFolder) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        if (!folder.coverImageUrl.isNullOrBlank()) {
            val iconWidth: androidx.compose.ui.unit.Dp
            val iconHeight: androidx.compose.ui.unit.Dp
            when (folder.tileShape) {
                com.nuvio.tv.domain.model.PosterShape.POSTER -> { iconWidth = NuvioTheme.spacing.xxl; iconHeight = NuvioTheme.spacing.xxxl }
                com.nuvio.tv.domain.model.PosterShape.LANDSCAPE -> { iconWidth = 64.dp; iconHeight = 36.dp }
                com.nuvio.tv.domain.model.PosterShape.SQUARE -> { iconWidth = NuvioTheme.spacing.xxxl; iconHeight = NuvioTheme.spacing.xxxl }
            }
            AsyncImage(
                model = folder.coverImageUrl,
                contentDescription = folder.title,
                modifier = Modifier
                    .width(iconWidth)
                    .height(iconHeight)
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm)),
                contentScale = ContentScale.FillBounds
            )
        } else if (!folder.coverEmoji.isNullOrBlank()) {
            Text(
                text = folder.coverEmoji,
                style = MaterialTheme.typography.headlineLarge
            )
        }
        Text(
            text = folder.title,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TabbedGridContent(
    uiState: FolderDetailUiState,
    folder: com.nuvio.tv.domain.model.CollectionFolder,
    tabFocusState: FolderDetailGridFocusState,
    onSelectTab: (Int) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onSaveFocusState: (Int, Int, String?) -> Unit,
    onLoadMore: () -> Unit = {},
    isItemWatched: (MetaPreview) -> Boolean = { false },
    onItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> }
) {
    val tabFocusRequesters = remember(uiState.tabs.size) { uiState.tabs.indices.map { FocusRequester() } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        if (!folder.coverImageUrl.isNullOrBlank()) {
            val iconWidth: androidx.compose.ui.unit.Dp
            val iconHeight: androidx.compose.ui.unit.Dp
            when (folder.tileShape) {
                com.nuvio.tv.domain.model.PosterShape.POSTER -> { iconWidth = NuvioTheme.spacing.xxl; iconHeight = NuvioTheme.spacing.xxxl }
                com.nuvio.tv.domain.model.PosterShape.LANDSCAPE -> { iconWidth = 64.dp; iconHeight = 36.dp }
                com.nuvio.tv.domain.model.PosterShape.SQUARE -> { iconWidth = NuvioTheme.spacing.xxxl; iconHeight = NuvioTheme.spacing.xxxl }
            }
            AsyncImage(
                model = folder.coverImageUrl,
                contentDescription = folder.title,
                modifier = Modifier
                    .width(iconWidth)
                    .height(iconHeight)
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm)),
                contentScale = ContentScale.FillBounds
            )
        } else if (!folder.coverEmoji.isNullOrBlank()) {
            Text(
                text = folder.coverEmoji,
                style = MaterialTheme.typography.headlineLarge
            )
        }
        Text(
            text = folder.title,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 300.dp)
        )

        if (uiState.tabs.size > 1) {
            TabRow(
                selectedTabIndex = uiState.selectedTabIndex,
                modifier = Modifier
                    .focusRestorer {
                        tabFocusRequesters.getOrNull(uiState.selectedTabIndex) ?: FocusRequester.Default
                    }
            ) {
                uiState.tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == uiState.selectedTabIndex,
                        onFocus = { onSelectTab(index) },
                        onClick = { onSelectTab(index) },
                        modifier = if (index < tabFocusRequesters.size) {
                            Modifier.focusRequester(tabFocusRequesters[index])
                        } else Modifier
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = if (tab.isAllTab) stringResource(R.string.collections_tab_all) else tab.label,
                                style = MaterialTheme.typography.labelLarge
                            )
                            if (uiState.catalogTypeSuffixEnabled && tab.typeLabel.isNotBlank()) {
                                val localizedType = when {
                                    tab.isAllTab -> stringResource(R.string.collections_tab_combined)
                                    tab.rawType.lowercase() == "movie" -> stringResource(R.string.type_movie)
                                    tab.rawType.lowercase() == "series" -> stringResource(R.string.type_series)
                                    else -> tab.typeLabel
                                }
                                Text(
                                    text = localizedType,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.TextTertiary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val currentTab = uiState.tabs.getOrNull(uiState.selectedTabIndex)
    if (currentTab == null) return

    when {
        currentTab.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }
        currentTab.error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = currentTab.error,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        currentTab.catalogRow != null -> {
            val items = currentTab.catalogRow.items
            val posterCardStyle = PosterCardDefaults.Style
            val itemFocusRequesters = remember(uiState.selectedTabIndex) { mutableMapOf<String, FocusRequester>() }
            var lastFocusedItemKey by remember(
                uiState.selectedTabIndex,
                tabFocusState.focusedItemKey
            ) {
                mutableStateOf(tabFocusState.focusedItemKey)
            }
            val gridState = rememberLazyGridState(
                initialFirstVisibleItemIndex = tabFocusState.verticalScrollIndex,
                initialFirstVisibleItemScrollOffset = tabFocusState.verticalScrollOffset
            )

            DisposableEffect(Unit) {
                onDispose {
                    onSaveFocusState(
                        gridState.firstVisibleItemIndex,
                        gridState.firstVisibleItemScrollOffset,
                        lastFocusedItemKey
                    )
                }
            }

            LaunchedEffect(tabFocusState.verticalScrollIndex, tabFocusState.verticalScrollOffset) {
                val targetIndex = tabFocusState.verticalScrollIndex
                val targetOffset = tabFocusState.verticalScrollOffset
                if (gridState.firstVisibleItemIndex == targetIndex &&
                    gridState.firstVisibleItemScrollOffset == targetOffset
                ) {
                    return@LaunchedEffect
                }
                if (targetIndex > 0 || targetOffset > 0) {
                    gridState.scrollToItem(targetIndex, targetOffset)
                }
            }

            LaunchedEffect(items, tabFocusState.hasSavedFocus, tabFocusState.focusedItemKey) {
                val targetKey = tabFocusState.focusedItemKey ?: return@LaunchedEffect
                if (!tabFocusState.hasSavedFocus) return@LaunchedEffect
                val requester = itemFocusRequesters[targetKey] ?: return@LaunchedEffect
                repeat(2) { withFrameNanos { } }
                if (runCatching { requester.requestFocus() }.isSuccess) {
                    lastFocusedItemKey = targetKey
                }
            }

            val catalogRow = currentTab.catalogRow
            LaunchedEffect(gridState, items.size) {
                snapshotFlow {
                    val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    val total = gridState.layoutInfo.totalItemsCount
                    lastVisible to total
                }
                    .distinctUntilChanged()
                    .collect { (lastVisible, total) ->
                        if (total > 0 && lastVisible >= total - 10) {
                            if (catalogRow != null && catalogRow.hasMore && !catalogRow.isLoading) {
                                onLoadMore()
                            }
                        }
                    }
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = posterCardStyle.width),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer {
                        lastFocusedItemKey?.let { itemFocusRequesters[it] } ?: FocusRequester.Default
                    }
                    .dpadRepeatThrottle(),
                contentPadding = PaddingValues(
                    start = NuvioTheme.spacing.xxxl,
                    end = NuvioTheme.spacing.xxxl,
                    top = NuvioTheme.spacing.lg,
                    bottom = NuvioTheme.spacing.xxxl
                ),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                itemsIndexed(
                    items = items,
                    key = { index, item -> "${item.id}_$index" }
                ) { index, item ->
                    val itemKey = "${item.id}_$index"
                    val focusReq = itemFocusRequesters.getOrPut(itemKey) { FocusRequester() }
                    ContentCard(
                        item = item,
                        posterCardStyle = posterCardStyle,
                        focusRequester = focusReq,
                        isWatched = isItemWatched(item),
                        onFocus = { _ -> lastFocusedItemKey = itemKey },
                        onClick = {
                            onNavigateToDetail(
                                item.id,
                                item.apiType,
                                currentTab.catalogRow.addonBaseUrl
                            )
                        },
                        onLongPress = {
                            lastFocusedItemKey = itemKey
                            onItemLongPress(item, currentTab.catalogRow.addonBaseUrl)
                        }
                    )
                }
                if (catalogRow != null && catalogRow.isLoading) {
                    item(
                        span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = NuvioTheme.spacing.xl),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RowsContent(
    uiState: FolderDetailUiState,
    focusState: HomeScreenFocusState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit = { _, _, _ -> },
    onSaveFocusState: (Int, Int, String?, Map<String, String>, Map<String, Int>, Int, Int) -> Unit,
    isItemWatched: (MetaPreview) -> Boolean = { false },
    onItemFocus: (MetaPreview) -> Unit = {},
    onItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> }
) {
    val sourceTabs = uiState.tabs.filter { !it.isAllTab }
    val columnListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val rowFocusedItemIndex = remember { mutableMapOf<String, Int>() }
    val focusedItemByRow = remember { mutableStateMapOf<String, Int>() }
    val currentFocusedRowKey = remember { mutableStateOf(focusState.focusedRowKey) }

    DisposableEffect(Unit) {
        onDispose {
            val focusedRowKey = currentFocusedRowKey.value
            val itemKeys = mutableMapOf<String, String>()
            sourceTabs.forEach { tab ->
                val row = tab.catalogRow
                if (row != null) {
                    val rowKey = row.key()
                    val focusedIdx = rowFocusedItemIndex[rowKey] ?: 0
                    val itemId = row.items.getOrNull(focusedIdx)?.id ?: ""
                    itemKeys[rowKey] = itemId
                }
            }

            onSaveFocusState(
                columnListState.firstVisibleItemIndex,
                columnListState.firstVisibleItemScrollOffset,
                focusedRowKey,
                itemKeys,
                rowStates.mapValues { it.value.firstVisibleItemIndex },
                -1, // rowIndex
                0   // itemIndex
            )
        }
    }

    LaunchedEffect(focusState.hasSavedFocus) {
        if (focusState.hasSavedFocus && focusedItemByRow.isEmpty()) {
            val savedRowKey = focusState.focusedRowKey
            if (savedRowKey != null) {
                val savedItemKey = focusState.focusedItemKeyByRow[savedRowKey]
                sourceTabs.forEach { tab ->
                    val row = tab.catalogRow
                    if (row != null) {
                        val rowKey = row.key()
                        if (rowKey == savedRowKey) {
                            val itemIdx = if (savedItemKey != null) {
                                row.items.indexOfFirst { it.id == savedItemKey }.coerceAtLeast(0)
                            } else 0
                            focusedItemByRow[savedRowKey] = itemIdx
                            rowFocusedItemIndex[savedRowKey] = itemIdx
                        }
                    }
                }
            }
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
            columnListState.scrollToItem(targetIndex, targetOffset)
        }
    }

    val loadMoreLabel = stringResource(R.string.action_load_more)

    LazyColumn(
        state = columnListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        sourceTabs.forEachIndexed { index, tab ->
            item(key = "row_${index}_${tab.label}") {
                val folderContext = LocalContext.current
                val localizedTypeLabel = remember(tab.rawType, folderContext) {
                    localizedContentType(folderContext, tab.rawType)
                }
                val rowTitle = remember(tab.label, localizedTypeLabel, uiState.catalogTypeSuffixEnabled) {
                    if (uiState.catalogTypeSuffixEnabled && tab.label != tab.typeLabel && localizedTypeLabel.isNotEmpty()) {
                        "${tab.label} - $localizedTypeLabel"
                    } else {
                        tab.label
                    }
                }
                when {
                    tab.isLoading -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = rowTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                color = NuvioTheme.colors.TextPrimary,
                                modifier = Modifier.padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(PosterCardDefaults.Style.height),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator()
                            }
                        }
                    }
                    tab.error != null -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = rowTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                color = NuvioTheme.colors.TextPrimary,
                                modifier = Modifier.padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(PosterCardDefaults.Style.height),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = tab.error, color = NuvioTheme.colors.TextSecondary)
                            }
                        }
                    }
                    tab.catalogRow != null -> {
                        val catalogRow = tab.catalogRow
                        val rowKey = "${catalogRow.addonId}_${catalogRow.apiType}_${catalogRow.catalogId}"
                        val listState = rowStates.getOrPut(rowKey) {
                            LazyListState(
                                firstVisibleItemIndex = focusState.catalogRowScrollStates[rowKey] ?: 0
                            )
                        }
                        CatalogRowSection(
                            catalogRow = catalogRow,
                            onItemClick = onNavigateToDetail,
                            onItemLongPress = onItemLongPress,
                            onSeeAll = {
                                onLoadMoreCatalog(
                                    catalogRow.catalogId,
                                    catalogRow.addonId,
                                    catalogRow.apiType
                                )
                            },
                            showSeeAll = catalogRow.hasMore && !catalogRow.isLoading,
                            seeAllLabel = loadMoreLabel,
                            showPosterLabels = true,
                            showAddonName = false,
                            showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                            isItemWatched = isItemWatched,
                            onItemFocus = onItemFocus,
                            listState = listState,
                            focusedItemIndex = if (
                                focusState.hasSavedFocus &&
                                focusState.focusedRowIndex == index
                            ) {
                                focusState.focusedItemIndex
                            } else {
                                -1
                            },
                            restorerFocusedIndex = -1,
                            onItemFocused = { itemIndex ->
                                currentFocusedRowKey.value = rowKey
                                rowFocusedItemIndex[rowKey] = itemIndex
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowLayoutContent(
    uiState: FolderDetailUiState,
    focusState: HomeScreenFocusState,
    enrichingItemId: String? = null,
    enrichedPreviews: Map<String, MetaPreview> = emptyMap(),
    failedEnrichmentIds: Set<String> = emptySet(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit = { _, _, _ -> },
    onSaveFocusState: (Int, Int, String?, Map<String, String>, Map<String, Int>, Int, Int) -> Unit,
    onSaveGridFocusState: (Int, Int, String?) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {},
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    trailerPreviewUrls: Map<String, String> = emptyMap(),
    trailerPreviewAudioUrls: Map<String, String> = emptyMap(),
    onRequestTrailerPreview: (String, String, String?, String) -> Unit = { _, _, _, _ -> },
    scrollToTopTrigger: Int = 0
) {
    val homeState = uiState.followLayoutHomeState

    if (homeState == null || homeState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    val posterCardStyle = remember {
        PosterCardStyle(
            width = homeState.posterCardWidthDp.dp,
            height = homeState.posterCardHeightDp.dp,
            cornerRadius = homeState.posterCardCornerRadiusDp.dp
        )
    }
    val noOpCwClick: (ContinueWatchingItem) -> Unit = remember { { } }
    val noOpRemoveCw: (String, Int?, Int?, Boolean) -> Unit = remember { { _, _, _, _ -> } }
    val noOpFolderDetail: (String, String) -> Unit = remember { { _, _ -> } }
    val isItemWatched: (MetaPreview) -> Boolean = remember(homeState.movieWatchedStatus) {
        { item -> homeState.movieWatchedStatus[com.nuvio.tv.ui.screens.home.homeItemStatusKey(item.id, item.apiType)] == true }
    }
    val loadMoreLabel = stringResource(R.string.action_load_more)

    when (uiState.homeLayout) {
        HomeLayout.CLASSIC -> ClassicHomeContent(
            uiState = homeState,
            posterCardStyle = posterCardStyle,
            focusState = focusState,
            trailerPreviewUrls = trailerPreviewUrls,
            trailerPreviewAudioUrls = trailerPreviewAudioUrls,
            onNavigateToDetail = onNavigateToDetail,
            onContinueWatchingClick = noOpCwClick,
            onNavigateToCatalogSeeAll = onLoadMoreCatalog,
            onNavigateToFolderDetail = noOpFolderDetail,
            onRemoveContinueWatching = noOpRemoveCw,
            isCatalogItemWatched = isItemWatched,
            catalogSeeAllLabel = loadMoreLabel,
            onRequestTrailerPreview = { item ->
                onRequestTrailerPreview(item.id, item.name, item.releaseInfo, item.apiType)
            },
            onItemFocus = onItemFocus,
            onSaveFocusState = onSaveFocusState
        )
        HomeLayout.GRID -> GridHomeContent(
            uiState = homeState,
            gridFocusState = focusState,
            onNavigateToDetail = onNavigateToDetail,
            onContinueWatchingClick = noOpCwClick,
            onNavigateToCatalogSeeAll = onLoadMoreCatalog,
            onNavigateToFolderDetail = noOpFolderDetail,
            onRemoveContinueWatching = noOpRemoveCw,
            isCatalogItemWatched = isItemWatched,
            catalogSeeAllLabel = loadMoreLabel,
            posterCardStyle = posterCardStyle,
            onSaveGridFocusState = onSaveGridFocusState
        )
        HomeLayout.MODERN -> ModernHomeContent(
            uiState = homeState,
            focusState = focusState,
            enrichingItemId = enrichingItemId,
            enrichedPreviews = enrichedPreviews,
            failedEnrichmentIds = failedEnrichmentIds,
            trailerPreviewUrls = trailerPreviewUrls,
            trailerPreviewAudioUrls = trailerPreviewAudioUrls,
            onNavigateToDetail = onNavigateToDetail,
            onContinueWatchingClick = noOpCwClick,
            onRequestTrailerPreview = onRequestTrailerPreview,
            onLoadMoreCatalog = onLoadMoreCatalog,
            onRemoveContinueWatching = noOpRemoveCw,
            isCatalogItemWatched = isItemWatched,
            onCatalogItemLongPress = onCatalogItemLongPress,
            onNavigateToFolderDetail = noOpFolderDetail,
            onItemFocus = onItemFocus,
            onPreloadAdjacentItem = onPreloadAdjacentItem,
            onSaveFocusState = onSaveFocusState,
            scrollToTopTrigger = scrollToTopTrigger
        )
    }
}
