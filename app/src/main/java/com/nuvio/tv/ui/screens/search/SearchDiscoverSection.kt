package com.nuvio.tv.ui.screens.search

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DiscoverSection(
    uiState: SearchUiState,
    posterCardStyle: PosterCardStyle,
    focusResults: Boolean,
    firstItemFocusRequester: FocusRequester,
    focusedItemIndex: Int,
    shouldRestoreFocusedItem: Boolean,
    onRestoreFocusedItemHandled: () -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onDiscoverItemFocused: (Int) -> Unit,
    onSelectType: (String) -> Unit,
    onSelectCatalog: (String) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedCatalog = uiState.discoverCatalogs.firstOrNull { it.key == uiState.selectedDiscoverCatalogKey }
    val filteredCatalogs = uiState.discoverCatalogs.filter { it.type == uiState.selectedDiscoverType }
    val genres = selectedCatalog?.genres.orEmpty()
    var expandedPicker by remember { mutableStateOf<String?>(null) }

    val availableTypes = remember(uiState.discoverCatalogs) {
        uiState.discoverCatalogs.map { it.type }.distinct()
    }
    val selectedTypeLabel = uiState.selectedDiscoverType.replaceFirstChar { it.uppercase() }
    val selectedCatalogLabel = selectedCatalog?.catalogName ?: "Select"
    val selectedGenreLabel = uiState.selectedDiscoverGenre ?: "Default"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Discover",
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.TextPrimary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscoverDropdownPicker(
                modifier = Modifier.weight(1f),
                title = "Type",
                value = selectedTypeLabel,
                expanded = expandedPicker == "type",
                options = availableTypes.map { type ->
                    DiscoverOption(type.replaceFirstChar { it.uppercase() }, type)
                },
                onExpandedChange = { shouldExpand ->
                    expandedPicker = if (shouldExpand) "type" else null
                },
                onSelect = { option ->
                    onSelectType(option.value)
                    expandedPicker = null
                }
            )

            DiscoverDropdownPicker(
                modifier = Modifier.weight(1f),
                title = "Catalog",
                value = selectedCatalogLabel,
                expanded = expandedPicker == "catalog",
                options = filteredCatalogs.map { DiscoverOption(it.catalogName, it.key) },
                onExpandedChange = { shouldExpand ->
                    expandedPicker = if (shouldExpand) "catalog" else null
                },
                onSelect = { option ->
                    onSelectCatalog(option.value)
                    expandedPicker = null
                }
            )

            DiscoverDropdownPicker(
                modifier = Modifier.weight(1f),
                title = "Genre",
                value = selectedGenreLabel,
                expanded = expandedPicker == "genre",
                options = buildList {
                    add(DiscoverOption("Default", "__default__"))
                    addAll(genres.map { DiscoverOption(it, it) })
                },
                onExpandedChange = { shouldExpand ->
                    expandedPicker = if (shouldExpand) "genre" else null
                },
                onSelect = { option ->
                    onSelectGenre(option.value.takeUnless { it == "__default__" })
                    expandedPicker = null
                }
            )
        }

        selectedCatalog?.let { catalog ->
            val metadataSegments = buildList {
                add(catalog.addonName)
                if (uiState.catalogTypeSuffixEnabled) {
                    add(catalog.type.replaceFirstChar { c -> c.uppercase() })
                }
                uiState.selectedDiscoverGenre?.let(::add)
            }
            Text(
                text = metadataSegments.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary
            )
        }

        when {
            uiState.discoverLoading && uiState.discoverResults.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, bottom = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.discoverResults.isNotEmpty() -> {
                DiscoverGrid(
                    items = uiState.discoverResults,
                    posterCardStyle = posterCardStyle,
                    focusResults = focusResults,
                    firstItemFocusRequester = firstItemFocusRequester,
                    focusedItemIndex = focusedItemIndex,
                    shouldRestoreFocusedItem = shouldRestoreFocusedItem,
                    onRestoreFocusedItemHandled = onRestoreFocusedItemHandled,
                    onItemFocused = onDiscoverItemFocused,
                    pendingCount = uiState.pendingDiscoverResults.size,
                    canLoadMore = uiState.discoverHasMore,
                    isLoadingMore = uiState.discoverLoadingMore,
                    onLoadMore = onLoadMore,
                    onItemClick = { _, item ->
                        onNavigateToDetail(
                            item.id,
                            item.apiType,
                            selectedCatalog?.addonBaseUrl ?: ""
                        )
                    }
                )

            }

            uiState.discoverInitialized && selectedCatalog == null -> {
                EmptyScreenState(
                    title = "Select a catalog",
                    subtitle = "Choose a discover catalog to browse",
                    icon = Icons.Default.Search
                )
            }

            uiState.discoverInitialized && !uiState.discoverLoading && selectedCatalog != null -> {
                EmptyScreenState(
                    title = "No content found",
                    subtitle = "Try a different genre or catalog",
                    icon = Icons.Default.Search
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverDropdownPicker(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    expanded: Boolean,
    options: List<DiscoverOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (DiscoverOption) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier) {
        Card(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                },
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.FocusBackground
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, NuvioColors.Border),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.0f,
                pressedScale = 1.0f
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioColors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                        modifier = Modifier.size(20.dp),
                        tint = if (isFocused) NuvioColors.FocusRing else NuvioColors.TextSecondary
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier
                .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                .heightIn(max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NuvioColors.BackgroundCard,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, NuvioColors.Border)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            color = NuvioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onSelect(option) },
                    colors = MenuDefaults.itemColors(
                        textColor = NuvioColors.TextPrimary,
                        disabledTextColor = NuvioColors.TextDisabled
                    )
                )
            }
        }
    }
}

