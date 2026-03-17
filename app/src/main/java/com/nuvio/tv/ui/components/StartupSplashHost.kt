package com.nuvio.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import kotlinx.coroutines.delay

private const val STARTUP_SPLASH_HOLD_MS = 300L
private const val STARTUP_SPLASH_FADE_MS = 400
private const val STARTUP_RING_DURATION_MS = 1400
private const val STARTUP_RING_STAGGER_MS = 220

private val RingCyan = Color(0xFF32D8FF)
private val RingBlue = Color(0xFF4D73FF)
private val RingViolet = Color(0xFFC05BFF)
private val RingCenterGlow = Color(0x3325CFFF)

@Composable
fun StartupSplashHost(
    visible: Boolean,
    readyToDismiss: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        content()

        if (visible) {
            StartupSplashOverlay(
                readyToDismiss = readyToDismiss,
                onFinished = onFinished,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun StartupSplashOverlay(
    readyToDismiss: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var fadeOut by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (fadeOut) 0f else 1f,
        animationSpec = tween(
            durationMillis = STARTUP_SPLASH_FADE_MS,
            easing = LinearOutSlowInEasing
        ),
        label = "startupSplashAlpha"
    )

    LaunchedEffect(readyToDismiss) {
        if (!readyToDismiss) return@LaunchedEffect
        delay(STARTUP_SPLASH_HOLD_MS)
        fadeOut = true
        delay(STARTUP_SPLASH_FADE_MS.toLong())
        onFinished()
    }

    Box(
        modifier = modifier
            .background(colorResource(id = R.color.splash_background))
            .graphicsLayer(alpha = alpha),
        contentAlignment = Alignment.Center
    ) {
        StartupRippleRings(
            modifier = Modifier.size(220.dp)
        )
    }
}

@Composable
private fun StartupRippleRings(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "startupRippleTransition")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = STARTUP_RING_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "startupRipplePulse"
    )
    val rippleA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = STARTUP_RING_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(0)
        ),
        label = "startupRippleA"
    )
    val rippleB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = STARTUP_RING_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(STARTUP_RING_STAGGER_MS)
        ),
        label = "startupRippleB"
    )
    val rippleC by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = STARTUP_RING_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(STARTUP_RING_STAGGER_MS * 2)
        ),
        label = "startupRippleC"
    )

    Canvas(modifier = modifier) {
        val maxRadius = size.minDimension * 0.46f
        val minRadius = size.minDimension * 0.18f
        val pulseRadius = size.minDimension * (0.12f + (0.035f * pulse))
        val pulseAlpha = 0.18f + (0.16f * (1f - pulse))
        val gradient = Brush.sweepGradient(
            colors = listOf(RingCyan, RingBlue, RingViolet, RingCyan)
        )

        drawCircle(
            color = RingCenterGlow,
            radius = pulseRadius,
            alpha = pulseAlpha
        )

        listOf(rippleA, rippleB, rippleC).forEach { progress ->
            val easedProgress = FastOutSlowInEasing.transform(progress)
            val radius = minRadius + ((maxRadius - minRadius) * easedProgress)
            val strokeWidth = size.minDimension * (0.04f - (0.022f * easedProgress))
            val ringAlpha = (1f - easedProgress) * 0.95f
            drawCircle(
                brush = gradient,
                radius = radius,
                alpha = ringAlpha,
                style = Stroke(width = strokeWidth)
            )
        }

        drawCircle(
            brush = gradient,
            radius = size.minDimension * 0.17f,
            alpha = 0.9f,
            style = Stroke(width = size.minDimension * 0.028f)
        )

        drawCircle(
            color = lerp(RingBlue, RingViolet, 0.45f),
            radius = size.minDimension * 0.05f,
            alpha = 0.28f
        )
    }
}
