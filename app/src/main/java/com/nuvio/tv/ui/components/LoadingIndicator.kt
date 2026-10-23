package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.util.lerp

private val LoadingEasing = CubicBezierEasing(0.333f, 0f, 0.667f, 1f)

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val brush = if (color == Color.Unspecified) NuvioTheme.palette.accentBrush() else SolidColor(color)
    val frame = rememberInfiniteTransition(label = "loading_indicator").animateFloat(
        initialValue = 0f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2000, easing = LinearEasing)),
        label = "loading_frame"
    )
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Spacer(
            modifier = Modifier
                .size(NuvioTheme.spacing.xxxl)
                .drawWithCache {
                    val scale = size.minDimension * 0.75f / 350f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = 150f * scale
                    val topLeft = center - Offset(radius, radius)
                    val arcSize = Size(radius * 2f, radius * 2f)
                    val stroke = Stroke(width = 50f * scale, cap = StrokeCap.Round)
                    onDrawBehind {
                        val currentFrame = frame.value
                        val start = lerp(0f, 99f, LoadingEasing.transform(((currentFrame - 10f) / 50f).coerceIn(0f, 1f)))
                        val end = lerp(1f, 100f, LoadingEasing.transform((currentFrame / 50f).coerceIn(0f, 1f)))
                        drawArc(
                            brush = brush,
                            startAngle = -90f + currentFrame / 60f * 363f + start * 3.6f,
                            sweepAngle = (end - start) * 3.6f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = stroke
                        )
                    }
                }
        )
    }
}
