package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionSection(
    items: List<MetaPreview>,
    listState: LazyListState,
    title: String? = null,
    posterCardCornerRadius: Dp = NuvioTheme.spacing.md,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    sectionFocusRequester: FocusRequester? = null,
    restoreItemId: String? = null,
    restoreFocusToken: Int = 0,
    blockDefaultRestore: Boolean = false,
    lastFocusedItemId: String? = null,
    onLastFocusedItemIdChange: (String) -> Unit = {},
    onRestoreFocusHandled: () -> Unit = {},
    onItemFocused: (MetaPreview) -> Unit = {},
    onItemClick: (MetaPreview) -> Unit,
    onItemLongPress: (MetaPreview) -> Unit = {},
    isItemWatched: (MetaPreview) -> Boolean = { false },
    windowResetKey: String? = null
) {
    if (items.isEmpty()) return

    val itemIds = remember(items) { items.map { it.id } }
    listState.keepDetailRowWindow(
        itemIds = itemIds,
        lazyKeyAt = { index ->
            items.getOrNull(index)?.let { previewRowLazyKey(index, it.id, it.name) }
        },
        resetKey = windowResetKey
    )

    val firstItemFocusRequester = remember { FocusRequester() }
    val restoreFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val lastFocusedRequester = remember(lastFocusedItemId, items, restoreItemId) {
        when {
            lastFocusedItemId == null -> firstItemFocusRequester
            lastFocusedItemId == restoreItemId -> restoreFocusRequester
            lastFocusedItemId == items.firstOrNull()?.id -> firstItemFocusRequester
            else -> itemFocusRequesters.getOrPut(lastFocusedItemId) { FocusRequester() }
        }
    }

    val suppressRestoreScroll = !restoreItemId.isNullOrBlank()
    var restorePending by remember(restoreItemId) { mutableStateOf(suppressRestoreScroll) }
    var placedFocused by remember(restoreItemId) { mutableStateOf(false) }
    LaunchedEffect(restoreItemId) {
        if (restoreItemId.isNullOrBlank()) return@LaunchedEffect
        restoreFocusRequester.requestFocusAfterFrames(frames = 0)
    }
    val restoreNoScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }
    val restoreItemModifier = if (suppressRestoreScroll) {
        Modifier.bringIntoViewResponder(restoreNoScrollResponder)
    } else {
        Modifier
    }

    LaunchedEffect(items) {
        val validIds = items.mapTo(mutableSetOf()) { it.id }
        itemFocusRequesters.keys.retainAll(validIds)
    }

    val landscapeStyle = remember(posterCardCornerRadius) {
        PosterCardStyle(
            width = 260.dp,
            height = 146.dp,
            cornerRadius = posterCardCornerRadius,
            focusedBorderWidth = NuvioTheme.spacing.xxs,
            focusedScale = 1.02f
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (title.isNullOrBlank()) NuvioTheme.spacing.sm else 20.dp, bottom = NuvioTheme.spacing.sm)
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier
                    .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.sm)
            )
        }
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sectionFocusRequester != null) Modifier.focusRequester(sectionFocusRequester) else Modifier)
                .focusRestorer {
                    when {
                        restorePending -> restoreFocusRequester
                        blockDefaultRestore -> FocusRequester.Cancel
                        else -> lastFocusedRequester
                    }
                }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            itemsIndexed(
                items = items,
                key = { index, item -> previewRowLazyKey(index, item.id, item.name) }
            ) { index, item ->
                val isRestoreTarget = item.id == restoreItemId
                val isFirstItem = index == 0
                val focusRequester = when {
                    isRestoreTarget -> restoreFocusRequester
                    isFirstItem -> firstItemFocusRequester
                    else -> remember(item.id) { itemFocusRequesters.getOrPut(item.id) { FocusRequester() } }
                }

                Column(
                    modifier = restoreItemModifier.then(
                        if (isRestoreTarget && suppressRestoreScroll) {
                            Modifier.onPlaced {
                                if (placedFocused) return@onPlaced
                                placedFocused = true
                                runCatching { focusRequester.requestFocus() }
                            }
                        } else {
                            Modifier
                        }
                    )
                ) {
                    GridContentCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = { onItemLongPress(item) },
                        posterCardStyle = landscapeStyle,
                        showLabel = true,
                        imageCrossfade = true,
                        isWatched = isItemWatched(item),
                        focusRequester = focusRequester,
                        upFocusRequester = upFocusRequester,
                        downFocusRequester = downFocusRequester,
                        onFocused = {
                            onLastFocusedItemIdChange(item.id)
                            onItemFocused(item)
                            if (isRestoreTarget && !restoreItemId.isNullOrBlank()) {
                                restorePending = false
                                onRestoreFocusHandled()
                            }
                        }
                    )
                    val year = item.releaseInfo
                    if (!year.isNullOrBlank()) {
                        Text(
                            text = year,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(landscapeStyle.width)
                                .padding(start = NuvioTheme.spacing.xxs, end = NuvioTheme.spacing.xxs, top = NuvioTheme.spacing.xxs)
                        )
                    }
                }
            }
        }
    }
}
