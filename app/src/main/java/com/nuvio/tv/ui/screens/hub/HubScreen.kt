@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.hub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.ui.screens.home.HomeViewModel
import com.nuvio.tv.ui.screens.home.homeItemStatusKey
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Movies/TV hub: one deduped grid fed by every enabled addon's movie (or
 * series/tv) catalogs, with a Source chip row to slice back to a single catalog.
 * Mirrors CatalogSeeAllScreen's grid (focus restore, infinite scroll, poster
 * options) so the hubs behave like the rest of the app.
 */
@Composable
fun HubScreen(
    kind: HubKind,
    viewModel: HubViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel? = null,
    posterOptionsViewModel: com.nuvio.tv.ui.components.posteroptions.PosterOptionsViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    // homeViewModel is optional (resolved from the Home back stack entry for
    // watched markers); read its value directly since a null-safe `by` delegate
    // on a nullable State doesn't compile.
    val homeUiState = homeViewModel?.uiState?.collectAsState()?.value
    val posterOptionsController = posterOptionsViewModel.controller

    LaunchedEffect(kind) { viewModel.initialize(kind) }
    BackHandler { onBackPress() }

    val posterCardStyle = PosterCardDefaults.Style
    val titleText = stringResource(
        when (kind) {
            HubKind.MOVIES -> R.string.nav_movies
            HubKind.TV -> R.string.nav_tv
        }
    )

    val gridState = rememberLazyGridState()
    val restoreFocusRequester = remember { FocusRequester() }
    // Persist the focused item by stable dedup key (not grid index) so returning
    // from Details re-focuses the same poster even if the merged grid reorders.
    var focusedItemKey by rememberSaveable(kind.name) { mutableStateOf<String?>(null) }
    var shouldRestoreFocus by rememberSaveable(kind.name) { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val items = uiState.items
    val focusedItemIndex = remember(items, focusedItemKey) {
        if (items.isEmpty()) return@remember 0
        val key = focusedItemKey
        if (key.isNullOrBlank()) return@remember 0
        items.indexOfFirst { hubDedupKey(it) == key }.takeIf { it >= 0 } ?: 0
    }

    // Fresh value for the load-more collector without restarting the effect.
    val currentUiState by rememberUpdatedState(uiState)

    // Load more when scrolling near the bottom of the merged grid.
    LaunchedEffect(gridState, items.size) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            lastVisible to total
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 6) {
                    if (currentUiState.hasMore && !currentUiState.isLoadingMore) {
                        viewModel.loadMore()
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

    LaunchedEffect(shouldRestoreFocus, items.size, focusedItemKey) {
        if (!shouldRestoreFocus) return@LaunchedEffect
        if (items.isEmpty()) return@LaunchedEffect

        val targetIndex = focusedItemIndex.coerceIn(0, items.lastIndex)
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
            .padding(vertical = NuvioTheme.spacing.xl)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTheme.spacing.xxxl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioTheme.colors.TextPrimary
            )
        }

        // Source filter chips: "All" + one chip per catalog feeding this hub.
        if (uiState.sources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                item(key = "hub_all") {
                    HubSourceChip(
                        label = stringResource(R.string.hub_source_all),
                        isSelected = uiState.selectedSourceKey == null,
                        onClick = { viewModel.selectSource(null) }
                    )
                }
                items(items = uiState.sources, key = { it.key }) { source ->
                    HubSourceChip(
                        label = source.sourceLabel,
                        isSelected = uiState.selectedSourceKey == source.key,
                        onClick = { viewModel.selectSource(source.key) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.sources.isEmpty() -> {
                EmptyScreenState(
                    title = stringResource(R.string.hub_empty_title),
                    subtitle = stringResource(R.string.hub_empty_subtitle),
                    icon = Icons.Default.GridView
                )
            }

            items.isEmpty() -> {
                EmptyScreenState(
                    title = stringResource(R.string.hub_empty_title),
                    subtitle = stringResource(R.string.hub_empty_subtitle),
                    icon = Icons.Default.GridView
                )
            }

            else -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = posterCardStyle.width),
                        modifier = Modifier.dpadRepeatThrottle(),
                        contentPadding = PaddingValues(
                            start = NuvioTheme.spacing.xxxl,
                            end = NuvioTheme.spacing.xl,
                            top = NuvioTheme.spacing.md,
                            bottom = NuvioTheme.spacing.xxl
                        ),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                    ) {
                        itemsIndexed(
                            items = items,
                            key = { _, item -> hubDedupKey(item) }
                        ) { index, item ->
                            val isWatched = homeUiState?.movieWatchedStatus?.get(
                                homeItemStatusKey(item.id, item.apiType)
                            ) == true
                            val itemFocusKey = hubDedupKey(item)
                            GridContentCard(
                                item = item,
                                posterCardStyle = posterCardStyle,
                                showLabel = true,
                                isWatched = isWatched,
                                focusRequester = if (index == focusedItemIndex) restoreFocusRequester else null,
                                onFocused = {
                                    // While restoring after Details/resume, ignore transient focus
                                    // on the first cell so it can't overwrite the saved key.
                                    if (shouldRestoreFocus &&
                                        focusedItemKey != null &&
                                        itemFocusKey != focusedItemKey
                                    ) {
                                        return@GridContentCard
                                    }
                                    focusedItemKey = itemFocusKey
                                },
                                onClick = {
                                    focusedItemKey = itemFocusKey
                                    onNavigateToDetail(item.id, item.apiType, item.sourceAddonBaseUrl.orEmpty())
                                },
                                onLongPress = {
                                    focusedItemKey = itemFocusKey
                                    posterOptionsController.show(item, item.sourceAddonBaseUrl)
                                }
                            )
                        }

                        if (uiState.isLoadingMore) {
                            item(key = "hub_loading_more") {
                                val cardShape = remember(posterCardStyle.cornerRadius) {
                                    RoundedCornerShape(posterCardStyle.cornerRadius)
                                }
                                val cardDepthStyle = LocalCardDepthStyle.current
                                Column(
                                    modifier = Modifier.width(posterCardStyle.width)
                                ) {
                                    androidx.tv.material3.Card(
                                        onClick = {},
                                        modifier = Modifier
                                            .width(posterCardStyle.width)
                                            .height(posterCardStyle.height)
                                            .then(Modifier.focusProperties { canFocus = false }),
                                        shape = androidx.tv.material3.CardDefaults.shape(shape = cardShape),
                                        colors = androidx.tv.material3.CardDefaults.colors(
                                            containerColor = NuvioTheme.colors.BackgroundCard,
                                            focusedContainerColor = NuvioTheme.colors.BackgroundCard
                                        )
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(cardShape)
                                                .nuvioCardDepth(
                                                    shape = cardShape,
                                                    surface = com.nuvio.tv.domain.model.CardDepthSurface.POSTERS,
                                                    style = cardDepthStyle
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            LoadingIndicator()
                                        }
                                    }
                                    Spacer(
                                        modifier = Modifier
                                            .width(posterCardStyle.width)
                                            .padding(top = NuvioTheme.spacing.sm)
                                            .height(MaterialTheme.typography.titleMedium.lineHeight.value.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val posterOptionsState by posterOptionsController.state.collectAsState()
        com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
            state = posterOptionsState,
            controller = posterOptionsController,
            onNavigateToDetail = { id, type, addonBaseUrl ->
                onNavigateToDetail(id, type, addonBaseUrl)
            }
        )
    }
}

/** Simple selected/unselected filter chip for the hub Source row. */
@Composable
private fun HubSourceChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = if (isSelected) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextSecondary
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        modifier = modifier,
        colors = FilterChipDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            selectedContainerColor = NuvioTheme.colors.Secondary,
            focusedSelectedContainerColor = NuvioTheme.colors.Secondary,
            contentColor = textColor,
            focusedContentColor = textColor,
            selectedContentColor = textColor,
            focusedSelectedContentColor = textColor
        ),
        border = FilterChipDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(20.dp)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(20.dp)
            ),
            selectedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Primary),
                shape = RoundedCornerShape(20.dp)
            ),
            focusedSelectedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(20.dp)
            )
        ),
        shape = FilterChipDefaults.shape(shape = RoundedCornerShape(20.dp))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor
        )
    }
}
