package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.dp

// Animation state is read during drawing, not composition or measurement.
internal fun Modifier.playerProgressFill(
    progress: () -> Float,
    bufferedProgress: () -> Float,
    bufferedColor: Color,
    playedBrush: Brush
): Modifier = drawBehind {
    val radius = CornerRadius(3.dp.toPx())
    val buffered = bufferedProgress().coerceIn(0f, 1f)
    if (buffered > 0f) {
        drawRoundRect(
            color = bufferedColor.copy(alpha = 0.35f),
            size = Size(size.width * buffered, size.height),
            cornerRadius = radius
        )
    }
    val played = progress().coerceIn(0f, 1f)
    if (played > 0f) {
        // Match the former child Box: its gradient spans the played width.
        inset(left = 0f, top = 0f, right = size.width * (1f - played), bottom = 0f) {
            drawRoundRect(brush = playedBrush, cornerRadius = radius)
        }
    }
}
