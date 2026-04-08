@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.LoadingIndicator
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class SyncStage {
    WAIT_FOR_SYNC,
    PICK_LINE
}

private const val VISIBLE_CUE_ROWS = 6
private val CUE_ROW_HEIGHT = 56.dp
private val CUE_ROW_SPACING = 8.dp
private val ASS_OVERRIDE_TAG_REGEX = Regex("""\{\\[^{}]*\}""")

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
    onCueSelected: (SubtitleSyncCue) -> Unit
) {
    val syncButtonFocusRequester = remember { FocusRequester() }
    val anchorMs = capturedVideoMs ?: currentPositionMs
    val visibleCues = remember(cues, anchorMs) {
        selectAutoSyncVisibleCues(
            cues = cues,
            anchorTimeMs = anchorMs
        )
    }
    var stage by remember(capturedVideoMs) {
        mutableStateOf(if (capturedVideoMs != null) SyncStage.PICK_LINE else SyncStage.WAIT_FOR_SYNC)
    }

    LaunchedEffect(capturedVideoMs) {
        stage = if (capturedVideoMs != null) SyncStage.PICK_LINE else SyncStage.WAIT_FOR_SYNC
    }

    LaunchedEffect(stage) {
        if (stage == SyncStage.WAIT_FOR_SYNC) {
            delay(120)
            try {
                syncButtonFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus target may not be attached yet.
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(if (stage == SyncStage.PICK_LINE) 0.94f else 0.6f)
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (stage == SyncStage.PICK_LINE) {
                    Color(0x2E090909)
                } else {
                    Color(0x47090909)
                }
            )
            .padding(
                horizontal = if (stage == SyncStage.PICK_LINE) 26.dp else 34.dp,
                vertical = if (stage == SyncStage.PICK_LINE) 20.dp else 30.dp
            )
    ) {
        when (stage) {
            SyncStage.WAIT_FOR_SYNC -> {
                SyncPromptPanel(
                    onCaptureNow = {
                        stage = SyncStage.PICK_LINE
                        onCaptureNow()
                    },
                    focusRequester = syncButtonFocusRequester
                )
            }

            SyncStage.PICK_LINE -> {
                CueSelectionPanel(
                    anchorMs = anchorMs,
                    selectedAddonSubtitle = selectedAddonSubtitle,
                    cues = visibleCues,
                    capturedVideoMs = capturedVideoMs,
                    statusMessage = statusMessage,
                    errorMessage = errorMessage,
                    isLoadingCues = isLoadingCues,
                    onCueSelected = onCueSelected
                )
            }
        }
    }
}

@Composable
private fun SyncPromptPanel(
    onCaptureNow: () -> Unit,
    focusRequester: FocusRequester
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Press Sync when you hear a dialog line.",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        Card(
            onClick = onCaptureNow,
            modifier = Modifier
                .focusRequester(focusRequester),
            colors = CardDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.14f),
                focusedContainerColor = Color.White.copy(alpha = 0.26f)
            ),
            shape = CardDefaults.shape(RoundedCornerShape(14.dp))
        ) {
            Text(
                text = "Sync",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun CueSelectionPanel(
    anchorMs: Long,
    selectedAddonSubtitle: Subtitle?,
    cues: List<SubtitleSyncCue>,
    capturedVideoMs: Long?,
    statusMessage: String?,
    errorMessage: String?,
    isLoadingCues: Boolean,
    onCueSelected: (SubtitleSyncCue) -> Unit
) {
    val nearestCueIndex = remember(cues, anchorMs) {
        cues.indices.minByOrNull { index -> abs(cues[index].startTimeMs - anchorMs) } ?: 0
    }
    val cueListState = rememberLazyListState()
    val nearestCueFocusRequester = remember(capturedVideoMs, nearestCueIndex, cues.size) { FocusRequester() }

    LaunchedEffect(capturedVideoMs, nearestCueIndex, cues.size) {
        if (capturedVideoMs != null && cues.isNotEmpty()) {
            cueListState.scrollToItem(nearestCueIndex.coerceIn(0, cues.lastIndex))
            delay(50)
            try {
                nearestCueFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus target may not be attached yet.
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (capturedVideoMs != null) {
                    "Captured at ${formatAutoSyncTimestamp(capturedVideoMs)}"
                } else {
                    "Capturing..."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )

            if (selectedAddonSubtitle != null) {
                Text(
                    text = Subtitle.languageCodeToName(selectedAddonSubtitle.lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.68f)
                )
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9BE2AF)
            )
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFB37A)
            )
            return
        }

        if (selectedAddonSubtitle == null) {
            Text(
                text = "Select an addon subtitle track first.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB37A)
            )
            return
        }

        if (isLoadingCues) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = "Loading subtitle lines...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            return
        }

        if (cues.isEmpty()) {
            Text(
                text = "No subtitle lines were found around this moment.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f)
            )
            return
        }

        LazyColumn(
            modifier = Modifier.height(
                (CUE_ROW_HEIGHT * VISIBLE_CUE_ROWS) +
                    (CUE_ROW_SPACING * (VISIBLE_CUE_ROWS - 1)) +
                    8.dp
            ),
            state = cueListState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(CUE_ROW_SPACING)
        ) {
            itemsIndexed(
                items = cues,
                key = { _, cue -> "${cue.startTimeMs}:${cue.text.hashCode()}" }
            ) { index, cue ->
                CueRow(
                    cue = cue,
                    rowHeight = CUE_ROW_HEIGHT,
                    focusRequester = if (index == nearestCueIndex) nearestCueFocusRequester else null,
                    onClick = { onCueSelected(cue) }
                )
            }
        }

        Text(
            text = "Press Back to cancel",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun CueRow(
    cue: SubtitleSyncCue,
    rowHeight: Dp,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusedContainer = MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f)
    val focusedTextColor = MaterialTheme.colorScheme.onSecondary

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isFocused) {
                focusedContainer
            } else {
                Color.White.copy(alpha = 0.07f)
            },
            focusedContainerColor = focusedContainer
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.Transparent),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(1.dp, Color.Transparent),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.015f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = formatAutoSyncTimestamp(cue.startTimeMs),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (isFocused) focusedTextColor.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.78f),
                modifier = Modifier.width(72.dp)
            )
            Text(
                text = sanitizeCuePreviewText(cue.text),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused) focusedTextColor else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun sanitizeCuePreviewText(text: String): String {
    val cleaned = text
        .replace(ASS_OVERRIDE_TAG_REGEX, "")
        .replace("\\N", " ")
        .replace("\\n", " ")
        .replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return if (cleaned.isNotBlank()) cleaned else text.trim()
}

private fun selectAutoSyncVisibleCues(
    cues: List<SubtitleSyncCue>,
    anchorTimeMs: Long,
    marginMs: Long = 180_000L,
    maxVisible: Int = 90
): List<SubtitleSyncCue> {
    if (cues.isEmpty()) return emptyList()
    val sorted = cues.sortedBy { it.startTimeMs }
    val lower = (anchorTimeMs - marginMs).coerceAtLeast(0L)
    val upper = anchorTimeMs + marginMs
    val inWindow = sorted.filter { cue -> cue.startTimeMs in lower..upper }
    if (inWindow.isNotEmpty()) {
        if (inWindow.size <= maxVisible) return inWindow
        val centerIndex = inWindow.indices.minByOrNull { index ->
            abs(inWindow[index].startTimeMs - anchorTimeMs)
        } ?: 0
        return takeCentered(inWindow, centerIndex, maxVisible)
    }

    val nearestIndex = sorted.indices.minByOrNull { index ->
        abs(sorted[index].startTimeMs - anchorTimeMs)
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
    val end = (start + maxVisible).coerceAtMost(items.size)
    if (end - start < maxVisible) {
        start = (end - maxVisible).coerceAtLeast(0)
    }
    return items.subList(start, end)
}
