package com.nuvio.tv.ui.screens.player.audiosync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Small live readout of what audio subtitle sync is doing: how far ahead it has listened, what it
 * has heard, and why it is not synced yet. Hidden a few seconds after a confirmed sync.
 */
@Composable
internal fun SubtitleSyncStatusOverlay(modifier: Modifier = Modifier) {
    val diagnostics by SubtitleSyncStatus.diagnostics.collectAsStateWithLifecycle()
    val current = diagnostics
    var hideAfterSync by remember { mutableStateOf(false) }
    val synced = current?.phase == SubtitleSyncDiagnostics.Phase.Synced
    LaunchedEffect(synced, current?.mapping) {
        hideAfterSync = false
        if (synced) {
            delay(if (current?.notice != null) 12_000 else 8_000)
            hideAfterSync = true
        }
    }
    AnimatedVisibility(
        visible = current != null && !(synced && hideAfterSync),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val state = current ?: return@AnimatedVisibility
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val (headline, color) = when (state.phase) {
                SubtitleSyncDiagnostics.Phase.Synced ->
                    "Subtitles synced ${formatOffset(state.offsetMs)} · ${state.method ?: ""}" to Color(0xFF7CE38B)
                SubtitleSyncDiagnostics.Phase.Estimated ->
                    "Syncing ${formatOffset(state.offsetMs)} (early estimate)…" to Color(0xFFFFD166)
                SubtitleSyncDiagnostics.Phase.Listening -> "Syncing subtitles to audio…" to Color.White
                SubtitleSyncDiagnostics.Phase.Unavailable -> "Subtitle sync unavailable" to Color(0xFFFF8A80)
            }
            Text(headline, color = color, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            state.notice?.let { Line(it, Color(0xFF7CE38B)) }
            state.rate?.let { Line("Frame rate: $it", Color(0xFFFFD166)) }
            val listening = if (state.liveOnly) {
                "Listening live (no look-ahead for this audio)"
            } else {
                "Listened ${state.lookAheadSec}s ahead"
            }
            Line("$listening · ${state.wordsHeard} words recognised")
            if (state.sampling.isNotEmpty()) Line("Across the film: ${state.sampling}")
            if (state.recognizer.isNotEmpty()) Line("Speech recognition: ${state.recognizer}")
            if (state.reference.isNotEmpty()) Line("English reference: ${state.reference}")
            if (state.alternatives.isNotEmpty()) Line("Other subtitles: ${state.alternatives}")
            state.problem?.let { Line(it, Color(0xFFFFB4A9)) }
        }
    }
}

/**
 * The status panel in the player's top-left corner, inside the TV's overscan-safe area. Shown only
 * when "Show AudioSync statistics" is on; otherwise nothing is composed or collected.
 */
@Composable
internal fun BoxScope.SubtitleSyncStatusPanel(safePadding: Dp) {
    val showStatistics by AudioSyncSettings.showStatistics.collectAsStateWithLifecycle()
    if (!showStatistics) return
    SubtitleSyncStatusOverlay(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = safePadding, top = safePadding)
            .zIndex(2.74f),
    )
}

@Composable
private fun Line(text: String, color: Color = Color.White.copy(alpha = 0.85f)) {
    Text(text, color = color, fontSize = 15.sp, lineHeight = 20.sp)
}

private fun formatOffset(offsetMs: Long?): String {
    val value = offsetMs ?: return ""
    val sign = if (value < 0) "-" else "+"
    val tenths = (abs(value) + 50) / 100
    return "($sign${tenths / 10}.${tenths % 10}s)"
}
