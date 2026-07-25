package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun GridContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onDetailsClick: (ContinueWatchingItem) -> Unit = onItemClick,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    onStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    showManualPlayOption: Boolean = false,
    onPlayManually: (ContinueWatchingItem) -> Unit = {},
    modifier: Modifier = Modifier,
    title: String? = null,
    fullWidth: Dp = Dp.Unspecified,
    focusedItemIndex: Int = -1,
    lastFocusedIndex: MutableIntState = remember { mutableIntStateOf(-1) },
    focusRequesters: MutableMap<Int, FocusRequester> = remember { mutableMapOf() },
    onItemFocused: (Int) -> Unit = {},
    blurUnwatchedEpisodes: Boolean = false,
    useEpisodeThumbnails: Boolean = true
) {
    if (items.isEmpty()) return

    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    var lastRequestedFocusIndex by remember { mutableIntStateOf(-1) }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = (lastFocusedIndex.intValue - 1).coerceAtLeast(0)
    )

    LaunchedEffect(focusedItemIndex) {
        if (focusedItemIndex >= 0 && focusedItemIndex < items.size) {
            if (lastRequestedFocusIndex == focusedItemIndex) return@LaunchedEffect
            var focused = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                val requester = focusRequesters[focusedItemIndex] ?: continue
                focused = runCatching { requester.requestFocus() }.isSuccess
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
                .padding(top = NuvioTheme.spacing.xl, bottom = NuvioTheme.spacing.md)
        ) {
            Column {
                Text(
                    text = title ?: stringResource(R.string.continue_watching),
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier
                .then(
                    if (fullWidth != Dp.Unspecified)
                        Modifier.requiredWidth(fullWidth)
                    else
                        Modifier.fillMaxWidth()
                )
                .focusRestorer {
                    val idx = if (lastFocusedIndex.intValue >= 0) lastFocusedIndex.intValue else 0
                    focusRequesters[idx]
                        ?: focusRequesters.values.firstOrNull()
                        ?: FocusRequester.Default
                }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = NuvioTheme.spacing.none),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
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
                val requester = focusRequesters.getOrPut(index) { FocusRequester() }
                val focusModifier = Modifier.focusRequester(requester)
                val stableOnClick = remember(progress) { { onItemClick(progress) } }
                val stableOnLongPress = remember(progress) { { optionsItem = progress } }

                ContinueWatchingCard(
                    item = progress,
                    onClick = stableOnClick,
                    onLongPress = stableOnLongPress,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    modifier = focusModifier
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused && lastFocusedIndex.intValue != index) {
                                lastFocusedIndex.intValue = index
                                onItemFocused(index)
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
                val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex.intValue, items.size - 2)
                pendingFocusIndex = targetIndex
                onRemoveItem(menuItem)
                optionsItem = null
            },
            onDetails = {
                onDetailsClick(menuItem)
                optionsItem = null
            },
            onStartFromBeginning = {
                onStartFromBeginning(menuItem)
                optionsItem = null
            },
            showPlayManually = showManualPlayOption,
            onPlayManually = {
                onPlayManually(menuItem)
                optionsItem = null
            }
        )
    }

    LaunchedEffect(items.size, pendingFocusIndex) {
        val target = pendingFocusIndex
        if (target != null && target >= 0) {
            val requester = focusRequesters[target]
            if (requester != null) {
                var focused = false
                for (attempt in 0 until 3) {
                    withFrameNanos { }
                    focused = runCatching { requester.requestFocus() }.isSuccess
                    if (focused) break
                }
                if (focused) {
                    lastRequestedFocusIndex = target
                }
            }
            pendingFocusIndex = null
        }
    }
}
