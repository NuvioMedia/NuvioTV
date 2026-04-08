package com.nuvio.tv.ui.screens.collection

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
import coil.compose.AsyncImage
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ClassicHomeContent
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.GridHomeContent
import com.nuvio.tv.ui.screens.home.HomeScreenFocusState
import com.nuvio.tv.ui.screens.home.ModernHomeContent
import com.nuvio.tv.ui.theme.NuvioColors

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
            Text("Folder not found", color = NuvioColors.TextSecondary)
        }
        return
    }

    if (uiState.viewMode == FolderViewMode.FOLLOW_LAYOUT) {
        FollowLayoutContent(
            uiState = uiState,
            focusState = followLayoutFocusState,
            onNavigateToDetail = onNavigateToDetail,
            onSaveFocusState = viewModel::saveFollowLayoutFocusState,
            onSaveGridFocusState = viewModel::saveFollowLayoutGridFocusState
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 24.dp)
        ) {
            when (uiState.viewMode) {
                FolderViewMode.TABBED_GRID -> TabbedGridContent(
                    uiState = uiState,
                    folder = folder,
                    tabFocusState = tabFocusStates[uiState.selectedTabIndex] ?: FolderDetailGridFocusState(),
                    onSelectTab = viewModel::selectTab,
                    onNavigateToDetail = onNavigateToDetail,
                    onSaveFocusState = { verticalIndex, verticalOffset, focusedItemKey ->
                        viewModel.saveTabFocusState(
                            tabIndex = uiState.selectedTabIndex,
                            verticalScrollIndex = verticalIndex,
                            verticalScrollOffset = verticalOffset,
                            focusedItemKey = focusedItemKey
                        )
                    }
                )
                FolderViewMode.ROWS -> {
                    FolderHeader(folder = folder)
                    RowsContent(
                        uiState = uiState,
                        focusState = rowsFocusState,
                        onNavigateToDetail = onNavigateToDetail,
                        onSaveFocusState = viewModel::saveRowsFocusState
                    )
                }
                FolderViewMode.FOLLOW_LAYOUT -> {} // handled above
            }
        }
    }
}

@Composable
private fun FolderHeader(folder: com.nuvio.tv.domain.model.CollectionFolder) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!folder.coverImageUrl.isNullOrBlank()) {
            AsyncImage(
                model = folder.coverImageUrl,
                contentDescription = folder.title,
                modifier = Modifier
                    .width(48.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
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
            color = NuvioColors.TextPrimary,
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
    onSaveFocusState: (Int, Int, String?) -> Unit
) {
    val tabFocusRequesters = remember(uiState.tabs.size) { uiState.tabs.indices.map { FocusRequester() } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!folder.coverImageUrl.isNullOrBlank()) {
            AsyncImage(
                model = folder.coverImageUrl,
                contentDescription = folder.title,
                modifier = Modifier
                    .width(48.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
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
            color = NuvioColors.TextPrimary,
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
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelLarge
                            )
                            if (tab.typeLabel.isNotBlank()) {
                                Text(
                                    text = tab.typeLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioColors.TextTertiary
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
                    color = NuvioColors.TextSecondary
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

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = posterCardStyle.width),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer {
                        lastFocusedItemKey?.let { itemFocusRequesters[it] } ?: FocusRequester.Default
                    },
                contentPadding = PaddingValues(
                    start = 48.dp,
                    end = 48.dp,
                    top = 16.dp,
                    bottom = 48.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        onFocus = { _ -> lastFocusedItemKey = itemKey },
                        onClick = {
                            onNavigateToDetail(
                                item.id,
                                item.apiType,
                                currentTab.catalogRow.addonBaseUrl
                            )
                        }
                    )
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
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit
) {
    val sourceTabs = uiState.tabs.filter { !it.isAllTab }
    val columnListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val currentFocusedRowIndex = remember { intArrayOf(focusState.focusedRowIndex) }
    val currentFocusedItemIndex = remember { intArrayOf(focusState.focusedItemIndex) }

    DisposableEffect(Unit) {
        onDispose {
            onSaveFocusState(
                columnListState.firstVisibleItemIndex,
                columnListState.firstVisibleItemScrollOffset,
                currentFocusedRowIndex[0],
                currentFocusedItemIndex[0],
                rowStates.mapValues { it.value.firstVisibleItemIndex }
            )
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

    LazyColumn(
        state = columnListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sourceTabs.forEachIndexed { index, tab ->
            item(key = "row_${index}_${tab.label}") {
                when {
                    tab.isLoading -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.headlineSmall,
                                color = NuvioColors.TextPrimary,
                                modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 12.dp)
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
                                text = tab.label,
                                style = MaterialTheme.typography.headlineSmall,
                                color = NuvioColors.TextPrimary,
                                modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 12.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(PosterCardDefaults.Style.height),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = tab.error, color = NuvioColors.TextSecondary)
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
                            showPosterLabels = true,
                            showAddonName = false,
                            showCatalogTypeSuffix = false,
                            listState = listState,
                            focusedItemIndex = if (
                                focusState.hasSavedFocus &&
                                focusState.focusedRowIndex == index
                            ) {
                                focusState.focusedItemIndex
                            } else {
                                -1
                            },
                            onItemFocused = { itemIndex ->
                                currentFocusedRowIndex[0] = index
                                currentFocusedItemIndex[0] = itemIndex
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
    onNavigateToDetail: (String, String, String) -> Unit,
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit,
    onSaveGridFocusState: (Int, Int, String?) -> Unit
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
    val noOpSeeAll: (String, String, String) -> Unit = remember { { _, _, _ -> } }
    val noOpFolderDetail: (String, String) -> Unit = remember { { _, _ -> } }

    when (uiState.homeLayout) {
        HomeLayout.CLASSIC -> ClassicHomeContent(
            uiState = homeState,
            posterCardStyle = posterCardStyle,
            focusState = focusState,
            trailerPreviewUrls = emptyMap(),
            trailerPreviewAudioUrls = emptyMap(),
            onNavigateToDetail = onNavigateToDetail,
            onContinueWatchingClick = noOpCwClick,
            onNavigateToCatalogSeeAll = noOpSeeAll,
            onNavigateToFolderDetail = noOpFolderDetail,
            onRemoveContinueWatching = noOpRemoveCw,
            onRequestTrailerPreview = { },
            onSaveFocusState = onSaveFocusState
        )
        HomeLayout.GRID -> GridHomeContent(
            uiState = homeState,
            gridFocusState = focusState,
            onNavigateToDetail = onNavigateToDetail,
            onContinueWatchingClick = noOpCwClick,
            onNavigateToCatalogSeeAll = noOpSeeAll,
            onNavigateToFolderDetail = noOpFolderDetail,
            onRemoveContinueWatching = noOpRemoveCw,
            posterCardStyle = posterCardStyle,
            onSaveGridFocusState = onSaveGridFocusState
        )
        HomeLayout.MODERN -> ModernHomeContent(
            uiState = homeState,
            focusState = focusState,
            trailerPreviewUrls = emptyMap(),
            trailerPreviewAudioUrls = emptyMap(),
            onNavigateToDetail = onNavigateToDetail,
            onContinueWatchingClick = noOpCwClick,
            onRequestTrailerPreview = { _, _, _, _ -> },
            onLoadMoreCatalog = noOpSeeAll,
            onRemoveContinueWatching = noOpRemoveCw,
            onNavigateToFolderDetail = noOpFolderDetail,
            onSaveFocusState = onSaveFocusState
        )
    }
}
