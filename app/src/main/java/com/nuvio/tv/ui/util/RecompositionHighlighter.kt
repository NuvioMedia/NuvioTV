package com.nuvio.tv.ui.util

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.debugInspectorInfo
import kotlinx.coroutines.delay
import kotlin.math.min

val LocalRecompositionHighlighterEnabled = compositionLocalOf { false }

/**
 * A [Modifier] that draws a border around elements that are recomposing.
 * 
 * - Green: 1-2 recompositions
 * - Yellow: 3-5 recompositions
 * - Red: 6+ recompositions
 * 
 * Includes a "cool down" mechanism: if no recompositions occur for 3 seconds,
 * the counter resets and the highlighter disappears.
 */
@Stable
fun Modifier.recompositionHighlighter(): Modifier = this.composed(inspectorInfo = debugInspectorInfo {
    name = "recompositionHighlighter"
}) {
    val enabled = LocalRecompositionHighlighterEnabled.current
    if (!enabled) return@composed Modifier

    val numRecompositions = remember { LongArray(1) { 0L } }
    numRecompositions[0]++

    val totalRecompositions = remember { mutableLongStateOf(0L) }
    
    // The count increments on every recomposition of this block.
    // The delay resets it after 3 seconds of inactivity.
    LaunchedEffect(numRecompositions[0]) {
        totalRecompositions.longValue++
        delay(3000)
        totalRecompositions.longValue = 0
    }

    drawWithCache {
        onDrawWithContent {
            drawContent()
            val num = totalRecompositions.longValue
            if (num > 0) {
                val alpha = 0.6f
                val color = when {
                    num <= 2 -> Color.Green.copy(alpha = alpha)
                    num <= 5 -> Color.Yellow.copy(alpha = alpha)
                    else -> Color.Red.copy(alpha = alpha)
                }
                val strokeWidth = min(size.width, size.height) * 0.05f
                drawRect(
                    brush = SolidColor(color),
                    size = size,
                    style = Stroke(width = strokeWidth.coerceIn(2f, 12f))
                )
            }
        }
    }
}
