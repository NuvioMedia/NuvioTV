package com.omnio.tv.core.uishared

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.omnio.tv.domain.model.AppTheme

/**
 * Form-factor-neutral extended colors. The TV / phone OmnioTheme wrappers each
 * build one of these from the active [OmnioColorScheme] and provide it through
 * [LocalOmnioExtendedColors] so screens can read non-Material slots
 * (focus ring, rating gold, text tertiary, …) uniformly.
 */
data class OmnioExtendedColors(
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val rating: Color
)

val LocalOmnioColors = staticCompositionLocalOf {
    OmnioColorScheme(ThemeColors.Ocean)
}

val LocalOmnioExtendedColors = staticCompositionLocalOf {
    OmnioExtendedColors(
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF242424),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFF808080),
        focusRing = ThemeColors.Ocean.focusRing,
        focusBackground = ThemeColors.Ocean.focusBackground,
        rating = Color(0xFFFFD700)
    )
}

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }
