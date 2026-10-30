package com.nuvio.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val AUTO_SCROLL_START_DELAY_MS = 3_000L
private const val AUTO_SCROLL_END_PAUSE_MS = 3_000L
private const val AUTO_SCROLL_RETURN_DURATION_MS = 600
private const val AUTO_SCROLL_SPEED_DP_PER_SECOND = 12f

/**
 * Text clamped to [maxLines]. When [autoScrollEnabled] is true and the text overflows,
 * it scrolls vertically after a short delay (Kodi-style plot scrolling) instead of
 * being cut off with an ellipsis, then pauses and returns to the top.
 */
@Composable
fun AutoScrollingText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    autoScrollEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (!autoScrollEnabled) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val viewportHeight = remember(textMeasurer, style, maxLines, density) {
        val sample = List(maxLines.coerceAtLeast(1)) { "A" }.joinToString("\n")
        with(density) { textMeasurer.measure(sample, style).size.height.toDp() }
    }
    val speedPxPerSecond = remember(density) {
        with(density) { AUTO_SCROLL_SPEED_DP_PER_SECOND.dp.toPx() }
    }
    val scrollState = rememberScrollState()

    LaunchedEffect(text, viewportHeight) {
        scrollState.scrollTo(0)
        // maxValue stays Int.MAX_VALUE until the first layout pass.
        val maxScroll = snapshotFlow { scrollState.maxValue }.first { it != Int.MAX_VALUE }
        if (maxScroll <= 0) return@LaunchedEffect
        val scrollDurationMs = (maxScroll / speedPxPerSecond * 1000f).toInt().coerceAtLeast(1)
        while (true) {
            delay(AUTO_SCROLL_START_DELAY_MS)
            scrollState.animateScrollTo(
                value = scrollState.maxValue,
                animationSpec = tween(durationMillis = scrollDurationMs, easing = LinearEasing)
            )
            delay(AUTO_SCROLL_END_PAUSE_MS)
            scrollState.animateScrollTo(
                value = 0,
                animationSpec = tween(durationMillis = AUTO_SCROLL_RETURN_DURATION_MS)
            )
        }
    }

    Box(
        modifier = modifier
            .heightIn(max = viewportHeight)
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fadeHeight = size.height * 0.18f
                if (scrollState.canScrollBackward) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startY = 0f,
                            endY = fadeHeight
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
                if (scrollState.canScrollForward) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startY = size.height - fadeHeight,
                            endY = size.height
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
            .verticalScroll(scrollState, enabled = false)
    ) {
        Text(
            text = text,
            style = style,
            color = color
        )
    }
}
