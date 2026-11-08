package com.nuvio.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.nuvio.tv.domain.model.CustomThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemePaletteTest {
    @Test
    fun solidColorsUseSolidAccentAndFocusBrushesWithTheExistingStorageFormat() {
        val colors = CustomThemeColors.solid(0x123456)
        val color = Color(0xFF123456)
        val palette = colors.toColorPalette()

        assertTrue(colors.isSolid)
        assertEquals("#123456,#123456,#123456", colors.encode())
        assertEquals(colors, CustomThemeColors.decode(colors.encode()))
        assertEquals(listOf(color), palette.accentGradient)
        assertEquals(listOf(color), palette.focusRingGradient)
        assertEquals(SolidColor(color), palette.accentBrush())
        assertEquals(SolidColor(color), createFocusRingStyle(palette).brush())
        assertEquals(SolidColor(color.copy(alpha = 0.5f)), createFocusRingStyle(palette).brush(0.5f))
        assertEquals(color, palette.secondary)
        assertEquals(color, palette.secondaryVariant)
    }

    @Test
    fun gradientsWithMatchingEndColorsKeepAllThreeStops() {
        val colors = CustomThemeColors(0xFF0000, 0x0000FF, 0xFF0000)
        val palette = colors.toColorPalette()
        val stops = listOf(Color.Red, Color.Blue, Color.Red)

        assertEquals(colors, CustomThemeColors.decode(colors.encode()))
        assertEquals(stops, palette.accentGradient)
        assertEquals(stops, palette.focusRingGradient)
    }

    @Test
    fun solidColorsKeepReadableForegrounds() {
        val lightPalette = CustomThemeColors.solid(0xFFFFFF).toColorPalette()
        val darkPalette = CustomThemeColors.solid(0x000000).toColorPalette()

        assertEquals(Color.Black, lightPalette.onSecondary)
        assertEquals(Color.Black, lightPalette.onSecondaryVariant)
        assertEquals(Color.White, darkPalette.onSecondary)
        assertEquals(Color.White, darkPalette.onSecondaryVariant)
    }
}
