@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nuvio.tv.core.player.ExternalPlayerLauncher
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.components.StreamsSkeletonList
import com.nuvio.tv.ui.screens.player.LoadingOverlay
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay as coroutineDelay
import kotlinx.coroutines.launch as coroutineLaunch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamScreen(
    viewModel: StreamScreenViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onStreamSelected: (StreamPlaybackInfo) -> Unit,
    onAutoPlayResolved: (StreamPlaybackInfo) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerPreference by viewModel.playerPreference.collectAsStateWithLifecycle(
        initialValue = PlayerPreference.INTERNAL
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var focusedStreamIndex by rememberSaveable { mutableStateOf(0) }
    var restoreFocusedStream by rememberSaveable { mutableStateOf(false) }
    var pendingRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    var showPlayerChoiceDialog by remember { mutableStateOf(false) }
    var pendingPlaybackInfo by remember { mutableStateOf<StreamPlaybackInfo?>(null) }

    fun routePlayback(playbackInfo: StreamPlaybackInfo) {
        when (playerPreference) {
            PlayerPreference.INTERNAL -> {
                onStreamSelected(playbackInfo)
            }
            PlayerPreference.EXTERNAL -> {
                playbackInfo.url?.let { url ->
                    ExternalPlayerLauncher.launch(
                        context = context,
                        url = url,
                        title = playbackInfo.title,
                        headers = playbackInfo.headers
                    )
                }
            }
            PlayerPreference.ASK_EVERY_TIME -> {
                pendingPlaybackInfo = playbackInfo
                showPlayerChoiceDialog = true
            }
        }
    }

    fun routeAutoPlay(playbackInfo: StreamPlaybackInfo) {
        if (uiState.isDirectAutoPlayFlow) {
            onAutoPlayResolved(playbackInfo)
            return
        } else {
            pendingRestoreOnResume = true
            routePlayback(playbackInfo)
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
        }
    }

    BackHandler {
        onBackPress()
    }

    LaunchedEffect(uiState.autoPlayStream) {
        val stream = uiState.autoPlayStream ?: return@LaunchedEffect
        val playbackInfo = viewModel.getStreamForPlayback(stream)
        if (playbackInfo.url != null) {
            routeAutoPlay(playbackInfo)
        }
    }

    LaunchedEffect(uiState.autoPlayPlaybackInfo) {
        val playbackInfo = uiState.autoPlayPlaybackInfo ?: return@LaunchedEffect
        if (playbackInfo.url != null) {
            routeAutoPlay(playbackInfo)
        }
    }

    DisposableEffect(lifecycleOwner, pendingRestoreOnResume) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingRestoreOnResume) {
                restoreFocusedStream = true
                pendingRestoreOnResume = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        // Full screen backdrop
        StreamBackdrop(
            backdrop = uiState.backdrop ?: uiState.poster,
            isLoading = uiState.isLoading
        )

        if (uiState.showDirectAutoPlayOverlay) {
            LoadingOverlay(
                visible = true,
                backdropUrl = uiState.backdrop ?: uiState.poster,
                logoUrl = uiState.logo,
                message = if (uiState.directAutoPlayMessage != null) {
                    stringResource(R.string.stream_finding_source)
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Content overlay
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                // Left side - Title/Logo (centered vertically)
                LeftContentSection(
                    title = uiState.title,
                    logo = uiState.logo,
                    isEpisode = uiState.isEpisode,
                    season = uiState.season,
                    episode = uiState.episode,
                    episodeName = uiState.episodeName,
                    runtime = uiState.runtime,
                    genres = uiState.genres,
                    year = uiState.year,
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                )

                // Right side - Streams container
                RightStreamSection(
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    streams = uiState.filteredStreams,
                    availableAddons = uiState.availableAddons,
                    sourceChips = uiState.sourceChips,
                    selectedAddonFilter = uiState.selectedAddonFilter,
                    onAddonFilterSelected = { viewModel.onEvent(StreamScreenEvent.OnAddonFilterSelected(it)) },
                    onStreamSelected = { stream ->
                        val currentIndex = uiState.filteredStreams.indexOfFirst {
                            it.url == stream.url &&
                                it.infoHash == stream.infoHash &&
                                it.ytId == stream.ytId &&
                                it.addonName == stream.addonName
                        }
                        if (currentIndex >= 0) {
                            focusedStreamIndex = currentIndex
                        }
                        val playbackInfo = viewModel.getStreamForPlayback(stream)
                        pendingRestoreOnResume = true
                        viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                        routePlayback(playbackInfo)
                    },
                    focusedStreamIndex = focusedStreamIndex,
                    shouldRestoreFocusedStream = restoreFocusedStream,
                    onRestoreFocusedStreamHandled = { restoreFocusedStream = false },
                    onRetry = { viewModel.onEvent(StreamScreenEvent.OnRetry) },
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                )
            }
        }

        // Player choice dialog for "Ask every time" preference
        if (showPlayerChoiceDialog && pendingPlaybackInfo != null) {
            PlayerChoiceDialog(
                onInternalSelected = {
                    showPlayerChoiceDialog = false
                    pendingPlaybackInfo?.let { onStreamSelected(it) }
                    pendingPlaybackInfo = null
                },
                onExternalSelected = {
                    showPlayerChoiceDialog = false
                    pendingPlaybackInfo?.let { info ->
                        info.url?.let { url ->
                            ExternalPlayerLauncher.launch(
                                context = context,
                                url = url,
                                title = info.title,
                                headers = info.headers
                            )
                        }
                    }
                    pendingPlaybackInfo = null
                },
                onDismiss = {
                    showPlayerChoiceDialog = false
                    pendingPlaybackInfo = null
                }
            )
        }
    }
}

@Composable
private fun StreamBackdrop(
    backdrop: String?,
    isLoading: Boolean
) {
    val context = LocalContext.current
    val backgroundColor = NuvioColors.Background
    val backdropModel = remember(context, backdrop) {
        backdrop?.let { image ->
            ImageRequest.Builder(context)
                .data(image)
                .crossfade(false)
                .build()
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (isLoading) 0.3f else 0.5f,
        animationSpec = tween(500),
        label = "backdrop_alpha"
    )
    val leftGradient = remember(backgroundColor) {
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0.0f to backgroundColor,
                0.25f to backgroundColor.copy(alpha = 0.95f),
                0.4f to backgroundColor.copy(alpha = 0.8f),
                0.5f to backgroundColor.copy(alpha = 0.5f),
                0.6f to Color.Transparent,
                1.0f to Color.Transparent
            )
        )
    }
    val rightGradient = remember(backgroundColor) {
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.4f to Color.Transparent,
                0.5f to backgroundColor.copy(alpha = 0.3f),
                0.7f to backgroundColor.copy(alpha = 0.7f),
                0.85f to backgroundColor.copy(alpha = 0.9f),
                1.0f to backgroundColor
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop image
        if (backdropModel != null) {
            AsyncImage(
                model = backdropModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Dark overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioColors.Background.copy(alpha = alpha))
        )

        // Left gradient for text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(leftGradient)
        )

        // Right gradient for streams panel
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(rightGradient)
        )
    }
}

