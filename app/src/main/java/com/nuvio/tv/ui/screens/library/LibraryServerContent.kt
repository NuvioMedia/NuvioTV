package com.nuvio.tv.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle

internal fun LazyGridScope.libraryServerContent(
    shelves: List<LibraryServerShelf>?,
    posterCardStyle: PosterCardStyle,
    rowInset: Dp,
    focusTarget: Pair<String, Int>?,
    focusedIndexes: Map<String, Int>,
    onItemFocused: (String, Int) -> Unit,
    isWatched: (MetaPreview) -> Boolean,
    onItemClick: (String, String, String) -> Unit,
    onSeeAll: (CatalogRow) -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit
) {
    when {
        shelves == null -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }

        shelves.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyScreenState(
                title = stringResource(R.string.library_servers_empty_title),
                subtitle = stringResource(R.string.library_servers_empty_message),
                icon = Icons.Default.Dns,
                height = 260.dp
            )
        }

        else -> shelves.forEach { shelf ->
            val row = shelf.row
            val failure = shelf.failure
            if (row != null && row.items.isNotEmpty()) {
                item(key = "server_shelf_${shelf.key}", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogRowSection(
                        catalogRow = row,
                        onItemClick = onItemClick,
                        onSeeAll = { onSeeAll(row) },
                        showSeeAll = row.hasMore,
                        posterCardStyle = posterCardStyle,
                        showAddonName = false,
                        showCatalogTypeSuffix = false,
                        isItemWatched = isWatched,
                        onItemLongPress = onItemLongPress,
                        modifier = Modifier.bleed(rowInset),
                        focusedItemIndex = focusTarget?.takeIf { it.first == shelf.key }?.second ?: -1,
                        restorerFocusedIndex = focusedIndexes[shelf.key] ?: -1,
                        onItemFocused = { index -> onItemFocused(shelf.key, index) }
                    )
                }
            } else if (failure != null) {
                item(key = "server_shelf_error_${shelf.key}", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyScreenState(
                        title = stringResource(R.string.library_server_load_failed, shelf.title),
                        subtitle = stringResource(failure.messageRes()),
                        icon = Icons.Default.Dns,
                        height = 180.dp
                    )
                }
            }
        }
    }
}

private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}
