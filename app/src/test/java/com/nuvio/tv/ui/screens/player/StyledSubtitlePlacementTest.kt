package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class StyledSubtitlePlacementTest {

    @Test
    fun `default offset matches the subtitle view placement`() {
        val placement = subtitleBottomPlacement(heightPx = 1080, verticalOffset = 5, liftPx = 0f)

        assertEquals(13, placement.paddingPx)
        assertEquals(0.08f, placement.fraction, 0.0001f)
        assertEquals(98.36f, placement.insetPx(1080), 0.01f)
    }

    @Test
    fun `lift raises subtitles above the controls`() {
        val placement = subtitleBottomPlacement(heightPx = 1080, verticalOffset = 5, liftPx = 300f)

        assertEquals(300f, placement.insetPx(1080), 0.01f)
    }

    @Test
    fun `lift and offsets stay within the allowed range`() {
        assertEquals(0.5f, subtitleBottomPlacement(1080, 5, 2_000f).fraction, 0.0001f)
        val lowest = subtitleBottomPlacement(1080, -20, 0f)
        assertEquals(0, lowest.paddingPx)
        assertEquals(0f, lowest.fraction, 0.0001f)
    }

    @Test
    fun `styled metrics use the overlay padding and font`() {
        val metrics = SubtitleTextMetrics(
            textSizePx = 48f,
            bold = false,
            scaleX = 1f,
            innerPaddingPx = 6f,
            wrapWidthPx = 1908f,
            centerXPx = 960f
        )

        val styled = metrics.forStyledRenderer(typeface = null)

        assertEquals(20f, styled.innerPaddingPx, 0.0001f)
        assertEquals(1880f, styled.wrapWidthPx, 0.0001f)
    }

    @Test
    fun `picking a background color keeps its opacity or makes it visible`() {
        val translucentBlack = Color.Black.copy(alpha = 0.5f).toArgb()

        assertEquals(Color.White.copy(alpha = 0.5f).toArgb(), subtitleBackgroundColorFor(Color.White, translucentBlack))
        assertEquals(
            Color.White.copy(alpha = SUBTITLE_BACKGROUND_DEFAULT_OPACITY).toArgb(),
            subtitleBackgroundColorFor(Color.White, Color.Transparent.toArgb())
        )
        assertEquals(Color.Black.toArgb(), selectedSubtitleBackgroundPreset(translucentBlack))
        assertEquals(0, selectedSubtitleBackgroundPreset(Color.Transparent.toArgb()))
    }
}
