@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay

@Composable
internal fun SubtitleTimingDialog(
    currentPositionMs: Long,
    selectedAddonSubtitle: Subtitle?,
    cues: List<SubtitleSyncCue>,
    capturedVideoMs: Long?,
    statusMessage: String?,
    errorMessage: String?,
    isLoadingCues: Boolean,
    onCaptureNow: () -> Unit,
    onCueSelected: (SubtitleSyncCue) -> Unit,
    onDismiss: () -> Unit
) {
    val captureFocusRequester = remember { FocusRequester() }
    val anchorMs = capturedVideoMs ?: currentPositionMs
    val visibleCues = remember(cues, anchorMs) {
        selectAutoSyncVisibleCues(
            cues = cues,
            anchorTimeMs = anchorMs
        )
    }

    LaunchedEffect(Unit) {
        delay(120)
        try {
            captureFocusRequester.requestFocus()
        } catch (_: Exception) {
            // Focus target may not be attached yet.
        }
    }

    Box(
        modifier = Modifier
            .width(760.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xA61A1A1A))
    ) {
        Box(
            modifier = Modifier.padding(24.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Sync Subtitles by Line",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )

                AutoSyncSection(
                    anchorMs = anchorMs,
                    selectedAddonSubtitle = selectedAddonSubtitle,
                    cues = visibleCues,
                    capturedVideoMs = capturedVideoMs,
                    statusMessage = statusMessage,
                    errorMessage = errorMessage,
                    isLoadingCues = isLoadingCues,
                    onCaptureNow = onCaptureNow,
                    onCueSelected = onCueSelected,
                    onDismiss = onDismiss,
                    captureFocusRequester = captureFocusRequester
                )
            }
        }
    }
}

@Composable
private fun AutoSyncSection(
    anchorMs: Long,
    selectedAddonSubtitle: Subtitle?,
    cues: List<SubtitleSyncCue>,
    capturedVideoMs: Long?,
    statusMessage: String?,
    errorMessage: String?,
    isLoadingCues: Boolean,
    onCaptureNow: () -> Unit,
    onCueSelected: (SubtitleSyncCue) -> Unit,
    onDismiss: () -> Unit,
    captureFocusRequester: FocusRequester
) {
    val hasCapturedMoment = capturedVideoMs != null
    val nearestCueIndex = remember(cues, anchorMs) {
        cues.indices.minByOrNull { index ->
            kotlin.math.abs(cues[index].startTimeMs - anchorMs)
        } ?: 0
    }
    val cueListState = rememberLazyListState()
    val nearestCueFocusRequester = remember(capturedVideoMs, nearestCueIndex, cues.size) { FocusRequester() }

    LaunchedEffect(hasCapturedMoment, nearestCueIndex, cues.size) {
        if (hasCapturedMoment && cues.isNotEmpty()) {
            cueListState.scrollToItem(nearestCueIndex.coerceIn(0, cues.lastIndex))
            delay(40)
            try {
                nearestCueFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus target may not be attached yet.
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Press Sync Now when you hear the line, then pick the matching subtitle.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Around ${formatAutoSyncTimestamp(anchorMs)} (+/-3m00s)",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f)
            )
            Card(
                onClick = onDismiss,
                colors = CardDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    focusedContainerColor = Color.White.copy(alpha = 0.18f)
                ),
                shape = CardDefaults.shape(RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        if (selectedAddonSubtitle == null) {
            Text(
                text = "Select an addon subtitle track first (Subtitles > Addons).",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB37A)
            )
            return
        }

        Text(
            text = "Source: ${Subtitle.languageCodeToName(selectedAddonSubtitle.lang)} (${selectedAddonSubtitle.addonName})",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f)
        )

        Card(
            onClick = onCaptureNow,
            modifier = Modifier.focusRequester(captureFocusRequester),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.Secondary.copy(alpha = 0.22f),
                focusedContainerColor = NuvioColors.Secondary.copy(alpha = 0.35f)
            ),
            shape = CardDefaults.shape(RoundedCornerShape(12.dp))
        ) {
            Text(
                text = "Sync Now",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        if (capturedVideoMs != null) {
            Text(
                text = "Captured: ${formatAutoSyncTimestamp(capturedVideoMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f)
            )
        }

        if (!statusMessage.isNullOrBlank()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8CE4A1)
            )
        }

        if (!hasCapturedMoment) {
            Text(
                text = "Subtitle lines stay hidden until you press Sync Now.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f)
            )
            return
        }

        if (isLoadingCues) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = "Loading subtitle lines...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
            }
            return
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFB37A)
            )
            return
        }

        if (cues.isEmpty()) {
            Text(
                text = "No lines around this position. Capture now or move playback and reload.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f)
            )
            return
        }

        LazyColumn(
            modifier = Modifier.height(260.dp),
            state = cueListState,
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = cues,
                key = { _, cue -> "${cue.startTimeMs}:${cue.text.hashCode()}" }
            ) { index, cue ->
                AutoSyncCueRow(
                    cue = cue,
                    focusRequester = if (index == nearestCueIndex) nearestCueFocusRequester else null,
                    onClick = { onCueSelected(cue) }
                )
            }
        }
    }
}

@Composable
private fun AutoSyncCueRow(
    cue: SubtitleSyncCue,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isFocused) {
                Color.White.copy(alpha = 0.14f)
            } else {
                Color.White.copy(alpha = 0.06f)
            },
            focusedContainerColor = Color.White.copy(alpha = 0.14f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = formatAutoSyncTimestamp(cue.startTimeMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.width(58.dp)
            )
            Text(
                text = cue.text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun selectAutoSyncVisibleCues(
    cues: List<SubtitleSyncCue>,
    anchorTimeMs: Long,
    marginMs: Long = 180_000L,
    maxVisible: Int = 70
): List<SubtitleSyncCue> {
    if (cues.isEmpty()) return emptyList()
    val sorted = cues.sortedBy { it.startTimeMs }
    val lower = (anchorTimeMs - marginMs).coerceAtLeast(0L)
    val upper = anchorTimeMs + marginMs
    val inWindow = sorted.filter { cue -> cue.startTimeMs in lower..upper }
    if (inWindow.isNotEmpty()) {
        if (inWindow.size <= maxVisible) return inWindow
        val centerIndex = inWindow.indices.minByOrNull { index ->
            kotlin.math.abs(inWindow[index].startTimeMs - anchorTimeMs)
        } ?: 0
        return takeCentered(inWindow, centerIndex, maxVisible)
    }

    val nearestIndex = sorted.indices.minByOrNull { index ->
        kotlin.math.abs(sorted[index].startTimeMs - anchorTimeMs)
    } ?: 0
    return takeCentered(sorted, nearestIndex, maxVisible)
}

private fun takeCentered(
    items: List<SubtitleSyncCue>,
    centerIndex: Int,
    maxVisible: Int
): List<SubtitleSyncCue> {
    if (items.size <= maxVisible) return items
    val half = maxVisible / 2
    var start = (centerIndex - half).coerceAtLeast(0)
    var end = (start + maxVisible).coerceAtMost(items.size)
    if (end - start < maxVisible) {
        start = (end - maxVisible).coerceAtLeast(0)
    }
    return items.subList(start, end)
}
