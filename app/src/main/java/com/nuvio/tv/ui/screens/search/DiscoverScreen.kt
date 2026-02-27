package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.roundToInt

@Composable
fun DiscoverScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val discoverFirstItemFocusRequester = remember { FocusRequester() }
    var discoverFocusedItemIndex by rememberSaveable { mutableStateOf(0) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }

    val posterCardStyle = remember(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val latestPendingDiscoverRestore by rememberUpdatedState(pendingDiscoverRestoreOnResume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && latestPendingDiscoverRestore) {
                restoreDiscoverFocus = true
                pendingDiscoverRestoreOnResume = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        if (!uiState.discoverEnabled) {
            EmptyScreenState(
                title = "Discover is disabled",
                subtitle = "Enable Search Discover in settings",
                icon = Icons.Default.Search
            )
        } else {
            DiscoverSection(
                uiState = uiState,
                posterCardStyle = posterCardStyle,
                focusResults = false,
                firstItemFocusRequester = discoverFirstItemFocusRequester,
                focusedItemIndex = discoverFocusedItemIndex,
                shouldRestoreFocusedItem = restoreDiscoverFocus,
                onRestoreFocusedItemHandled = { restoreDiscoverFocus = false },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    pendingDiscoverRestoreOnResume = true
                    onNavigateToDetail(itemId, itemType, addonBaseUrl)
                },
                onDiscoverItemFocused = { discoverFocusedItemIndex = it },
                onSelectType = { viewModel.onEvent(SearchEvent.SelectDiscoverType(it)) },
                onSelectCatalog = { viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(it)) },
                onSelectGenre = { viewModel.onEvent(SearchEvent.SelectDiscoverGenre(it)) },
                onLoadMore = { viewModel.onEvent(SearchEvent.LoadNextDiscoverResults) },
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
