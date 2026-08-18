package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Long enough that moving the D-pad across a list does not start a scroll on every card the focus passes through, and long enough to read the visible lines first.
const val DefaultFocusScrollStartDelayMillis = 3000L

private const val EndHoldMillis = 5000L

// Vertical reveal is paced far below the 45.dp/s used for single-line marquee, because each new line has to be read rather than skimmed.
private val ScrollVelocity = 9.dp

// The return trip has nothing to read, so it matches the single-line marquee speed instead of the reading pace.
private val ReturnVelocity = 45.dp

// Anyone still on the card after three passes is not reading it, so the motion stops rather than looping under them indefinitely.
private const val MaxScrollCycles = 3

// Used when a caller passes a style with no explicit line height, which would otherwise collapse the clipping box to zero.
private const val FallbackLineHeightRatio = 1.35f

// A distance that rounds to zero still needs a nonzero duration, because tween treats 0 as an instant jump rather than a scroll.
internal fun focusScrollDurationMillis(overflowPx: Int, pxPerSecond: Float): Int {
    if (overflowPx <= 0 || pxPerSecond <= 0f) return 0
    return ((overflowPx / pxPerSecond) * 1000f).roundToInt().coerceAtLeast(1)
}

internal fun focusScrollOverflowPx(fullHeightPx: Int, collapsedHeightPx: Int): Int =
    (fullHeightPx - collapsedHeightPx).coerceAtLeast(0)

// Multi-line text clipped to maxLines that reveals the rest while focused, scrolling down, holding at the end, then returning to the top for a few passes before settling.
// The box keeps a fixed height whether or not it is scrolling, so a fixed-height card can use this without the surrounding list reflowing as focus moves.
@Composable
fun FocusScrollingText(
    text: String,
    focused: Boolean,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    startDelayMillis: Long = DefaultFocusScrollStartDelayMillis,
) {
    val density = LocalDensity.current
    val collapsedHeight = remember(style, maxLines, density) {
        val lineHeight = if (style.lineHeight.isSpecified) {
            style.lineHeight
        } else {
            style.fontSize * FallbackLineHeightRatio
        }
        with(density) { lineHeight.toDp() * maxLines }
    }
    val collapsedHeightPx = with(density) { collapsedHeight.roundToPx() }

    var fullHeightPx by remember(text) { mutableIntStateOf(0) }
    val offsetPx = remember(text) { Animatable(0f) }
    val overflowPx = focusScrollOverflowPx(fullHeightPx, collapsedHeightPx)

    // Read through a holder rather than capturing the value, because the effect is keyed on focus alone and the text can still be settling when it starts.
    val currentOverflowPx by rememberUpdatedState(overflowPx)


    // Keyed on focus only, so the wait always starts when the card is focused instead of restarting every time layout revises the overflow.
    LaunchedEffect(focused) {
        offsetPx.snapTo(0f)
        if (!focused) return@LaunchedEffect

        val revealPxPerSecond = with(density) { ScrollVelocity.toPx() }
        val returnPxPerSecond = with(density) { ReturnVelocity.toPx() }
        // Each pass ends back at the top, so finishing the last one leaves the description resting at the start.
        repeat(MaxScrollCycles) {
            delay(startDelayMillis)
            val distancePx = currentOverflowPx
            if (distancePx <= 0) return@LaunchedEffect
            offsetPx.animateTo(
                targetValue = distancePx.toFloat(),
                animationSpec = tween(
                    durationMillis = focusScrollDurationMillis(distancePx, revealPxPerSecond),
                    easing = LinearEasing
                )
            )
            delay(EndHoldMillis)
            offsetPx.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = focusScrollDurationMillis(distancePx, returnPxPerSecond),
                    easing = LinearEasing
                )
            )
        }
    }

    Box(
        modifier = modifier
            .height(collapsedHeight)
            .clipToBounds()
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            // Unbounded so the layout reports the height of the whole description rather than the height of the clipping box.
            modifier = Modifier
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = -offsetPx.value },
            onTextLayout = { layout -> fullHeightPx = layout.size.height }
        )
    }
}
