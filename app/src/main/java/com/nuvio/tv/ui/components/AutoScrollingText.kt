package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val AUTO_SCROLL_START_DELAY_MS = 3_000L
private const val AUTO_SCROLL_END_PAUSE_MS = 3_000L
private const val AUTO_SCROLL_RETURN_DURATION_MS = 600
private const val AUTO_SCROLL_SPEED_DP_PER_SECOND = 12f
// Stop after a few loops so an idle Home screen doesn't animate forever.
private const val AUTO_SCROLL_MAX_CYCLES = 3
private const val AUTO_SCROLL_FADE_FRACTION = 0.18f

// Keeps the scroll at a constant speed regardless of the system animator duration scale.
private object FixedMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/**
 * Text clamped to [maxLines]. When [autoScrollEnabled] is true and the text overflows,
 * it scrolls vertically after a short delay (Kodi-style plot scrolling) instead of
 * being cut off with an ellipsis, then pauses and returns to the top.
 *
 * Scrolling stops while [paused] returns true (e.g. while the text is hidden) and falls
 * back to the ellipsis when system animations are turned off.
 *
 * [onScrollHoldChanged] is true while the first scroll pass is still running, so callers
 * can delay replacing the text (e.g. a carousel auto-advance) until it has been read.
 */
@Composable
fun AutoScrollingText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    autoScrollEnabled: Boolean,
    modifier: Modifier = Modifier,
    paused: () -> Boolean = { false },
    onScrollHoldChanged: (Boolean) -> Unit = {}
) {
    val currentOnScrollHoldChanged by rememberUpdatedState(onScrollHoldChanged)
    DisposableEffect(Unit) {
        onDispose { currentOnScrollHoldChanged(false) }
    }

    // Re-checked for each text, so turning animations back on takes effect on the next title.
    var animationsDisabled by remember(text) { mutableStateOf(false) }
    if (!autoScrollEnabled || animationsDisabled) {
        EllipsizedText(text, style, color, maxLines, modifier)
        LaunchedEffect(text) { currentOnScrollHoldChanged(false) }
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val mergedStyle = LocalTextStyle.current.merge(style)
        val textMeasurer = rememberTextMeasurer()
        val layout = remember(text, mergedStyle, constraints.maxWidth, textMeasurer) {
            if (constraints.hasBoundedWidth) {
                textMeasurer.measure(
                    text = text,
                    style = mergedStyle,
                    constraints = Constraints(maxWidth = constraints.maxWidth)
                )
            } else {
                null
            }
        }

        if (layout == null || layout.lineCount <= maxLines) {
            EllipsizedText(text, style, color, maxLines, Modifier)
            LaunchedEffect(text) { currentOnScrollHoldChanged(false) }
            return@BoxWithConstraints
        }

        val density = LocalDensity.current
        val viewportPx = layout.getLineBottom(maxLines - 1)
        val overflowPx = layout.size.height - viewportPx
        val viewportHeight = with(density) { viewportPx.toDp() }
        val speedPxPerSecond = with(density) { AUTO_SCROLL_SPEED_DP_PER_SECOND.dp.toPx() }
        val currentPaused by rememberUpdatedState(paused)
        // A fresh offset per text, so a new description never shows at the previous one's position.
        val offset = remember(text) { Animatable(0f) }

        LaunchedEffect(text, overflowPx) {
            if (coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
                animationsDisabled = true
                return@LaunchedEffect
            }
            val scrollDurationMs = (overflowPx / speedPxPerSecond * 1000f).toInt().coerceAtLeast(1)
            withContext(FixedMotionDurationScale) {
                snapshotFlow { currentPaused() }
                    .distinctUntilChanged()
                    .collectLatest { isPaused ->
                        offset.snapTo(0f)
                        currentOnScrollHoldChanged(!isPaused)
                        if (isPaused) return@collectLatest
                        repeat(AUTO_SCROLL_MAX_CYCLES) { cycle ->
                            delay(AUTO_SCROLL_START_DELAY_MS)
                            offset.animateTo(
                                targetValue = overflowPx,
                                animationSpec = tween(durationMillis = scrollDurationMs, easing = LinearEasing)
                            )
                            delay(AUTO_SCROLL_END_PAUSE_MS)
                            if (cycle == 0) currentOnScrollHoldChanged(false)
                            offset.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = AUTO_SCROLL_RETURN_DURATION_MS)
                            )
                        }
                    }
            }
        }

        Box(
            modifier = Modifier
                .height(viewportHeight)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    clip = true
                }
                .drawWithCache {
                    val fadeHeight = size.height * AUTO_SCROLL_FADE_FRACTION
                    val topFade = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = 0f,
                        endY = fadeHeight
                    )
                    val bottomFade = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = size.height - fadeHeight,
                        endY = size.height
                    )
                    val fadeSize = Size(size.width, fadeHeight)
                    onDrawWithContent {
                        drawContent()
                        val value = offset.value
                        // Scale each fade with the distance to its edge so it grows and shrinks smoothly.
                        val topAlpha = min(value, fadeHeight) / fadeHeight
                        val bottomAlpha = min(overflowPx - value, fadeHeight) / fadeHeight
                        if (topAlpha > 0f) {
                            drawRect(
                                brush = topFade,
                                size = fadeSize,
                                alpha = topAlpha,
                                blendMode = BlendMode.DstOut
                            )
                        }
                        if (bottomAlpha > 0f) {
                            drawRect(
                                brush = bottomFade,
                                topLeft = Offset(0f, size.height - fadeHeight),
                                size = fadeSize,
                                alpha = bottomAlpha,
                                blendMode = BlendMode.DstOut
                            )
                        }
                    }
                }
        ) {
            Text(
                text = text,
                style = style,
                color = color,
                modifier = Modifier
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    // Float translation gives sub-pixel motion instead of whole-pixel steps.
                    .graphicsLayer { translationY = -offset.value }
            )
        }
    }
}

@Composable
private fun EllipsizedText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
