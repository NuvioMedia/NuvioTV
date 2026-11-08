package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectModeVideoFractionTest {

    private val screen = 16f / 9f
    private val scope = 2.4f

    private fun assertFraction(expectedX: Float, expectedY: Float, actual: AspectScale) {
        assertEquals(expectedX, actual.scaleX, 0.0001f)
        assertEquals(expectedY, actual.scaleY, 0.0001f)
    }

    @Test
    fun unknownAspect_fillsTheScreen() {
        AspectMode.entries.forEach { mode ->
            assertFraction(1f, 1f, aspectModeVideoFraction(mode, videoAspect = null, viewAspect = screen))
            assertFraction(1f, 1f, aspectModeVideoFraction(mode, videoAspect = screen, viewAspect = 0f))
        }
    }

    @Test
    fun scopeVideo_matchesTheFrameLayoutOfEachMode() {
        val ratio = scope / screen
        assertFraction(1f, 1f / ratio, aspectModeVideoFraction(AspectMode.ORIGINAL, scope, screen))
        assertFraction(ratio, 1f, aspectModeVideoFraction(AspectMode.FULL_SCREEN, scope, screen))
        assertFraction(1f, 1f, aspectModeVideoFraction(AspectMode.STRETCH, scope, screen))
        assertFraction(1f, 1f, aspectModeVideoFraction(AspectMode.FORCE_STRETCH, scope, screen))
        assertFraction(1.15f, 1.15f / ratio, aspectModeVideoFraction(AspectMode.SLIGHT_ZOOM, scope, screen))
        assertFraction(1.33f, 1.33f / ratio, aspectModeVideoFraction(AspectMode.CINEMA_ZOOM, scope, screen))
        assertFraction(ratio, 1f, aspectModeVideoFraction(AspectMode.VERTICAL_STRETCH, scope, screen))
        assertFraction(1f, 1f / ratio, aspectModeVideoFraction(AspectMode.HORIZONTAL_STRETCH, scope, screen))
    }

    @Test
    fun letterboxedWideFrame_zoomsPastTheScreenInBothDirections() {
        assertFraction(1.15f, 1.15f, aspectModeVideoFraction(AspectMode.SLIGHT_ZOOM, screen, screen))
        assertFraction(1.33f, 1.33f, aspectModeVideoFraction(AspectMode.CINEMA_ZOOM, screen, screen))
        assertFraction(1f, 2.39f / screen, aspectModeVideoFraction(AspectMode.FORCE_STRETCH, screen, screen))
    }

    @Test
    fun overflowsView_onlyWhenTheVideoIsLargerThanTheScreen() {
        assertFalse(aspectModeVideoFraction(AspectMode.ORIGINAL, scope, screen).overflowsView())
        assertFalse(aspectModeVideoFraction(AspectMode.STRETCH, scope, screen).overflowsView())
        assertFalse(aspectModeVideoFraction(AspectMode.FULL_SCREEN, screen, screen).overflowsView())
        assertTrue(aspectModeVideoFraction(AspectMode.FULL_SCREEN, scope, screen).overflowsView())
        assertTrue(aspectModeVideoFraction(AspectMode.CINEMA_ZOOM, screen, screen).overflowsView())
        assertTrue(aspectModeVideoFraction(AspectMode.FORCE_STRETCH, screen, screen).overflowsView())
    }
}
