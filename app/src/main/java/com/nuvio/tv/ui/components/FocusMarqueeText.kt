package com.nuvio.tv.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlin.math.min
import kotlinx.coroutines.delay

// Compose's default marquee velocity (MarqueeDefaults.Velocity) is 30.dp/s, which at our title font
// is only ~3.5 characters/second. Screen-reading research on horizontally scrolling text shows
// comprehension stays high (~95%) up to ~8.5 chars/second (~85 wpm), so 45.dp/s (~5.3 cps) reads
// noticeably faster while keeping a comfortable margin below that rate.
private val MarqueeVelocity = 45.dp

/**
 * Single-line text that scrolls (marquees) horizontally while [focused] if the content overflows,
 * and otherwise ellipsizes. Lets long titles/labels become fully readable when their card or row is
 * focused, while staying visually identical to a normal ellipsized [Text] when unfocused.
 *
 * Scrolling only happens while [focused] and when the text actually overflows (Compose's
 * [basicMarquee] is a no-op when it already fits).
 */
/**
 * Multi-line variant: normally wraps to [maxLines] and ellipsizes, exactly as before. Only when
 * [focused] AND the text still doesn't fit in [maxLines] does it switch to a single scrolling line.
 *
 * The wrapped text stays in the layout (drawn transparent) while scrolling, so it keeps reserving
 * its full height and nothing below it shifts when the marquee kicks in.
 */
@Composable
fun FocusMarqueeMultilineText(
    text: String,
    focused: Boolean,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    var overflowed by remember(text, maxLines) { mutableStateOf(false) }
    val scrolling = focused && overflowed

    Box(modifier) {
        // No minLines: a short name must still take only the lines it needs, otherwise everything
        // below it (the character label) gets pushed down. The wrapped text stays in the layout even
        // while scrolling, so it keeps reserving exactly its own height and nothing shifts.
        Text(
            text = text,
            style = style,
            color = if (scrolling) Color.Transparent else color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            onTextLayout = { overflowed = it.hasVisualOverflow },
        )
        if (scrolling) {
            Text(
                text = text,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = MarqueeVelocity),
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = textAlign,
            )
        }
    }
}

private const val SyncedMarqueeStartDelayMs = 900L
private const val SyncedMarqueeEndPauseMs = 900L

/**
 * Shared clock for a group of [SyncedMarqueeText]s (a card's title and episode title).
 *
 * Independent marquees drift apart when the two lines differ in length. Here every line moves at the
 * same speed off one clock; a line that reaches the end of its own text holds there until the
 * longest line finishes, then the whole group snaps back to the start and begins again together.
 */
@Stable
class SyncedMarqueeState internal constructor() {
    /** Full rotation distance per line: its text width plus the trailing gap. */
    internal val distances = mutableStateMapOf<Any, Float>()
    internal var travelledPx by mutableFloatStateOf(0f)
    internal var focused by mutableStateOf(false)
    internal var running by mutableStateOf(false)
    internal val maxDistancePx: Float
        get() = distances.values.maxOrNull() ?: 0f
}

@Composable
fun rememberSyncedMarqueeState(focused: Boolean, velocity: Dp = MarqueeVelocity): SyncedMarqueeState {
    val state = remember { SyncedMarqueeState() }
    val velocityPx = with(LocalDensity.current) { velocity.toPx() }
    SideEffect { state.focused = focused }

    val maxDistance = state.maxDistancePx
    LaunchedEffect(focused, maxDistance, velocityPx) {
        state.travelledPx = 0f
        state.running = false
        if (!focused || maxDistance <= 0f || velocityPx <= 0f) return@LaunchedEffect
        state.running = true
        // Driven from real frame timestamps rather than a tween: tween durations are multiplied by
        // the system "Animator duration scale" (0.5x on this device made it run at double speed),
        // whereas frame-time integration always moves at the true velocity, like basicMarquee does.
        while (true) {
            delay(SyncedMarqueeStartDelayMs)
            var travelled = 0f
            var lastFrameNanos = 0L
            while (travelled < maxDistance) {
                withFrameNanos { now ->
                    if (lastFrameNanos != 0L) {
                        val deltaSeconds = (now - lastFrameNanos) / 1_000_000_000f
                        travelled = (travelled + velocityPx * deltaSeconds).coerceAtMost(maxDistance)
                        state.travelledPx = travelled
                    }
                    lastFrameNanos = now
                }
            }
            delay(SyncedMarqueeEndPauseMs)
            state.travelledPx = 0f
        }
    }
    return state
}

/**
 * Single line that scrolls in lockstep with the rest of its [state] group. At rest it is an ordinary
 * ellipsized line; only while the group is focused does it lay out at full width and scroll.
 */
@Composable
fun SyncedMarqueeText(
    text: String,
    state: SyncedMarqueeState,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val slot = remember { Any() }
    val density = LocalDensity.current
    var containerWidth by remember { mutableIntStateOf(0) }
    var textWidth by remember(text) { mutableIntStateOf(0) }

    val expanded = state.focused
    val overflows = textWidth > containerWidth && containerWidth > 0
    // Gap between the text and its trailing copy, matching basicMarquee's default feel.
    val gapPx = containerWidth / 3f
    // A full rotation carries the whole text plus the gap past the window, so the trailing copy
    // lands exactly where the original started.
    val distance = if (overflows) textWidth + gapPx else 0f

    LaunchedEffect(slot, distance, expanded) {
        state.distances[slot] = if (expanded) distance else 0f
    }
    DisposableEffect(slot) { onDispose { state.distances.remove(slot) } }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerWidth = it.width }
    ) {
        if (expanded && overflows) {
            // Two copies separated by the gap, translated as one. Once travelled reaches this
            // line's distance the copy sits exactly where the original began, so a line that
            // finishes early simply rests at the start position until the longest line catches up.
            Row(
                modifier = Modifier
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = -min(state.travelledPx, distance) },
                horizontalArrangement = Arrangement.spacedBy(with(density) { gapPx.toDp() })
            ) {
                MarqueeLine(text, style, color) { textWidth = it }
                MarqueeLine(text, style, color)
            }
        } else if (expanded) {
            // Focused but not yet known to overflow: lay out unbounded purely to learn the true
            // text width. If it turns out to fit, this looks identical to the resting line.
            Text(
                text = text,
                modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true),
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = { textWidth = it.size.width },
            )
        } else {
            // At rest: an ordinary ellipsized line, unchanged from before.
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MarqueeLine(
    text: String,
    style: TextStyle,
    color: Color,
    onWidth: ((Int) -> Unit)? = null,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { onWidth?.invoke(it.size.width) },
    )
}

@Composable
fun FocusMarqueeText(
    text: String,
    focused: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = if (focused) {
            modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = MarqueeVelocity)
        } else {
            modifier
        },
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}
