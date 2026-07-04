package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.SettingsUiStyle

data class NuvioExtendedColors(
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val rating: Color
)

val LocalNuvioColors = staticCompositionLocalOf {
    NuvioColorScheme(ThemeColors.Ocean)
}

val LocalNuvioExtendedColors = staticCompositionLocalOf {
    NuvioExtendedColors(
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF242424),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFF808080),
        focusRing = ThemeColors.Ocean.focusRing,
        focusBackground = ThemeColors.Ocean.focusBackground,
        rating = Color(0xFFFFD700)
    )
}

val LocalNuvioTextStyles = staticCompositionLocalOf { NuvioTextStyles }

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }

val LocalSettingsUiStyle = staticCompositionLocalOf { SettingsUiStyle.CLASSIC }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appFont: AppFont = AppFont.INTER,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false,
    settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    content: @Composable () -> Unit
) {
    val palette = ThemeColors.getColorPalette(appTheme)
    val colorScheme = NuvioColorScheme(palette, amoledMode, amoledSurfacesMode)
    val typography = buildNuvioTypography(getFontFamily(appFont))
    val textStyles = buildNuvioTextStyles(typography)

    val materialColorScheme = darkColorScheme(
        primary = colorScheme.Primary,
        onPrimary = colorScheme.OnPrimary,
        secondary = colorScheme.Secondary,
        onSecondary = colorScheme.OnSecondary,
        background = colorScheme.Background,
        surface = colorScheme.Surface,
        surfaceVariant = colorScheme.SurfaceVariant,
        onBackground = colorScheme.TextPrimary,
        onSurface = colorScheme.TextPrimary,
        onSurfaceVariant = colorScheme.TextSecondary,
        error = colorScheme.Error
    )

    val extendedColors = NuvioExtendedColors(
        backgroundElevated = colorScheme.BackgroundElevated,
        backgroundCard = colorScheme.BackgroundCard,
        textSecondary = colorScheme.TextSecondary,
        textTertiary = colorScheme.TextTertiary,
        focusRing = colorScheme.FocusRing,
        focusBackground = colorScheme.FocusBackground,
        rating = colorScheme.Rating
    )

    CompositionLocalProvider(
        LocalNuvioColors provides colorScheme,
        LocalNuvioExtendedColors provides extendedColors,
        LocalNuvioTextStyles provides textStyles,
        LocalAppTheme provides appTheme,
        LocalSettingsUiStyle provides settingsUiStyle
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = typography,
            content = content
        )
    }
}

object NuvioTheme {
    val colors: NuvioColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioColors.current

    val extendedColors: NuvioExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioExtendedColors.current

    val textStyles: NuvioTextStyleTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioTextStyles.current

    val spacing: NuvioSpacingTokens
        get() = NuvioSpacing.tokens

    val radii: NuvioRadiusTokens
        get() = NuvioRadii.tokens

    val shapes: NuvioShapeTokens
        get() = NuvioShapes.tokens

    val sizes: NuvioSizeTokens
        get() = NuvioSizes.tokens

    val strokes: NuvioStrokeTokens
        get() = NuvioStrokes.tokens

    val elevations: NuvioElevationTokens
        get() = NuvioElevations.tokens

    val effects: NuvioEffectTokens
        get() = NuvioEffects.tokens

    val motion: NuvioMotionTokens
        get() = NuvioMotion.tokens

    val focus: NuvioFocusTokens
        get() = NuvioFocus.tokens

    val layout: NuvioLayoutTokens
        get() = NuvioLayout.tokens

    val media: NuvioMediaTokens
        get() = NuvioMedia.tokens

    val components: NuvioComponentTokens
        get() = NuvioComponents.tokens

    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current

    val settingsUiStyle: SettingsUiStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalSettingsUiStyle.current
}