@Composable
private fun LeftContentSection(
    title: String,
    logo: String?,
    isEpisode: Boolean,
    season: Int?,
    episode: Int?,
    episodeName: String?,
    runtime: Int?,
    genres: String?,
    year: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val logoModel = remember(context, logo) {
        logo?.let { image ->
            ImageRequest.Builder(context)
                .data(image)
                .crossfade(false)
                .build()
        }
    }
    val infoText = remember(genres, year) {
        listOfNotNull(genres, year).joinToString(" • ")
    }
    Box(
        modifier = modifier.padding(start = 48.dp, end = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            if (logoModel != null) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = NuvioColors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            // Show episode info or movie info
            if (isEpisode && season != null && episode != null) {
                // Episode info
                Text(
                    text = "S$season E$episode",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.extendedColors.textSecondary,
                    textAlign = TextAlign.Center
                )
                if (episodeName != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = episodeName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                if (runtime != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${runtime}m",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.extendedColors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Movie info - genres and year
                if (infoText.isNotEmpty()) {
                    Text(
                        text = infoText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.extendedColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RightStreamSection(
    isLoading: Boolean,
    error: String?,
    streams: List<Stream>,
    availableAddons: List<String>,
    sourceChips: List<SourceChipItem>,
    selectedAddonFilter: String?,
    onAddonFilterSelected: (String?) -> Unit,
    onStreamSelected: (Stream) -> Unit,
    focusedStreamIndex: Int,
    shouldRestoreFocusedStream: Boolean,
    onRestoreFocusedStreamHandled: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var enter by remember { mutableStateOf(false) }
    var shouldFocusFirstStream by remember { mutableStateOf(false) }
    var wasLoading by remember { mutableStateOf(true) }
    var listHasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val orderedAddonNames = remember(availableAddons, sourceChips) {
        buildList {
            addAll(availableAddons)
            sourceChips.forEach { if (it.name !in this) add(it.name) }
        }
    }
    val chipFocusRequesters = remember(orderedAddonNames.size) {
        List(orderedAddonNames.size + 1) { FocusRequester() }
    }
    fun onAddonFilterSelectedGuarded(addon: String?) {
        onAddonFilterSelected(addon)
        val idx = if (addon == null) 0 else orderedAddonNames.indexOf(addon) + 1
        focusJob?.cancel()
        focusJob = scope.coroutineLaunch {
            withFrameNanos {}
            if (!listHasFocus && idx >= 0 && idx < chipFocusRequesters.size) {
                try { chipFocusRequesters[idx].requestFocus() } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        enter = true
    }
    LaunchedEffect(isLoading, streams.size) {
        if (wasLoading && !isLoading && streams.isNotEmpty()) {
            shouldFocusFirstStream = true
        }
        wasLoading = isLoading
    }

    Column(
        modifier = modifier
            .padding(top = 48.dp, end = 48.dp, bottom = 48.dp)
    ) {
        val chipRowHeight = 56.dp

        // Addon filter chips
        Box(modifier = Modifier.height(chipRowHeight)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = sourceChips.isNotEmpty() || (!isLoading && availableAddons.isNotEmpty()),
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                AddonFilterChips(
                    addons = availableAddons,
                    sourceChips = sourceChips,
                    selectedAddon = selectedAddonFilter,
                    onAddonSelected = { onAddonFilterSelectedGuarded(it) },
                    focusRequesters = chipFocusRequesters,
                    orderedNames = orderedAddonNames
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        androidx.compose.animation.AnimatedVisibility(
            visible = enter,
            enter = fadeIn(animationSpec = tween(260)) +
                slideInHorizontally(
                    animationSpec = tween(260),
                    initialOffsetX = { fullWidth -> (fullWidth * 0.06f).toInt() }
                ),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            // Content area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NuvioColors.BackgroundCard.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        LoadingState()
                    }
                    error != null -> {
                        ErrorState(
                            message = error,
                            onRetry = onRetry
                        )
                    }
                    streams.isEmpty() -> {
                        EmptyState()
                    }
                    else -> {
                        StreamsList(
                            streams = streams,
                            onStreamSelected = onStreamSelected,
                            focusedStreamIndex = focusedStreamIndex,
                            shouldRestoreFocusedStream = shouldRestoreFocusedStream,
                            onRestoreFocusedStreamHandled = onRestoreFocusedStreamHandled,
                            requestInitialFocus = shouldFocusFirstStream,
                            onInitialFocusConsumed = { shouldFocusFirstStream = false },
                            availableAddons = availableAddons,
                            selectedAddonFilter = selectedAddonFilter,
                            onAddonFilterSelected = { onAddonFilterSelectedGuarded(it) },
                            chipFocusRequesters = chipFocusRequesters,
                            orderedAddonNames = orderedAddonNames,
                            onFocusChanged = { listHasFocus = it }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddonFilterChips(
    addons: List<String>,
    sourceChips: List<SourceChipItem>,
    selectedAddon: String?,
    onAddonSelected: (String?) -> Unit,
    focusRequesters: List<FocusRequester>,
    orderedNames: List<String>
) {
    val chipMap = sourceChips.associateBy { it.name }
    var chipRowHasFocus by remember { mutableStateOf(false) }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier
            .onFocusChanged { chipRowHasFocus = it.hasFocus }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val allOptions = listOf<String?>(null) + orderedNames
                val currentIdx = allOptions.indexOf(selectedAddon)
                when (event.key) {
                    androidx.compose.ui.input.key.Key.DirectionLeft -> {
                        if (currentIdx > 0) { onAddonSelected(allOptions[currentIdx - 1]); true } else false
                    }
                    androidx.compose.ui.input.key.Key.DirectionRight -> {
                        if (currentIdx < allOptions.lastIndex) { onAddonSelected(allOptions[currentIdx + 1]); true } else false
                    }
                    else -> false
                }
            }
    ) {
        item {
            SourceStatusFilterChip(
                name = "All",
                isSelected = selectedAddon == null,
                status = SourceChipStatus.SUCCESS,
                isSelectable = true,
                onClick = { onAddonSelected(null) },
                modifier = Modifier
                    .focusRequester(focusRequesters[0])
                    .focusProperties { canFocus = selectedAddon == null || chipRowHasFocus }
            )
        }

        items(orderedNames.size) { i ->
            val addon = orderedNames[i]
            val chipStatus = chipMap[addon]?.status ?: SourceChipStatus.SUCCESS
            val isSelectable = addon in addons && chipStatus == SourceChipStatus.SUCCESS
            SourceStatusFilterChip(
                name = addon,
                isSelected = selectedAddon == addon,
                status = chipStatus,
                isSelectable = isSelectable,
                onClick = { if (isSelectable) onAddonSelected(addon) },
                modifier = Modifier.focusRequester(focusRequesters[i + 1])
            )
        }
    }
}

@Composable
private fun LoadingState() {
    StreamsSkeletonList()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = NuvioColors.Error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.extendedColors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        var isFocused by remember { mutableStateOf(false) }
        Card(
            onClick = onRetry,
            modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.Secondary
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(8.dp)
                )
            ),
            shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
            scale = CardDefaults.scale(focusedScale = 1.02f)
        ) {
            Text(
                text = stringResource(R.string.stream_retry),
                style = MaterialTheme.typography.labelLarge,
                color = if (isFocused) NuvioColors.OnSecondary else NuvioColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Text(
            text = stringResource(R.string.stream_no_streams),
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.extendedColors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.stream_no_streams_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.extendedColors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamsList(
    streams: List<Stream>,
    onStreamSelected: (Stream) -> Unit,
    focusedStreamIndex: Int = 0,
    shouldRestoreFocusedStream: Boolean = false,
    onRestoreFocusedStreamHandled: () -> Unit = {},
    requestInitialFocus: Boolean = false,
    onInitialFocusConsumed: () -> Unit = {},
    availableAddons: List<String> = emptyList(),
    selectedAddonFilter: String? = null,
    onAddonFilterSelected: (String?) -> Unit = {},
    chipFocusRequesters: List<FocusRequester> = emptyList(),
    orderedAddonNames: List<String> = emptyList(),
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val firstCardFocusRequester = remember { FocusRequester() }
    val restoreFocusRequester = remember { FocusRequester() }
    val firstStreamKey = streams.firstOrNull()?.let { first ->
        "${first.addonName}_${first.url ?: first.infoHash ?: first.ytId ?: "unknown"}"
    }

    LaunchedEffect(requestInitialFocus, firstStreamKey) {
        if (!requestInitialFocus || streams.isEmpty()) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        try {
            firstCardFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        onInitialFocusConsumed()
    }

    LaunchedEffect(shouldRestoreFocusedStream, focusedStreamIndex, streams.size) {
        if (!shouldRestoreFocusedStream) return@LaunchedEffect
        if (streams.isEmpty()) {
            onRestoreFocusedStreamHandled()
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        try {
            restoreFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        onRestoreFocusedStreamHandled()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                if (availableAddons.isEmpty()) return@onKeyEvent false
                val allOptions = listOf<String?>(null) + availableAddons
                val currentIdx = allOptions.indexOf(selectedAddonFilter)
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (currentIdx > 0) { onAddonFilterSelected(allOptions[currentIdx - 1]); true } else false
                    }
                    Key.DirectionRight -> {
                        if (currentIdx < allOptions.lastIndex) { onAddonFilterSelected(allOptions[currentIdx + 1]); true } else false
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        itemsIndexed(streams, key = { index, stream ->
            "${stream.addonName}_${stream.url ?: stream.infoHash ?: stream.ytId ?: "unknown"}_$index"
        }) { index, stream ->
            StreamCard(
                stream = stream,
                onClick = { onStreamSelected(stream) },
                focusRequester = when {
                    shouldRestoreFocusedStream && index == focusedStreamIndex.coerceIn(0, (streams.lastIndex).coerceAtLeast(0)) -> restoreFocusRequester
                    index == 0 -> firstCardFocusRequester
                    else -> null
                },
                onUpKey = if (index == 0 && chipFocusRequesters.isNotEmpty()) {{
                    val idx = if (selectedAddonFilter == null) 0
                              else orderedAddonNames.indexOf(selectedAddonFilter) + 1
                    if (idx >= 0 && idx < chipFocusRequesters.size) {
                        try { chipFocusRequesters[idx].requestFocus() } catch (_: Exception) {}
                    }
                }} else null
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamCard(
    stream: Stream,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onUpKey: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val streamName = remember(stream) { stream.getDisplayName() }
    val streamDescription = remember(stream) { stream.getDisplayDescription() }
    val addonLogoModel = remember(context, stream.addonLogo) {
        stream.addonLogo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(false)
                .build()
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (onUpKey != null) Modifier.onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.key == Key.DirectionUp) {
                    onUpKey(); true
                } else false
            } else Modifier),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.08f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = streamName,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary
                )

                streamDescription?.let { description ->
                    if (description != streamName) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.extendedColors.textSecondary
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (stream.isTorrent()) {
                        StreamTypeChip(text = stringResource(R.string.stream_type_torrent), color = NuvioColors.Secondary)
                    }
                    if (stream.isYouTube()) {
                        StreamTypeChip(text = stringResource(R.string.stream_type_youtube), color = Color(0xFFFF0000))
                    }
                    if (stream.isExternal()) {
                        StreamTypeChip(text = stringResource(R.string.stream_type_external), color = NuvioColors.Primary)
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                if (addonLogoModel != null) {
                    AsyncImage(
                        model = addonLogoModel,
                        contentDescription = stream.addonName,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stream.addonName,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.extendedColors.textTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StreamTypeChip(
    text: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun PlayerChoiceDialog(
    onInternalSelected: () -> Unit,
    onExternalSelected: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(400.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.stream_player_picker_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    var internalFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = onInternalSelected,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { internalFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = NuvioColors.BackgroundElevated,
                            focusedContainerColor = NuvioColors.Secondary
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.stream_player_internal),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (internalFocused) NuvioColors.OnSecondary else NuvioColors.TextPrimary,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    var externalFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = onExternalSelected,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { externalFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = NuvioColors.BackgroundElevated,
                            focusedContainerColor = NuvioColors.Secondary
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.stream_player_external),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (externalFocused) NuvioColors.OnSecondary else NuvioColors.TextPrimary,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
