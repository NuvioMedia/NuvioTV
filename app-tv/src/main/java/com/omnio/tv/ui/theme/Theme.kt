package com.omnio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.omnio.tv.core.uishared.LocalAppTheme
import com.omnio.tv.core.uishared.LocalOmnioColors
import com.omnio.tv.core.uishared.LocalOmnioExtendedColors
import com.omnio.tv.core.uishared.OmnioColorScheme
import com.omnio.tv.core.uishared.OmnioExtendedColors
import com.omnio.tv.core.uishared.ThemeColors
import com.omnio.tv.domain.model.AppFont
import com.omnio.tv.domain.model.AppTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OmnioTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appFont: AppFont = AppFont.INTER,
    content: @Composable () -> Unit
) {
    val palette = ThemeColors.getColorPalette(appTheme)
    val colorScheme = OmnioColorScheme(palette)

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

    val extendedColors = OmnioExtendedColors(
        backgroundElevated = colorScheme.BackgroundElevated,
        backgroundCard = colorScheme.BackgroundCard,
        textSecondary = colorScheme.TextSecondary,
        textTertiary = colorScheme.TextTertiary,
        focusRing = colorScheme.FocusRing,
        focusBackground = colorScheme.FocusBackground,
        rating = colorScheme.Rating
    )

    CompositionLocalProvider(
        LocalOmnioColors provides colorScheme,
        LocalOmnioExtendedColors provides extendedColors,
        LocalAppTheme provides appTheme
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = buildTvMaterial3Typography(getFontFamily(appFont)),
            content = content
        )
    }
}

object OmnioTheme {
    val colors: OmnioColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioColors.current

    val extendedColors: OmnioExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOmnioExtendedColors.current

    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current
}
