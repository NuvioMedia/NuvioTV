package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Slim visual indicator bar under the audio amplification stepper. The right end carries a subtle
 * wash using the theme's Error color, and the fill warms as amplification climbs toward max dB.
 * Clamps to 0 when amplification is disabled or unavailable.
 */
@Composable
internal fun VolumeBoostBar(
    gainDb: Int,
    maxDb: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = NuvioTheme.colors.Secondary
    val boostEndColor = NuvioTheme.colors.Error
    val effectiveDb = if (enabled) gainDb.coerceIn(0, maxDb) else 0
    val target = if (maxDb > 0) effectiveDb.toFloat() / maxDb else 0f
    val fraction by animateFloatAsState(target, tween(160), label = "volumeBoostBar")

    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
            .drawBehind {
                val alpha = if (enabled) 1f else 0.35f
                drawRect(Color.White.copy(alpha = 0.18f * alpha))
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, boostEndColor.copy(alpha = 0.35f * alpha)),
                        startX = size.width * 0.45f,
                        endX = size.width,
                    ),
                )
                val filled = size.width * fraction
                if (filled > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(listOf(accent, boostEndColor), startX = 0f, endX = size.width),
                        topLeft = Offset.Zero,
                        size = Size(filled, size.height),
                        alpha = alpha,
                    )
                }
            },
    )
}
