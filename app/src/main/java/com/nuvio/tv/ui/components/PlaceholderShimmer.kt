package com.nuvio.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private const val PLACEHOLDER_SHIMMER_DISTANCE_PX = 1000f
private const val PLACEHOLDER_SHIMMER_WIDTH_FRACTION = 0.6f
private val PLACEHOLDER_SHIMMER_COLOR_STOPS = arrayOf(
    0.0f to Color.Transparent,
    0.4f to Color.White.copy(alpha = 0.07f),
    0.5f to Color.White.copy(alpha = 0.13f),
    0.6f to Color.White.copy(alpha = 0.07f),
    1.0f to Color.Transparent
)

@Composable
fun rememberPlaceholderShimmerOffsetState(label: String): State<Float> {
    val shimmerTransition = rememberInfiniteTransition(label = label)
    return shimmerTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
}

fun Modifier.placeholderCardShimmer(
    shimmerOffsetState: State<Float>,
    backgroundColor: Color? = null
): Modifier = drawWithCache {
    onDrawBehind {
        backgroundColor?.let { drawRect(color = it) }
        val shimmerOffset = shimmerOffsetState.value
        drawRect(
            brush = Brush.linearGradient(
                colorStops = PLACEHOLDER_SHIMMER_COLOR_STOPS,
                start = Offset(shimmerOffset * PLACEHOLDER_SHIMMER_DISTANCE_PX, 0f),
                end = Offset(
                    (shimmerOffset + PLACEHOLDER_SHIMMER_WIDTH_FRACTION) * PLACEHOLDER_SHIMMER_DISTANCE_PX,
                    0f
                )
            )
        )
    }
}
