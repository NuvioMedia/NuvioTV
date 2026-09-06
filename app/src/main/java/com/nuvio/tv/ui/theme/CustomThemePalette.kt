package com.nuvio.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.nuvio.tv.domain.model.CustomThemeColors

fun CustomThemeColors.toColorPalette(): ThemeColorPalette {
    val gradient = colors.map { Color(it or 0xFF000000.toInt()) }
    val accent = gradient[1]
    fun surface(base: Long, tint: Float) = lerp(Color(base), accent, tint)
    fun foreground(color: Color) = if (color.luminance() > 0.179f) Color.Black else Color.White

    return ThemeColorPalette(
        secondary = accent,
        secondaryVariant = gradient[2],
        onSecondary = foreground(accent),
        onSecondaryVariant = foreground(gradient[2]),
        accentGradient = gradient,
        focusRing = gradient.maxBy { it.luminance() },
        focusRingGradient = gradient,
        focusBackground = surface(0xFF242424, 0.18f),
        background = surface(0xFF0C0D0F, 0.025f),
        backgroundElevated = surface(0xFF17191D, 0.045f),
        backgroundCard = surface(0xFF20242A, 0.06f),
        surface = surface(0xFF1C1F23, 0.05f),
        surfaceVariant = surface(0xFF292E35, 0.06f),
        panel = surface(0xFF17191D, 0.045f),
        field = surface(0xFF24282E, 0.055f),
        menu = surface(0xFF1C1F23, 0.05f),
        modal = surface(0xFF17191D, 0.045f)
    )
}
