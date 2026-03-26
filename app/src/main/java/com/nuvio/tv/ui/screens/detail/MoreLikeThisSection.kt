package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MoreLikeThisSection(
    items: List<MetaPreview>,
    sourceLabel: String? = null,
    upFocusRequester: FocusRequester? = null,
    sectionFocusRequester: FocusRequester? = null,
    restoreItemId: String? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {},
    onItemFocused: (MetaPreview) -> Unit = {},
    onItemClick: (MetaPreview) -> Unit
) {
    if (items.isEmpty()) return

    val firstItemFocusRequester = remember { FocusRequester() }
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
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sectionFocusRequester != null) Modifier.focusRequester(sectionFocusRequester) else Modifier)
                .focusRestorer { firstItemFocusRequester },
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { index, item -> item.id + "|" + item.name + "|" + index }
            ) { index, item ->
                val isRestoreTarget = item.id == restoreItemId
                val isFirstItem = index == 0
                val focusRequester = when {
                    isRestoreTarget -> restoreFocusRequester
                    isFirstItem -> firstItemFocusRequester
                    else -> remember(item.id) { itemFocusRequesters.getOrPut(item.id) { FocusRequester() } }
                }

                Column {
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
                    val year = item.releaseInfo
                    if (!year.isNullOrBlank()) {
                        Text(
                            text = year,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(landscapeStyle.width)
                                .padding(start = 2.dp, end = 2.dp, top = 2.dp)
                        )
                    }
                }
            }
        }

        sourceLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.End)
                        .padding(end = 10.dp, top = 2.dp, bottom = 2.dp)
                )
            }
    }
}
