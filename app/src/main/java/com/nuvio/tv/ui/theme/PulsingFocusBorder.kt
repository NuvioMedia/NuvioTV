package com.nuvio.tv.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border

/**
 * Pulsing focus border animation utilities.
 * Provides a smooth pulsing effect for focus borders that never completely disappears.
 */
object PulsingFocusBorder {
    
    private const val ANIMATION_DURATION_MS = 1000
    private const val MIN_ALPHA = 0.35f
    private const val MAX_ALPHA = 1f
    
    /**
     * Creates a pulsing border color that animates between MIN_ALPHA and MAX_ALPHA.
     * The animation only runs when isFocused is true.
     * 
     * @param baseColor The base color for the border (typically NuvioColors.FocusRing)
     * @param isFocused Whether the component is currently focused
     * @return The animated border color
     */
    @Composable
    fun animatedBorderColor(
        baseColor: Color = NuvioColors.FocusRing,
        isFocused: Boolean
    ): Color {
        val infiniteTransition = rememberInfiniteTransition(label = "borderPulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = MIN_ALPHA,
            targetValue = MAX_ALPHA,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = ANIMATION_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "borderAlpha"
        )
        
        return if (isFocused) {
            baseColor.copy(alpha = pulseAlpha)
        } else {
            baseColor
        }
    }
    
    /**
     * Creates a pulsing Border for TV Material3 components.
     * 
     * @param baseColor The base color for the border
     * @param isFocused Whether the component is currently focused
     * @param borderWidth The width of the border in Dp
     * @param shape The shape of the border
     * @return A Border with animated color when focused
     */
    @Composable
    fun border(
        baseColor: Color = NuvioColors.FocusRing,
        isFocused: Boolean,
        borderWidth: androidx.compose.ui.unit.Dp = 2.dp,
        shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
    ): Border {
        val animatedColor = animatedBorderColor(baseColor, isFocused)
        return Border(
            border = BorderStroke(borderWidth, animatedColor),
            shape = shape
        )
    }
}

/**
 * Remember focus state and provide animated border color.
 * Usage:
 * ```
 * var isFocused by remember { mutableStateOf(false) }
 * val animatedBorderColor = rememberPulsingFocusBorderColor(isFocused)
 * 
 * Card(
 *     modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
 *     border = CardDefaults.border(
 *         focusedBorder = Border(
 *             border = BorderStroke(2.dp, animatedBorderColor),
 *             shape = cardShape
 *         )
 *     )
 * )
 * ```
 */
@Composable
fun rememberPulsingFocusBorderColor(
    baseColor: Color = NuvioColors.FocusRing,
    isFocused: Boolean
): Color {
    return PulsingFocusBorder.animatedBorderColor(baseColor, isFocused)
}
