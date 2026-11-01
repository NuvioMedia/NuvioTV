package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp

/**
 * Scales the RGB of everything this node draws by [factor], keeping alpha, so player controls
 * drawn over HDR video are not rendered at full graphics brightness. A factor of 1 or more
 * leaves the node untouched and adds no offscreen layer.
 */
internal fun Modifier.playerControlsBrightness(factor: Float): Modifier {
    if (factor >= 1f) return this
    val scale = factor.coerceIn(0f, 1f)
    return this.drawWithCache {
        val paint = Paint().apply {
            colorFilter = ColorFilter.colorMatrix(
                ColorMatrix().apply { setToScale(scale, scale, scale, 1f) }
            )
        }
        // Focused controls scale up and cast glows past their own bounds; the layer has to
        // cover that overflow or it gets clipped. The canvas clip still bounds the allocation.
        val layerBounds = size.toRect().inflate(LAYER_OVERFLOW.toPx())
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(layerBounds, paint)
                drawContent()
                canvas.restore()
            }
        }
    }
}

private val LAYER_OVERFLOW = 48.dp
