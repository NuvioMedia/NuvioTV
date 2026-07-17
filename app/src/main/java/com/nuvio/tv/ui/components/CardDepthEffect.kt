package com.nuvio.tv.ui.components

import androidx.compose.foundation.border
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.DEFAULT_CARD_DEPTH_EDGE_COVERAGE

val LocalCardDepthStyle = staticCompositionLocalOf { CardDepthStyle() }

fun Modifier.nuvioCardDepth(
    shape: Shape,
    surface: CardDepthSurface,
    style: CardDepthStyle,
    fallbackBorderAlpha: Float = 0f
): Modifier {
    if (!style.isEnabledFor(surface)) {
        return if (fallbackBorderAlpha > 0f) {
            border(
                width = 1.dp,
                color = Color.White.copy(alpha = fallbackBorderAlpha),
                shape = shape
            )
        } else {
            this
        }
    }

    return cardDepthVisual(
        shape = shape,
        edgeStrength = style.edgeStrength.toFloat(),
        sheenStrength = style.sheenStrength.toFloat(),
        edgeCoverage = style.edgeCoverage.toFloat()
    )
}

fun Modifier.cardDepthVisual(
    shape: Shape,
    edgeStrength: Float,
    sheenStrength: Float,
    edgeCoverage: Float = DEFAULT_CARD_DEPTH_EDGE_COVERAGE.toFloat()
): Modifier {
    val edgeTop = edgeStrength.coerceIn(0f, 100f) / 100f
    val sheen = sheenStrength.coerceIn(0f, 100f) / 100f
    val coverage = edgeCoverage.coerceIn(0f, 100f) / 100f

    val withEdge = if (edgeTop > 0f) {
        border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = edgeTop),
                    Color.White.copy(alpha = edgeTop * (0.33f + 0.67f * coverage)),
                    Color.White.copy(alpha = edgeTop * coverage)
                )
            ),
            shape = shape
        )
    } else {
        this
    }

    return if (sheen > 0f) {
        withEdge.drawWithContent {
            drawContent()
            val sheenHeight = size.height * 0.22f
            if (sheenHeight > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = sheen),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = sheenHeight
                    ),
                    size = Size(size.width, sheenHeight)
                )
            }
        }
    } else {
        withEdge
    }
}
