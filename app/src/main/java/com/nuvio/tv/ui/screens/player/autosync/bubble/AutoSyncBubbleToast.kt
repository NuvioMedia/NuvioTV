package com.nuvio.tv.ui.screens.player.autosync.bubble

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

// About 60% of the first TV bubble: small enough not to cover the film, still readable from the couch.
private val GlassBody = Color(0xFF232327)
private val BubbleCorner = 22.dp
private val OrbSize = 29.dp
private val BubblePadding = 7.dp
private val LabelGap = 10.dp
/** Past these the words wrap onto more lines rather than being cut; the bubble grows to fit. */
private val LabelMaxWidth = 300.dp
private val CardMaxWidth = 340.dp
/** Above the player's title, seek bar and buttons while they show. */
private val LiftWithControls = 200.dp
/**
 * With the controls hidden, clear of the subtitles: the player pads them 6% of the height from the
 * bottom, plus about two caption lines (media3's default text is ~5.3% of the height). ~108 dp at 540.
 */
private const val LIFT_ABOVE_SUBTITLES_FRACTION = 0.20f
/** While only the droplet shows, the spinner ticks at this slow cadence instead of every frame. */
private const val IDLE_TICK_MS = 250L
/** The words' fold-away spring has settled by then; until it has, the bubble animates every frame. */
private const val FOLD_SETTLE_MS = 800L

/** How long the working bubble keeps its words before settling to just the droplet. */
private const val WORKING_LABEL_MS = 7_000L
/** A run that never reports back fades away after this. */
private const val WORKING_TIMEOUT_MS = 240_000L
private const val SUCCESS_HOLD_MS = 1_800L
/** A little longer than the phone: the explanation is read from across the room. */
private const val FAILURE_HOLD_MS = 8_000L

/** The theme colours the bubble tints itself with once a run ends. */
private data class BubbleColors(val success: Color, val failure: Color)

/**
 * AutoSync's frosted bubble, at the bottom centre of the player. Composes nothing, and runs no
 * animation, unless the setting is on and AutoSync has something to say. It is never focusable
 * and takes no input, so the remote always stays with the player; the failure card closes itself.
 *
 * Built for weak TV boxes: no copy of the video, no blur, no per-frame paths. The glass is a
 * static frosted fill drawn once; everything that moves every frame (drift, breathing droplet,
 * spinner) is a graphics-layer property, so frames neither recompose nor re-record drawing.
 */
@Composable
internal fun BoxScope.AutoSyncBubbleToastHost(controlsVisible: Boolean) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        AutoSyncBubbleToasts.ensureLoaded(context)
        AutoSyncBubbleToasts.attachHost()
        onDispose { AutoSyncBubbleToasts.detachHost() }
    }
    val enabled by AutoSyncBubbleToasts.enabled.collectAsState()
    val message by AutoSyncBubbleToasts.current.collectAsState()
    val current = message
    if (!enabled || current == null) return
    val safeBottom = NuvioTheme.spacing.screen.overscanVertical
    val aboveSubtitles = (LocalConfiguration.current.screenHeightDp * LIFT_ABOVE_SUBTITLES_FRACTION).dp
    val lift = animateDpAsState(
        targetValue = if (controlsVisible) LiftWithControls else maxOf(safeBottom, aboveSubtitles),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "autoSyncBubbleLift",
    )
    val colors = BubbleColors(success = NuvioTheme.colors.Success, failure = NuvioTheme.colors.Error)
    // A full-width holder keeps the bubble's size changes (the words folding in and out) inside it,
    // so they never re-lay out the player screen around it on every animation frame.
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .zIndex(2.74f)
            .offset { IntOffset(0, -lift.value.roundToPx()) },
    ) {
        key(current.session) {
            AutoSyncBubble(message = current, colors = colors, modifier = Modifier)
        }
    }
}

