package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerProgressFillTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun changingProgressRedrawsWithoutRecompositionOrRemeasure() {
        val played = mutableFloatStateOf(0.2f)
        val buffered = mutableFloatStateOf(0.4f)
        var compositions = 0
        var measures = 0
        compose.setContent {
            SideEffect { compositions++ }
            Box(
                Modifier.size(200.dp, 12.dp).testTag("track")
                    .layout { measurable, constraints ->
                        measures++
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) { child.place(0, 0) }
                    }
                    .background(Color.Black)
                    .playerProgressFill({ played.floatValue }, { buffered.floatValue }, Color.Red, SolidColor(Color.Red))
            )
        }
        compose.waitForIdle()
        val initialCompositions = compositions
        val initialMeasures = measures
        assertPixel(0.1f, 1f)
        assertPixel(0.3f, 0.35f)
        assertPixel(0.5f, 0f)
        compose.runOnIdle { played.floatValue = 0.6f; buffered.floatValue = 0.8f }
        compose.waitForIdle()
        assertPixel(0.5f, 1f)
        assertPixel(0.7f, 0.35f)
        assertPixel(0.9f, 0f)
        compose.runOnIdle {
            assertEquals(initialCompositions, compositions)
            assertEquals(initialMeasures, measures)
            played.floatValue = 0f
            buffered.floatValue = 0f
        }
        compose.waitForIdle()
        assertPixel(0.1f, 0f)
    }

    @Test
    fun gradientUsesPlayedWidthRatherThanEntireTrackWidth() {
        compose.setContent {
            Box(Modifier.size(200.dp, 12.dp).testTag("track").background(Color.Black)
                .playerProgressFill({ 0.5f }, { 0.75f }, Color.Red,
                    Brush.horizontalGradient(listOf(Color.Red, Color.Blue))))
        }
        compose.waitForIdle()
        // Quarter-track lies at midpoint of half-track played fill.
        assertPixel(0.25f, 0.5f, 0.5f)
        assertPixel(0.6f, 0.35f)
        assertPixel(0.9f, 0f)
    }

    private fun assertPixel(fraction: Float, red: Float, blue: Float = 0f) {
        val pixels = compose.onNodeWithTag("track").captureToImage().toPixelMap()
        val pixel = pixels[(pixels.width * fraction).toInt(), pixels.height / 2]
        assertEquals(red, pixel.red, 0.03f)
        assertEquals(0f, pixel.green, 0.03f)
        assertEquals(blue, pixel.blue, 0.03f)
    }
}
