@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch as coroutineLaunch
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.SourceChipStatus
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun StreamSourcesSidePanel(
    uiState: PlayerUiState,
    streamsFocusRequester: FocusRequester,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onAddonFilterSelected: (String?) -> Unit,
    onStreamSelected: (Stream) -> Unit,
    modifier: Modifier = Modifier
) {
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val streamListState = rememberLazyListState()
    var userMovedFromFirstResult by remember { mutableStateOf(false) }
    var firstResultFocusAssigned by remember { mutableStateOf(false) }
    var firstStreamFocusRequestId by remember { mutableStateOf(0) }
    var listHasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val closeButtonFocusRequester = remember { FocusRequester() }

    val orderedAddonNames = remember(uiState.sourceAvailableAddons, uiState.sourceChips) {
        buildList {
            addAll(uiState.sourceAvailableAddons)
            uiState.sourceChips.forEach { if (it.name !in this) add(it.name) }
        }
    }
    val refreshFocusRequester = remember { FocusRequester() }
    val allFocusRequester = remember { FocusRequester() }
    val addonFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val chipFocusRequesters = remember(orderedAddonNames) {
        // Remove stale entries for addons that no longer exist
        addonFocusRequesters.keys.retainAll(orderedAddonNames.toSet())
        buildList {
            add(refreshFocusRequester)
            add(allFocusRequester)
            orderedAddonNames.forEach { addon ->
                add(addonFocusRequesters.getOrPut(addon) { FocusRequester() })
            }
        }
    }

    val streamKeys = remember(uiState.sourceFilteredStreams) {
        val seen = mutableMapOf<String, Int>()
        uiState.sourceFilteredStreams.map { stream ->
            val base = stream.stableKey(0)
            val count = seen.getOrDefault(base, 0)
            seen[base] = count + 1
            stream.stableKey(count)
        }
    }
    val firstStreamKey = streamKeys.firstOrNull()
    val streamFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    streamKeys.forEach { key ->
        streamFocusRequesters.getOrPut(key) { FocusRequester() }
    }
    var firstCardHasFocus by remember(firstStreamKey) { mutableStateOf(false) }

    // Request initial focus when loading finishes and streams are available,
    // only if the user has not navigated away or focused the chips row.
    LaunchedEffect(uiState.isLoadingSourceStreams, firstStreamKey, userMovedFromFirstResult, firstResultFocusAssigned) {
        if (!uiState.isLoadingSourceStreams && firstStreamKey != null &&
            !userMovedFromFirstResult && !firstResultFocusAssigned
        ) {
            firstResultFocusAssigned = true
            firstStreamFocusRequestId += 1
        }
    }

    // When on "All" tab and new results arrive above the focused stream, move focus to the new first item.
    var trackedFirstStreamKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(firstStreamKey, uiState.sourceSelectedAddonFilter, listHasFocus) {
        if (uiState.sourceSelectedAddonFilter != null) {
            trackedFirstStreamKey = firstStreamKey
            return@LaunchedEffect
        }
        if (firstStreamKey != null && trackedFirstStreamKey != null &&
            firstStreamKey != trackedFirstStreamKey &&
            listHasFocus && !userMovedFromFirstResult
        ) {
            firstStreamFocusRequestId += 1
        }
        trackedFirstStreamKey = firstStreamKey
    }

    LaunchedEffect(firstStreamFocusRequestId) {
        val requestedKey = firstStreamKey
        if (firstStreamFocusRequestId <= 0 || requestedKey == null) return@LaunchedEffect
        streamListState.scrollToItem(0)
        repeat(30) {
            withFrameNanos { }
            if (firstCardHasFocus) return@LaunchedEffect
            runCatching { streamFocusRequesters.getValue(requestedKey).requestFocus() }
        }
    }

    fun requestChipFocus(index: Int) {
        if (index !in chipFocusRequesters.indices) return
        userMovedFromFirstResult = true
        focusJob?.cancel()
        focusJob = scope.coroutineLaunch {
            withFrameNanos { }
            runCatching { chipFocusRequesters[index].requestFocus() }
        }
    }

    // Called when navigating tabs horizontally from within the stream list
    fun onAddonFilterSelectedGuarded(addon: String?) {
        userMovedFromFirstResult = true
        onAddonFilterSelected(addon)
        focusJob?.cancel()
        focusJob = scope.coroutineLaunch {
            withFrameNanos {}
            val targetRequester = if (addon == null) {
                chipFocusRequesters.getOrNull(1)
            } else {
                addonFocusRequesters[addon]
            }
            runCatching { targetRequester?.requestFocus() }
        }
    }

    // Reset scroll position to top when addon filter changes
    LaunchedEffect(uiState.sourceSelectedAddonFilter) {
        streamListState.scrollToItem(0)
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .clip(RoundedCornerShape(topStart = NuvioTheme.spacing.lg, bottomStart = NuvioTheme.spacing.lg))
            .background(NuvioTheme.colors.BackgroundElevated)
    ) {
        Column(modifier = Modifier.padding(NuvioTheme.spacing.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sources_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )

                DialogButton(
                    text = stringResource(R.string.sources_close),
                    onClick = onClose,
                    isPrimary = false,
                    modifier = Modifier
                        .focusRequester(closeButtonFocusRequester)
                        .onKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.key == Key.DirectionDown
                            ) {
                                val activeIdx = if (uiState.sourceSelectedAddonFilter == null) 1
                                    else (orderedAddonNames.indexOf(uiState.sourceSelectedAddonFilter) + 2).coerceAtLeast(1)
                                requestChipFocus(activeIdx)
                                true
                            } else false
                        }
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            // Current content info
            val context = LocalContext.current
            val seasonEpisodeCode = if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                stringResource(
                    R.string.season_episode_format,
                    uiState.currentSeason,
                    uiState.currentEpisode
                )
            } else {
                null
            }
            val localizedEpisodeTitle = uiState.currentEpisodeTitle
                ?.takeIf { it.isNotBlank() }
                ?.localizeEpisodeTitle(context)
            val contentInfoText = when {
                seasonEpisodeCode != null && localizedEpisodeTitle != null -> "$seasonEpisodeCode • $localizedEpisodeTitle"
                seasonEpisodeCode != null -> seasonEpisodeCode
                else -> uiState.title
            }
            Text(
                text = contentInfoText,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.extendedColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            AnimatedVisibility(
                visible = uiState.sourceChips.isNotEmpty() || uiState.sourceAvailableAddons.isNotEmpty(),
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                AddonFilterChips(
                    addons = uiState.sourceAvailableAddons,
                    sourceChips = uiState.sourceChips,
                    selectedAddon = uiState.sourceSelectedAddonFilter,
                    isStillFetching = uiState.isLoadingSourceStreams ||
                        uiState.sourceChips.any { it.status == SourceChipStatus.LOADING },
                    onRefresh = {
                        userMovedFromFirstResult = false
                        firstResultFocusAssigned = false
                        onReload()
                    },
                    onAddonSelected = { onAddonFilterSelected(it) },
                    externalFocusRequesters = chipFocusRequesters,
                    externalOrderedNames = orderedAddonNames,
                    onUpKey = {
                        try { closeButtonFocusRequester.requestFocus() } catch (_: Exception) {}
                    },
                    debugTag = "SourcesSidePanel"
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            when {
                uiState.isLoadingSourceStreams -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = NuvioTheme.spacing.xl),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                uiState.sourceStreamsError != null -> {
                    Text(
                        text = uiState.sourceStreamsError,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }

                uiState.sourceFilteredStreams.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.sources_no_streams),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                else -> {
                    val currentStreamIndex = findCurrentStreamIndex(
                        streams = uiState.sourceFilteredStreams,
                        currentStreamInfoHash = uiState.currentStreamInfoHash,
                        currentStreamFileIdx = uiState.currentStreamFileIdx,
                        currentStreamAddonName = uiState.currentStreamAddonName,
                        currentStreamUrl = uiState.currentStreamUrl,
                        currentStreamName = uiState.currentStreamName
                    )

                    val lastKeyRepeatDispatchRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }

                    LazyColumn(
                        state = streamListState,
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                        contentPadding = PaddingValues(
                            start = NuvioTheme.spacing.sm,
                            top = 14.dp,
                            end = NuvioTheme.spacing.sm,
                            bottom = NuvioTheme.spacing.sm
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .onFocusChanged { listHasFocus = it.hasFocus }
                            .onKeyEvent { event ->
                                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false

                                // Throttle rapid key repeats (long-press)
                                if (event.nativeKeyEvent.repeatCount > 0) {
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastKeyRepeatDispatchRef.get() < 112L) return@onKeyEvent true
                                    lastKeyRepeatDispatchRef.set(now)
                                }

                                if (event.key == Key.DirectionDown) {
                                    userMovedFromFirstResult = true
                                }

                                if (orderedAddonNames.isEmpty()) return@onKeyEvent false
                                val allOptions = listOf<String?>(null) + orderedAddonNames
                                val currentIdx = allOptions.indexOf(uiState.sourceSelectedAddonFilter)
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (isRtl) {
                                            if (currentIdx < allOptions.lastIndex) {
                                                onAddonFilterSelectedGuarded(allOptions[currentIdx + 1])
                                                true
                                            } else {
                                                // Boundary hit on rightmost tab in RTL
                                                true
                                            }
                                        } else {
                                            if (currentIdx > 0) {
                                                onAddonFilterSelectedGuarded(allOptions[currentIdx - 1])
                                                true
                                            } else {
                                                true
                                            }
                                        }
                                    }
                                    Key.DirectionRight -> {
                                        if (isRtl) {
                                            if (currentIdx > 0) {
                                                onAddonFilterSelectedGuarded(allOptions[currentIdx - 1])
                                                true
                                            } else {
                                                true
                                            }
                                        } else {
                                            if (currentIdx < allOptions.lastIndex) {
                                                onAddonFilterSelectedGuarded(allOptions[currentIdx + 1])
                                                true
                                            } else {
                                                // Boundary hit on rightmost tab in LTR: consume so focus doesn't escape overlay
                                                true
                                            }
                                        }
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        itemsIndexed(uiState.sourceFilteredStreams, key = { index, _ ->
                            streamKeys[index]
                        }) { index, stream ->
                            StreamItem(
                                stream = stream,
                                focusRequester = streamFocusRequesters.getValue(streamKeys[index]),
                                isCurrentStream = index == currentStreamIndex,
                                showFileSizeBadges = uiState.showFileSizeBadges,
                                showAddonLogo = uiState.showAddonLogo,
                                badgePlacement = uiState.streamBadgePlacement,
                                onClick = { onStreamSelected(stream) },
                                onFocusChanged = { focused ->
                                    if (index == 0) {
                                        firstCardHasFocus = focused
                                    }
                                },
                                onUpKey = if (index == 0 && chipFocusRequesters.isNotEmpty()) {{
                                    val idx = if (uiState.sourceSelectedAddonFilter == null) 1
                                              else orderedAddonNames.indexOf(uiState.sourceSelectedAddonFilter) + 2
                                    requestChipFocus(idx)
                                }} else null
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun findCurrentStreamIndex(
    streams: List<Stream>,
    currentStreamInfoHash: String?,
    currentStreamFileIdx: Int?,
    currentStreamAddonName: String?,
    currentStreamUrl: String?,
    currentStreamName: String?
): Int {
    if (streams.isEmpty()) return -1

    // Strategy 1: match by infoHash + fileIdx + addonName (most precise for debrid streams)
    if (!currentStreamInfoHash.isNullOrBlank()) {
        val hashMatch = streams.indexOfFirst { stream ->
            val streamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash
            val streamFileIdx = stream.fileIdx ?: stream.clientResolve?.fileIdx
            streamInfoHash.equals(currentStreamInfoHash, ignoreCase = true) &&
                (currentStreamFileIdx == null || streamFileIdx == currentStreamFileIdx) &&
                (currentStreamAddonName == null || stream.addonName == currentStreamAddonName)
        }
        if (hashMatch >= 0) return hashMatch
    }

    // Strategy 2: match by addon + URL (works for non-debrid HTTP streams)
    if (!currentStreamUrl.isNullOrBlank() && !currentStreamAddonName.isNullOrBlank()) {
        val urlMatch = streams.indexOfFirst { stream ->
            stream.addonName == currentStreamAddonName &&
                stream.getStreamUrl() == currentStreamUrl
        }
        if (urlMatch >= 0) return urlMatch
    }

    // Fallback: match by URL only (without addon filter)
    if (!currentStreamUrl.isNullOrBlank()) {
        val urlMatch = streams.indexOfFirst { stream ->
            stream.getStreamUrl() == currentStreamUrl
        }
        if (urlMatch >= 0) return urlMatch
    }

    return -1
}
