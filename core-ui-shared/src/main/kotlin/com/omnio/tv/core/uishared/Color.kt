package com.omnio.tv.core.uishared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Dynamic color scheme that changes based on selected theme.
 * Background colors have subtle theme tinting.
 * Accent colors (secondary, focus) change per theme.
 */
class OmnioColorScheme(palette: ThemeColorPalette) {
    // Primary Background - Theme dependent with subtle tinting
    val Background = palette.background
    val BackgroundElevated = palette.backgroundElevated
    val BackgroundCard = palette.backgroundCard

    // Surface colors (constant)
    val Surface = Color(0xFF1E1E1E)
    val SurfaceVariant = Color(0xFF2D2D2D)

    // Primary accent - Neutral Grey (constant)
    val Primary = Color(0xFF9E9E9E)
    val PrimaryVariant = Color(0xFF6F6F6F)
    val OnPrimary = Color(0xFFFFFFFF)

    // Secondary accent - Theme dependent
    val Secondary = palette.secondary
    val SecondaryVariant = palette.secondaryVariant
    val OnSecondary = palette.onSecondary
    val OnSecondaryVariant = palette.onSecondaryVariant

    // Text colors (constant)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextTertiary = Color(0xFF808080)
    val TextDisabled = Color(0xFF4D4D4D)

    // Focus states - Theme dependent
    val FocusRing = palette.focusRing
    val FocusBackground = palette.focusBackground

    // Status colors (constant)
    val Rating = Color(0xFFFFD700)
    val Error = Color(0xFFCF6679)
    val Success = Color(0xFF4CAF50)

    // Borders
    val Border = Color(0xFF333333)
    val BorderFocused = palette.focusRing
}

/**
 * Legacy OmnioColors object for backwards compatibility.
 * Components should migrate to using OmnioTheme.colors instead.
 * Reads the active scheme directly from [LocalOmnioColors] so this object
 * has no dependency on the TV-only OmnioTheme accessor.
 */
object OmnioColors {
    // Dynamic background colors - Theme dependent with subtle tinting
    val Background: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current.Background

    val BackgroundElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current.BackgroundElevated

    val BackgroundCard: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current.BackgroundCard

    // Surface colors (constant)
    val Surface = Color(0xFF1E1E1E)
    val SurfaceVariant = Color(0xFF2D2D2D)

    // Primary accent - Neutral Grey (constant)
    val Primary = Color(0xFF9E9E9E)
    val PrimaryVariant = Color(0xFF6F6F6F)
    val OnPrimary = Color(0xFFFFFFFF)

    // Text colors (constant)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val TextTertiary = Color(0xFF808080)
    val TextDisabled = Color(0xFF4D4D4D)

    // Status colors (constant)
    val Rating = Color(0xFFFFD700)
    val Error = Color(0xFFCF6679)
    val Success = Color(0xFF4CAF50)

    // Borders (non-focus constant)
    val Border = Color(0xFF333333)

    // Dynamic accent colors - Theme dependent
    val Secondary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current.Secondary

    val SecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current.SecondaryVariant

    val OnSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current.OnSecondary

    val OnSecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current.OnSecondaryVariant

    val FocusRing: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current.FocusRing

    val FocusBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current.FocusBackground

    val BorderFocused: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current.BorderFocused
}