private data class DiscoverOption(
    val label: String,
    val value: String
)

@Composable
internal fun DiscoverGrid(
    items: List<MetaPreview>,
    posterCardStyle: PosterCardStyle,
    focusResults: Boolean,
    firstItemFocusRequester: FocusRequester,
    focusedItemIndex: Int,
    shouldRestoreFocusedItem: Boolean,
    onRestoreFocusedItemHandled: () -> Unit,
    onItemFocused: (Int) -> Unit,
    pendingCount: Int,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onItemClick: (Int, MetaPreview) -> Unit
) {
    val restoreFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var pendingFocusOnNewItemIndex by remember { mutableStateOf<Int?>(null) }
    var localRestoreFocusedItemIndex by remember { mutableStateOf(-1) }
    var localShouldRestoreFocusedItem by remember { mutableStateOf(false) }
    val effectiveFocusedItemIndex = if (localShouldRestoreFocusedItem) {
        localRestoreFocusedItemIndex
    } else {
        focusedItemIndex
    }
    val effectiveShouldRestoreFocusedItem = shouldRestoreFocusedItem || localShouldRestoreFocusedItem
    val actionType = when {
        pendingCount > 0 -> DiscoverGridAction.ShowMore
        isLoadingMore -> DiscoverGridAction.Loading
        canLoadMore -> DiscoverGridAction.LoadMore
        else -> DiscoverGridAction.None
    }
    val totalCells = items.size + if (actionType != DiscoverGridAction.None) 1 else 0
    val hasActionCell = actionType != DiscoverGridAction.None

    val adaptiveStyle = remember(posterCardStyle) {
        val cardWidth = posterCardStyle.width
        posterCardStyle.copy(
            width = cardWidth,
            height = cardWidth * 1.5f
        )
    }

    LaunchedEffect(effectiveShouldRestoreFocusedItem, effectiveFocusedItemIndex, totalCells) {
        if (!effectiveShouldRestoreFocusedItem) return@LaunchedEffect
        if (effectiveFocusedItemIndex !in 0 until totalCells) {
            if (localShouldRestoreFocusedItem) {
                localShouldRestoreFocusedItem = false
                localRestoreFocusedItemIndex = -1
            } else {
                onRestoreFocusedItemHandled()
            }
            return@LaunchedEffect
        }
        try {
            restoreFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        repeat(2) { withFrameNanos { } }
        try {
            restoreFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        if (localShouldRestoreFocusedItem) {
            localShouldRestoreFocusedItem = false
            localRestoreFocusedItemIndex = -1
        } else {
            onRestoreFocusedItemHandled()
        }
    }

    LaunchedEffect(items.size, pendingFocusOnNewItemIndex) {
        val targetIndex = pendingFocusOnNewItemIndex ?: return@LaunchedEffect
        if (items.size <= targetIndex) return@LaunchedEffect
        pendingFocusOnNewItemIndex = null
        localRestoreFocusedItemIndex = targetIndex
        localShouldRestoreFocusedItem = true
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = adaptiveStyle.width),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(
            items = items,
            key = { index, item -> item.id.ifEmpty { "discover_$index" } },
            contentType = { _, _ -> "content_card" }
        ) { index, item ->
            val focusReq = when {
                effectiveShouldRestoreFocusedItem && index == effectiveFocusedItemIndex -> restoreFocusRequester
                focusResults && index == 0 -> firstItemFocusRequester
                else -> null
            }
            GridContentCard(
                item = item,
                onClick = { onItemClick(index, item) },
                posterCardStyle = adaptiveStyle,
                modifier = Modifier.width(adaptiveStyle.width),
                focusRequester = focusReq,
                onFocused = { onItemFocused(index) }
            )
        }

        if (hasActionCell) {
            item(
                key = "discover_action",
                contentType = "action_card"
            ) {
                val actionIndex = items.size
                val focusReq = when {
                    effectiveShouldRestoreFocusedItem && actionIndex == effectiveFocusedItemIndex -> restoreFocusRequester
                    focusResults && items.isEmpty() -> firstItemFocusRequester
                    else -> null
                }
                DiscoverActionCard(
                    actionType = actionType,
                    posterCardStyle = adaptiveStyle,
                    modifier = Modifier.width(adaptiveStyle.width),
                    focusRequester = focusReq,
                    onFocused = { onItemFocused(actionIndex) },
                    onClick = {
                        when (actionType) {
                            DiscoverGridAction.ShowMore -> {
                                pendingFocusOnNewItemIndex = items.size
                                onLoadMore()
                            }
                            DiscoverGridAction.LoadMore -> {
                                pendingFocusOnNewItemIndex = items.size
                                onLoadMore()
                            }
                            DiscoverGridAction.Loading -> Unit
                            DiscoverGridAction.None -> Unit
                        }
                    }
                )
            }
        }
    }
}

private sealed class DiscoverGridAction {
    object None : DiscoverGridAction()
    object ShowMore : DiscoverGridAction()
    object LoadMore : DiscoverGridAction()
    object Loading : DiscoverGridAction()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverActionCard(
    actionType: DiscoverGridAction,
    posterCardStyle: PosterCardStyle,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    val title = when (actionType) {
        DiscoverGridAction.ShowMore -> "Load more"
        DiscoverGridAction.LoadMore -> "Load more"
        DiscoverGridAction.Loading -> "Loading..."
        DiscoverGridAction.None -> ""
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .width(posterCardStyle.width)
            .onPreviewKeyEvent { event ->
                actionType != DiscoverGridAction.None &&
                    event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
            }
            .onFocusChanged { state -> if (state.isFocused) onFocused() }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            ),
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, NuvioColors.Border),
                shape = cardShape
            ),
            focusedBorder = Border(
                border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                shape = cardShape
            )
        ),
        scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .width(posterCardStyle.width)
                .aspectRatio(posterCardStyle.aspectRatio),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary,
                textAlign = TextAlign.Center
            )
        }
    }
}
