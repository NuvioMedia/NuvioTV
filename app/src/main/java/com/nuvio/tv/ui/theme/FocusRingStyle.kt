package com.nuvio.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp

@Immutable
class NuvioFocusRingStyle internal constructor(
    val solidColor: Color,
    private val gradientColors: List<Color>
) {
    private val fullBrush = createThemeBrush(gradientColors)

    fun brush(alpha: Float = 1f): Brush {
        val normalizedAlpha = alpha.coerceIn(0f, 1f)
        if (normalizedAlpha == 1f) return fullBrush
        return createThemeBrush(gradientColors.map { color ->
            color.copy(alpha = color.alpha * normalizedAlpha)
        })
    }

    fun border(width: Dp, alpha: Float = 1f): BorderStroke {
        return BorderStroke(width, brush(alpha))
    }
}

fun ThemeColorPalette.accentBrush(): Brush = createThemeBrush(accentGradient)

internal fun createThemeBrush(colors: List<Color>): Brush {
    return if (colors.size == 1) {
        SolidColor(colors.first())
    } else {
        Brush.linearGradient(colors)
    }
}

internal fun createFocusRingStyle(palette: ThemeColorPalette): NuvioFocusRingStyle {
    return NuvioFocusRingStyle(
        solidColor = palette.focusRing,
        gradientColors = palette.focusRingGradient
    )
}
