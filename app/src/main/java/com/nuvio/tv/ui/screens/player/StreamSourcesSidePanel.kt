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
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

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
    // Only request focus when loading finishes (not on addon filter changes)
    LaunchedEffect(uiState.isLoadingSourceStreams) {
        if (!uiState.isLoadingSourceStreams && uiState.sourceFilteredStreams.isNotEmpty()) {
            try {
                streamsFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus requester may not be ready yet
            }
        }
    }

    val orderedAddonNames = remember(uiState.sourceAvailableAddons, uiState.sourceChips) {
        buildList {
            addAll(uiState.sourceAvailableAddons)
            uiState.sourceChips.forEach { if (it.name !in this) add(it.name) }
        }
    }
    val chipFocusRequesters = remember(orderedAddonNames.size) {
        List(orderedAddonNames.size + 1) { FocusRequester() }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(NuvioColors.BackgroundElevated)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sources_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton(
                        text = stringResource(R.string.sources_reload),
                        onClick = onReload,
                        isPrimary = false
                    )
                    DialogButton(
                        text = stringResource(R.string.sources_close),
                        onClick = onClose,
                        isPrimary = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Current content info
            Text(
                text = buildString {
                    if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                        append("S${uiState.currentSeason} E${uiState.currentEpisode}")
                        if (!uiState.currentEpisodeTitle.isNullOrBlank()) {
                            append(" • ${uiState.currentEpisodeTitle}")
                        }
                    } else {
                        append(uiState.title)
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.extendedColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = uiState.sourceChips.isNotEmpty() ||
                    (!uiState.isLoadingSourceStreams && uiState.sourceAvailableAddons.isNotEmpty()),
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                AddonFilterChips(
                    addons = uiState.sourceAvailableAddons,
                    sourceChips = uiState.sourceChips,
                    selectedAddon = uiState.sourceSelectedAddonFilter,
                    onAddonSelected = onAddonFilterSelected,
                    externalFocusRequesters = chipFocusRequesters,
                    externalOrderedNames = orderedAddonNames
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                uiState.isLoadingSourceStreams -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                uiState.sourceStreamsError != null -> {
                    Text(
                        text = uiState.sourceStreamsError ?: stringResource(R.string.panel_failed_load_streams),
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
                    val currentStreamUrl = uiState.currentStreamUrl
                    val currentStreamName = uiState.currentStreamName
                    val currentStreamIndex = findCurrentStreamIndex(
                        streams = uiState.sourceFilteredStreams,
                        currentStreamUrl = currentStreamUrl,
                        currentStreamName = currentStreamName
                    )
                    val initialFocusStream = uiState.sourceFilteredStreams.getOrNull(currentStreamIndex)
                        ?: uiState.sourceFilteredStreams.firstOrNull()

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            top = 14.dp,
                            end = 8.dp,
                            bottom = 8.dp
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .onKeyEvent { event ->
                                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                                val addons = uiState.sourceAvailableAddons
                                if (addons.isEmpty()) return@onKeyEvent false
                                val allOptions = listOf<String?>(null) + addons
                                val currentIdx = allOptions.indexOf(uiState.sourceSelectedAddonFilter)
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (currentIdx > 0) { onAddonFilterSelected(allOptions[currentIdx - 1]); true } else false
                                    }
                                    Key.DirectionRight -> {
                                        if (currentIdx < allOptions.lastIndex) { onAddonFilterSelected(allOptions[currentIdx + 1]); true } else false
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        itemsIndexed(uiState.sourceFilteredStreams) { index, stream ->
                            StreamItem(
                                stream = stream,
                                focusRequester = streamsFocusRequester,
                                requestInitialFocus = stream == initialFocusStream,
                                isCurrentStream = index == currentStreamIndex,
                                onClick = { onStreamSelected(stream) },
                                onUpKey = if (index == 0 && chipFocusRequesters.isNotEmpty()) {{
                                    val selected = uiState.sourceSelectedAddonFilter
                                    val idx = if (selected == null) 0 else orderedAddonNames.indexOf(selected) + 1
                                    if (idx >= 0 && idx < chipFocusRequesters.size) {
                                        try { chipFocusRequesters[idx].requestFocus() } catch (_: Exception) {}
                                    }
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
    currentStreamUrl: String?,
    currentStreamName: String?
): Int {
    if (streams.isEmpty()) return -1

    val hasUrl = !currentStreamUrl.isNullOrBlank()
    val hasName = !currentStreamName.isNullOrBlank()

    if (hasUrl && hasName) {
        val bothMatch = streams.indexOfFirst { stream ->
            stream.getStreamUrl() == currentStreamUrl &&
                stream.getDisplayName().equals(currentStreamName, ignoreCase = true)
        }
        if (bothMatch >= 0) return bothMatch
    }

    if (hasUrl) {
        val urlMatch = streams.indexOfFirst { stream ->
            stream.getStreamUrl() == currentStreamUrl
        }
        if (urlMatch >= 0) return urlMatch
    }

    if (hasName) {
        val nameMatch = streams.indexOfFirst { stream ->
            stream.getDisplayName().equals(currentStreamName, ignoreCase = true)
        }
        if (nameMatch >= 0) return nameMatch
    }

    return -1
}
