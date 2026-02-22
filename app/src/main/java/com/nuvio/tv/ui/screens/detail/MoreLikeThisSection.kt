package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle

@Composable
fun MoreLikeThisSection(
    items: List<MetaPreview>,
    upFocusRequester: FocusRequester? = null,
    restoreItemId: String? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {},
    onItemFocused: (MetaPreview) -> Unit = {},
    onItemClick: (MetaPreview) -> Unit
) {
    if (items.isEmpty()) return

    val restoreFocusRequester = remember { FocusRequester() }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    LaunchedEffect(items) {
        val validIds = items.mapTo(mutableSetOf()) { it.id }
        itemFocusRequesters.keys.retainAll(validIds)
    }

    LaunchedEffect(restoreFocusToken, restoreItemId, items) {
        if (restoreFocusToken <= 0 || restoreItemId.isNullOrBlank()) return@LaunchedEffect
        if (items.none { it.id == restoreItemId }) return@LaunchedEffect
        restoreFocusRequester.requestFocusAfterFrames()
    }

    val landscapeStyle = remember {
        PosterCardStyle(
            width = 260.dp,
            height = 146.dp,
            cornerRadius = 12.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.02f
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { index, item -> item.id + "|" + item.name + "|" + index }
            ) { _, item ->
                val isRestoreTarget = item.id == restoreItemId
                val focusRequester = if (isRestoreTarget) {
                    restoreFocusRequester
                } else {
                    itemFocusRequesters.getOrPut(item.id) { FocusRequester() }
                }

                GridContentCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    posterCardStyle = landscapeStyle,
                    showLabel = true,
                    imageCrossfade = true,
                    focusRequester = focusRequester,
                    upFocusRequester = upFocusRequester,
                    onFocused = {
                        onItemFocused(item)
                        if (isRestoreTarget && restoreFocusToken > 0) {
                            onRestoreFocusHandled()
                        }
                    }
                )
            }
        }
    }
}
