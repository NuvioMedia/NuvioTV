package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.player.formatTime
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val PreviewWidth = 320.dp
private val PreviewShape = RoundedCornerShape(10.dp)
private const val DefaultAspectRatio = 16f / 9f
private const val SeekPreviewLingerMs = 3_000L

@Composable
internal fun rememberSeekPreviewPosition(scrubPositionMs: Long?): Long? {
    var lastPositionMs by remember { mutableStateOf<Long?>(null) }
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(scrubPositionMs) {
        if (scrubPositionMs != null) {
            lastPositionMs = scrubPositionMs
            lingering = true
        } else if (lingering) {
            delay(SeekPreviewLingerMs)
            lingering = false
        }
    }
    return scrubPositionMs ?: lastPositionMs.takeIf { lingering }
}

@Composable
internal fun SeekPreviewOverlay(
    controller: SeekPreviewController?,
    active: Boolean,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
) {
    if (controller == null || durationMs <= 0L) return
    val visible = active && !controller.isUnavailable

    LaunchedEffect(controller, visible, positionMs, durationMs) {
        if (visible) controller.request(positionMs, durationMs)
    }
    LaunchedEffect(controller, active) {
        if (!active) controller.endScrub()
    }
    LaunchedEffect(controller) {
        controller.warmUp(positionMs, durationMs)
    }

    Layout(
        modifier = modifier,
        content = {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(120)) + scaleIn(
                    initialScale = 0.9f,
                    transformOrigin = TransformOrigin(0.5f, 1f),
                    animationSpec = tween(120),
                ),
                exit = fadeOut(tween(160)) + scaleOut(
                    targetScale = 0.9f,
                    transformOrigin = TransformOrigin(0.5f, 1f),
                    animationSpec = tween(160),
                ),
            ) {
                SeekPreviewBubble(controller = controller, positionMs = positionMs)
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(Constraints())
        val width = constraints.maxWidth
        layout(width, constraints.maxHeight) {
            if (placeable == null) return@layout
            val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val x = (fraction * width - placeable.width / 2f).roundToInt()
                .coerceIn(0, (width - placeable.width).coerceAtLeast(0))
            placeable.place(x, -placeable.height - gap.roundToPx())
        }
    }
}

@Composable
private fun SeekPreviewBubble(controller: SeekPreviewController, positionMs: Long) {
    val frame = controller.frame
    val aspectRatio = frame?.bitmap
        ?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width.toFloat() / it.height.toFloat() }
        ?.coerceIn(1f, 2.4f)
        ?: DefaultAspectRatio

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = PreviewWidth, height = PreviewWidth / aspectRatio)
                .clip(PreviewShape)
                .background(Color.Black)
                .border(1.5.dp, Color.White.copy(alpha = 0.9f), PreviewShape),
            contentAlignment = Alignment.Center,
        ) {
            if (frame != null) {
                Image(
                    bitmap = frame.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.8f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = formatTime(positionMs),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