@Composable
private fun AutoSyncBubble(message: AutoSyncBubbleMessage, colors: BubbleColors, modifier: Modifier) {
    val kind = message.kind
    val kindState = rememberUpdatedState(kind)
    val colorsState = rememberUpdatedState(colors)
    val scope = rememberCoroutineScope()

    val appear = remember { Animatable(0f) }
    val settle = remember { Animatable(0f) } // 0 = working (breathing, spinning), 1 = still
    val mark = remember { Animatable(0f) } // the check or the X draws itself
    val shake = remember { Animatable(0f) } // one small wobble on failure
    val melt = remember { Animatable(0f) } // success: the bubble draws itself in and fades
    val leave = remember { Animatable(0f) } // failure or timeout: sinks away
    val clock = remember { mutableFloatStateOf(0f) }
    var labelVisible by remember { mutableStateOf(true) }
    var labelFoldedAtMs by remember { mutableLongStateOf(0L) }
    var cardOpen by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val tinted = animateFloatAsState(
        targetValue = if (kind == AutoSyncBubbleKind.Working) 0f else 1f,
        animationSpec = tween(480),
        label = "tinted",
    )

    fun dismiss() {
        if (leaving) return
        leaving = true
        AutoSyncBubbleToasts.leaving(message.session)
        scope.launch {
            cardOpen = false
            labelFoldedAtMs = SystemClock.uptimeMillis()
            labelVisible = false
            delay(240)
            leave.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
            AutoSyncBubbleToasts.finished(message.id)
        }
    }

    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 260f))
    }
    // The working clock: only ticks while the bubble works, then stops for good. It is read only
    // inside graphicsLayer blocks, so a tick moves layers without recomposing or redrawing. Once
    // the words have folded away (a run can work for minutes), it ticks a few times a second
    // instead of every frame: the spinner steps round like a clock, and the box renders no
    // UI frames in between.
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (kindState.value == AutoSyncBubbleKind.Working || settle.value < 1f) {
            // Every frame until the words have finished folding away, so the fold stays smooth.
            val idle = !labelVisible && kindState.value == AutoSyncBubbleKind.Working &&
                SystemClock.uptimeMillis() - labelFoldedAtMs >= FOLD_SETTLE_MS
            if (idle) delay(IDLE_TICK_MS)
            withFrameNanos { clock.floatValue = (it - start) / 1_000_000_000f * 0.6f }
        }
    }
    LaunchedEffect(message.id) {
        if (kind != AutoSyncBubbleKind.Working) return@LaunchedEffect
        labelVisible = true
        delay(WORKING_LABEL_MS)
        labelFoldedAtMs = SystemClock.uptimeMillis()
        labelVisible = false
        delay(WORKING_TIMEOUT_MS - WORKING_LABEL_MS)
        dismiss()
    }
    LaunchedEffect(kind) {
        when (kind) {
            AutoSyncBubbleKind.Working -> Unit
            AutoSyncBubbleKind.Success -> {
                labelVisible = true
                settle.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 200f))
                mark.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                delay(SUCCESS_HOLD_MS)
                labelVisible = false
                delay(380)
                leaving = true
                melt.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                AutoSyncBubbleToasts.finished(message.id)
            }
            AutoSyncBubbleKind.Failure -> {
                labelVisible = true
                settle.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 200f))
                launch { shake.animateTo(1f, tween(480)) }
                mark.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                delay(160)
                cardOpen = true
                delay(FAILURE_HOLD_MS)
                dismiss()
            }
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            val a = appear.value
            val l = leave.value
            val m = melt.value
            val working = 1f - settle.value
            val t = clock.floatValue
            // Melting away: a touch wider and shorter as it draws in, like a drop settling.
            val squash = 0.04f * sin(m * PI.toFloat())
            val scale = (0.72f + 0.28f * a) * (1f - 0.25f * l) * (1f - 0.5f * m)
            scaleX = scale * (1f + squash)
            scaleY = scale * (1f - squash)
            alpha = a.coerceIn(0f, 1f) * (1f - l) * (1f - m)
            // While it works, the whole bubble drifts a little, as if floating.
            val wobble = sin(shake.value * PI.toFloat() * 3f) * (1f - shake.value) * 2.dp.toPx()
            translationX = sin(t * 1.1f) * 0.8.dp.toPx() * working + wobble
            translationY = (1f - a) * 18.dp.toPx() + (l + m * 0.5f) * 9.dp.toPx() +
                sin(t * 1.6f + 0.8f) * 0.6.dp.toPx() * working
            transformOrigin = TransformOrigin(0.5f, 0.6f)
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .frostedGlass(kind = kindState, colors = colorsState, tinted = tinted)
                .padding(BubblePadding),
        ) {
            Droplet(kind = kindState, colors = colorsState, clock = clock, settle = settle, tinted = tinted, mark = mark)
            AnimatedVisibility(
                visible = labelVisible,
                enter = fadeIn(tween(240, delayMillis = 80)) +
                    expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 360f), expandFrom = Alignment.Start),
                exit = fadeOut(tween(140)) +
                    shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 420f), shrinkTowards = Alignment.Start),
            ) {
                BubbleLabel(message = message, cardOpen = cardOpen)
            }
        }
    }
}

