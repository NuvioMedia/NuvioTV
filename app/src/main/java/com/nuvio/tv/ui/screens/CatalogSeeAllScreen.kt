@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.foundation.layout.Box
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.HomeEvent
import com.nuvio.tv.ui.screens.home.HomeViewModel
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
fun CatalogSeeAllScreen(
    catalogId: String,
    addonId: String,
    type: String,
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val fullCatalogRows by viewModel.fullCatalogRows.collectAsState()
    val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
    val posterCardStyle = PosterCardStyle(
        width = uiState.posterCardWidthDp.dp,
        height = computedHeightDp.dp,
        cornerRadius = uiState.posterCardCornerRadiusDp.dp,
        focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
        focusedScale = PosterCardDefaults.Style.focusedScale
    )

    BackHandler { onBackPress() }

    // Find the matching catalog row from full (untruncated) data
    val catalogKey = "${addonId}_${type}_${catalogId}"
    val catalogRow = fullCatalogRows.find {
        "${it.addonId}_${it.apiType}_${it.catalogId}" == catalogKey
    }

    val gridState = rememberLazyGridState()
    val restoreFocusRequester = remember { FocusRequester() }
    var focusedItemIndex by rememberSaveable(catalogKey) { mutableStateOf(0) }
    var shouldRestoreFocus by rememberSaveable(catalogKey) { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Load more when scrolling near the bottom
    LaunchedEffect(gridState, catalogRow?.items?.size) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            lastVisible to total
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 10) {
                    val row = catalogRow
                    if (row != null && row.hasMore && !row.isLoading) {
                        viewModel.onEvent(
                            HomeEvent.OnLoadMoreCatalog(row.catalogId, row.addonId, row.apiType)
                        )
                    }
                }
            }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shouldRestoreFocus = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(shouldRestoreFocus, catalogRow?.items?.size, focusedItemIndex) {
        if (!shouldRestoreFocus) return@LaunchedEffect
        val itemsCount = catalogRow?.items?.size ?: 0
        if (itemsCount == 0) return@LaunchedEffect

        val targetIndex = focusedItemIndex.coerceIn(0, itemsCount - 1)
        val isTargetVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
        if (!isTargetVisible) {
            gridState.animateScrollToItem(targetIndex)
        }
        repeat(2) { withFrameNanos { } }
        try {
            restoreFocusRequester.requestFocus()
            shouldRestoreFocus = false
        } catch (_: IllegalStateException) {
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = catalogRow?.catalogName ?: "Catalog",
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioColors.TextPrimary
            )
        }

        if (uiState.catalogAddonNameEnabled) {
            catalogRow?.addonName?.let { addonName ->
                Text(
                    text = stringResource(R.string.catalog_see_all_from, addonName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val hasItems = catalogRow?.items?.isNotEmpty() == true
        val isCatalogLoading = catalogRow == null || catalogRow.isLoading

        if (hasItems) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = posterCardStyle.width),
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        bottom = if (catalogRow.isLoading) 80.dp else 32.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(
                        items = catalogRow.items,
                        key = { index, item -> "${catalogRow.catalogId}_${item.id}_$index" }
                    ) { index, item ->
                        GridContentCard(
                            item = item,
                            posterCardStyle = posterCardStyle,
                            showLabel = uiState.posterLabelsEnabled,
                            focusRequester = if (index == focusedItemIndex) restoreFocusRequester else null,
                            onFocused = {
                                focusedItemIndex = index
                            },
                            onClick = {
                                onNavigateToDetail(
                                    item.id,
                                    item.apiType,
                                    catalogRow.addonBaseUrl
                                )
                            }
                        )
                    }
                }

                // Loading indicator at bottom when loading more items
                if (catalogRow.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }
            }
        } else if (isCatalogLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        } else {
            EmptyScreenState(
                title = stringResource(R.string.catalog_see_all_empty_title),
                subtitle = stringResource(R.string.catalog_see_all_empty_subtitle),
                icon = Icons.Default.GridView
            )
        }
    }
}
