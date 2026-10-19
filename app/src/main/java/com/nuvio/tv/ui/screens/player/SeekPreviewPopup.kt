package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import java.util.Locale

private val PopupWidth = 200.dp
private val PopupGap = 12.dp

/** Thumbnail above the seek bar while scrubbing; renders nothing unless the server supplied previews. */
@Composable
internal fun SeekPreviewPopup(
    controller: SeekPreviewController,
    previewPositionMs: Long?,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    val layout by controller.layout.collectAsState()
    val currentLayout = layout ?: return
    val position = previewPositionMs ?: return
    if (durationMs <= 0L) return

    var bitmap by remember(currentLayout.key) { mutableStateOf<ImageBitmap?>(null) }
    val thumbnailIndex = currentLayout.thumbnailIndexAt(position)
    // Keep showing the previous thumbnail until the next one is ready, to avoid flicker.
    LaunchedEffect(currentLayout.key, thumbnailIndex) {
        controller.thumbnail(position)?.let { bitmap = it }
    }
    val image = bitmap ?: return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val gapPx = PopupGap.roundToPx()
                // Zero-height slot: draws above the parent instead of pushing the controls around.
                layout(placeable.width, 0) { placeable.place(0, -placeable.height - gapPx) }
            }
    ) {
        val fraction = (position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val x = (maxWidth * fraction - PopupWidth / 2).coerceIn(0.dp, (maxWidth - PopupWidth).coerceAtLeast(0.dp))
        Column(
            modifier = Modifier.offset(x = x).width(PopupWidth),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(currentLayout.thumbWidth.toFloat() / currentLayout.thumbHeight.toFloat())
                    .clip(RoundedCornerShape(8.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            )
            Text(
                text = formatPreviewTime(position),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}

private fun formatPreviewTime(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