@Composable
private fun BubbleLabel(message: AutoSyncBubbleMessage, cardOpen: Boolean) {
    Row {
        Spacer(Modifier.width(LabelGap))
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .heightIn(min = OrbSize)
                .widthIn(max = if (cardOpen) CardMaxWidth else LabelMaxWidth)
                .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 300f))
                .padding(end = 7.dp),
        ) {
            Text(
                text = stringResource(R.string.autosync_bubble_label),
                color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.6f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
            )
            AnimatedContent(
                targetState = message.headline,
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInVertically(tween(280)) { it / 3 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(220)) { -it / 3 }) using
                        SizeTransform(clip = false)
                },
                label = "autoSyncBubbleHeadline",
            ) { headline ->
                Text(
                    text = headline,
                    color = NuvioTheme.colors.TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val detail = message.detail
            if (cardOpen && detail != null) {
                Text(
                    text = detail,
                    color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

private fun AutoSyncBubbleKind.color(colors: BubbleColors): Color = when (this) {
    AutoSyncBubbleKind.Working -> Color.White
    AutoSyncBubbleKind.Success -> colors.success
    AutoSyncBubbleKind.Failure -> colors.failure
}

/**
 * The droplet beside the words: a small glass lens. While working it breathes and a highlight
 * glides round its rim; the result fills it green or red and the check or X draws in.
 * Breathing and the glide are layer transforms (no redraw); only the tint fade and the mark
 * redraw, for the half second they animate.
 */
@Composable
private fun Droplet(
    kind: State<AutoSyncBubbleKind>,
    colors: State<BubbleColors>,
    clock: FloatState,
    settle: Animatable<Float, *>,
    tinted: State<Float>,
    mark: Animatable<Float, *>,
) {
    Box(
        Modifier
            .size(OrbSize)
            .graphicsLayer {
                val working = 1f - settle.value
                val breath = 1f + 0.035f * sin(clock.floatValue * 2.4f) * working
                scaleX = breath
                scaleY = breath
            }
            .droplet(kind = kind, colors = colors, tinted = tinted, mark = mark),
    ) {
        // The spinner: one arc drawn once, turned by its layer.
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    rotationZ = clock.floatValue * 170f
                    alpha = (1f - settle.value).coerceIn(0f, 1f)
                }
                .drawWithCache {
                    val radius = size.minDimension / 2f
                    val inner = radius * 0.62f
                    val topLeft = Offset(size.width / 2f - inner, size.height / 2f - inner)
                    val arcSize = Size(inner * 2f, inner * 2f)
                    val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    val color = Color.White.copy(alpha = 0.7f)
                    onDrawBehind {
                        drawArc(
                            color = color,
                            startAngle = 0f,
                            sweepAngle = 70f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = stroke,
                        )
                    }
                },
        )
    }
}

/**
 * The static frosted glass behind the bubble's content: a translucent dark fill, a frost gradient
 * brighter at the top, and a light rim. Brushes are built once per size and result; the tint fades
 * in through the draw's alpha, so the fade allocates nothing.
 */
private fun Modifier.frostedGlass(
    kind: State<AutoSyncBubbleKind>,
    colors: State<BubbleColors>,
    tinted: State<Float>,
): Modifier = drawWithCache {
    val tintColor = kind.value.color(colors.value)
    val corner = BubbleCorner.toPx().coerceAtMost(size.minDimension / 2f)
    val radius = CornerRadius(corner)
    val shadowStep = 1.1.dp.toPx()
    val shadow = Color.Black.copy(alpha = 0.06f)
    val body = GlassBody.copy(alpha = 0.62f)
    val tintBrush = Brush.horizontalGradient(
        0f to tintColor.copy(alpha = 0.22f),
        1f to Color.Transparent,
        endX = size.height * 3f,
    )
    val frost = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.20f),
        0.5f to Color.White.copy(alpha = 0.10f),
        1f to Color.White.copy(alpha = 0.13f),
    )
    // Rim: bright on the upper left, a softer second highlight on the lower right.
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.55f),
        0.3f to Color.White.copy(alpha = 0.14f),
        0.7f to Color.White.copy(alpha = 0.08f),
        1f to Color.White.copy(alpha = 0.30f),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val rimStroke = Stroke(width = 1.dp.toPx())
    val inset = 2.5.dp.toPx()
    val innerStroke = Stroke(width = 1.2.dp.toPx())
    val innerColor = Color.White.copy(alpha = 0.07f)
    val innerTopLeft = Offset(inset, inset)
    val innerSize = Size(size.width - inset * 2f, size.height - inset * 2f)
    val innerRadius = CornerRadius((corner - inset).coerceAtLeast(0f))

    onDrawBehind {
        for (step in 1..3) {
            translate(top = step * shadowStep) { drawRoundRect(shadow, cornerRadius = radius) }
        }
        drawRoundRect(body, cornerRadius = radius)
        val tintAmount = tinted.value
        if (tintAmount > 0f) drawRoundRect(tintBrush, cornerRadius = radius, alpha = tintAmount)
        drawRoundRect(frost, cornerRadius = radius)
        drawRoundRect(rim, cornerRadius = radius, style = rimStroke)
        if (innerSize.width > 0f && innerSize.height > 0f) {
            drawRoundRect(
                color = innerColor,
                topLeft = innerTopLeft,
                size = innerSize,
                cornerRadius = innerRadius,
                style = innerStroke,
            )
        }
    }
}

