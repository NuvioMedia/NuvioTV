package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.ui.screens.player.PlayerViewModel

/*
 * Seek-preview integration points for the TV player.
 */

private val MinCueTickSpacing = 5.dp
private const val MaxCueTicks = 400L

/**
 * Seek-preview thumbnails above the controls' progress bar.
 */
@Composable
fun SeekPreviewAboveProgressBar(viewModel: PlayerViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) {
                    placeable.placeRelative(0, -placeable.height)
                }
            }
    ) {
        SeekPreviewThumbnailHost(viewModel = viewModel)
    }
}

/**
 * Cue ticks across a progress bar, at the positions grid-locked scrubbing can stop on.
 */
@Composable
fun SeekPreviewCueTicks(viewModel: PlayerViewModel, durationMs: Long, modifier: Modifier) {
    val cueIntervalMs by viewModel.seekPreview.cueIntervalMs.collectAsStateWithLifecycle()
    if (cueIntervalMs <= 0L || durationMs <= 0L) return
    val tickCount = durationMs / cueIntervalMs
    if (tickCount !in 2..MaxCueTicks) return
    Canvas(modifier = modifier) {
        val stepPx = size.width * (cueIntervalMs.toFloat() / durationMs.toFloat())
        if (stepPx < MinCueTickSpacing.toPx()) return@Canvas
        var x = stepPx
        while (x < size.width) {
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx()
            )
            x += stepPx
        }
    }
}
