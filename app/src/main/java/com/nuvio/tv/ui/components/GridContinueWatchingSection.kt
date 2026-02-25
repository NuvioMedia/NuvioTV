package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun GridContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onDetailsClick: (ContinueWatchingItem) -> Unit = onItemClick,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    modifier: Modifier = Modifier,
    focusedItemIndex: Int = -1
) {
    if (items.isEmpty()) return
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val itemFocusRequester = remember { FocusRequester() }
    val focusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }
    var lastFocusedIndex by remember { mutableIntStateOf(-1) }
    var lastRequestedFocusIndex by remember { mutableIntStateOf(-1) }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(focusedItemIndex, items) {
        if (focusedItemIndex >= 0 && focusedItemIndex < items.size) {
            if (lastRequestedFocusIndex == focusedItemIndex) return@LaunchedEffect
            var focused = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                focused = runCatching { itemFocusRequester.requestFocus() }.isSuccess
                if (focused) break
            }
            if (focused) {
                lastRequestedFocusIndex = focusedItemIndex
            }
        } else {
            lastRequestedFocusIndex = -1
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 12.dp, start = 24.dp, end = 24.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.continue_watching),
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioColors.TextPrimary
                )
            }
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(),
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { _, item ->
                    when (item) {
                        is ContinueWatchingItem.InProgress ->
                            "cw_${item.progress.contentId}_${item.progress.videoId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
                        is ContinueWatchingItem.NextUp ->
                            "nextup_${item.info.contentId}_${item.info.videoId}_${item.info.season}_${item.info.episode}"
                    }
                }
            ) { index, progress ->
                val focusModifier = if (pendingFocusIndex == index && index < focusRequesters.size) {
                    Modifier.focusRequester(focusRequesters[index])
                } else if (index == focusedItemIndex) {
                    Modifier.focusRequester(itemFocusRequester)
                } else {
                    Modifier
                }

                ContinueWatchingCard(
                    item = progress,
                    onClick = { onItemClick(progress) },
                    onLongPress = { optionsItem = progress },
                    modifier = focusModifier
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused && lastFocusedIndex != index) {
                                lastFocusedIndex = index
                            }
                        },
                    cardWidth = 220.dp,
                    imageHeight = 124.dp
                )
            }
        }
    }

    val menuItem = optionsItem
    if (menuItem != null) {
        ContinueWatchingOptionsDialog(
            item = menuItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex, items.size - 2)
                pendingFocusIndex = targetIndex
                onRemoveItem(menuItem)
                optionsItem = null
            },
            onDetails = {
                onDetailsClick(menuItem)
                optionsItem = null
            }
        )
    }

    LaunchedEffect(items.size, pendingFocusIndex) {
        val target = pendingFocusIndex
        if (target != null && target >= 0 && target < focusRequesters.size) {
            var focused = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                focused = runCatching { focusRequesters[target].requestFocus() }.isSuccess
                if (focused) break
            }
            if (focused) {
                lastRequestedFocusIndex = target
            }
            pendingFocusIndex = null
        }
    }
}