/** The droplet's lens, result fill, rim and mark: plain circles and lines, brushes cached. */
private fun Modifier.droplet(
    kind: State<AutoSyncBubbleKind>,
    colors: State<BubbleColors>,
    tinted: State<Float>,
    mark: Animatable<Float, *>,
): Modifier = drawWithCache {
    val failure = kind.value == AutoSyncBubbleKind.Failure
    val tintColor = kind.value.color(colors.value)
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension / 2f
    val lens = Brush.radialGradient(
        0f to Color.White.copy(alpha = 0.32f),
        1f to Color.White.copy(alpha = 0.12f),
        center = center + Offset(-radius * 0.35f, -radius * 0.45f),
        radius = radius * 1.6f,
    )
    val fill = Brush.radialGradient(
        0f to tintColor.copy(alpha = 0.95f),
        1f to tintColor.copy(alpha = 0.80f),
        center = center + Offset(-radius * 0.3f, -radius * 0.4f),
        radius = radius * 1.5f,
    )
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.8f),
        0.45f to Color.White.copy(alpha = 0.1f),
        1f to Color.White.copy(alpha = 0.45f),
        start = center - Offset(radius, radius),
        end = center + Offset(radius, radius),
    )
    val rimRadius = radius - 0.5.dp.toPx()
    val rimStroke = Stroke(width = 1.dp.toPx())
    val markStroke = 2.2.dp.toPx()

    onDrawBehind {
        drawCircle(lens, radius = radius, center = center)
        val tintAmount = tinted.value
        if (tintAmount > 0f) drawCircle(fill, radius = radius, center = center, alpha = tintAmount)
        drawCircle(rim, radius = rimRadius, center = center, style = rimStroke)

        val m = mark.value.coerceIn(0f, 1f)
        if (m > 0f) {
            if (failure) {
                val d = radius * 0.30f
                drawTrimmed(center.x - d, center.y - d, center.x + d, center.y + d, Float.NaN, Float.NaN, (m * 2f).coerceAtMost(1f), markStroke)
                if (m > 0.5f) {
                    drawTrimmed(center.x + d, center.y - d, center.x - d, center.y + d, Float.NaN, Float.NaN, (m - 0.5f) * 2f, markStroke)
                }
            } else {
                drawTrimmed(
                    center.x - radius * 0.38f, center.y + radius * 0.02f,
                    center.x - radius * 0.11f, center.y + radius * 0.29f,
                    center.x + radius * 0.40f, center.y - radius * 0.27f,
                    m,
                    markStroke,
                )
            }
        }
    }
}

/**
 * Draws the first [progress] of the line through (x0, y0), (x1, y1) and, unless NaN, (x2, y2), as
 * a pen would. Takes plain coordinates so drawing the mark allocates no lists per frame.
 */
private fun DrawScope.drawTrimmed(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    progress: Float,
    stroke: Float,
) {
    if (progress <= 0f) return
    val first = Offset(x1 - x0, y1 - y0).getDistance()
    val second = if (x2.isNaN()) 0f else Offset(x2 - x1, y2 - y1).getDistance()
    var remaining = (first + second) * progress
    if (first > 0f && remaining > 0f) {
        val f = (remaining / first).coerceAtMost(1f)
        drawLine(
            color = Color.White,
            start = Offset(x0, y0),
            end = Offset(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        remaining -= first
    }
    if (second > 0f && remaining > 0f) {
        val f = (remaining / second).coerceAtMost(1f)
        drawLine(
            color = Color.White,
            start = Offset(x1, y1),
            end = Offset(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
