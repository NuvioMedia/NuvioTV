package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibassOverlayGeometryTest {

    @Test
    fun margins_forLetterboxedCinemaVideo_areTopAndBottomBars() {
        assertEquals(
            LibassOverlayMargins(top = 140, bottom = 140, left = 0, right = 0),
            libassOverlayMargins(1920, 1080, 0f, 140f, 1920f, 940f)
        )
    }

    @Test
    fun margins_forPillarboxedFourByThreeVideo_areLeftAndRightBars() {
        assertEquals(
            LibassOverlayMargins(top = 0, bottom = 0, left = 240, right = 240),
            libassOverlayMargins(1920, 1080, 240f, 0f, 1680f, 1080f)
        )
    }

    @Test
    fun margins_forFullOverlay_areZero() {
        assertEquals(
            LibassOverlayMargins(top = 0, bottom = 0, left = 0, right = 0),
            libassOverlayMargins(1920, 1080, 0f, 0f, 1920f, 1080f)
        )
    }

    @Test
    fun margins_forCroppedVideo_areSigned() {
        assertEquals(
            LibassOverlayMargins(top = -60, bottom = -60, left = 0, right = 0),
            libassOverlayMargins(1920, 1080, 0f, -60f, 1920f, 1140f)
        )
    }

    @Test
    fun margins_roundFractionalBoundsSymmetrically() {
        assertEquals(
            LibassOverlayMargins(top = 141, bottom = 139, left = 1, right = 0),
            libassOverlayMargins(1920, 1080, 0.6f, 140.6f, 1919.6f, 940.6f)
        )
    }

    @Test
    fun margins_forUnmeasuredOrInvalidBounds_areAbsent() {
        assertNull(libassOverlayMargins(0, 1080, 0f, 0f, 1920f, 1080f))
        assertNull(libassOverlayMargins(1920, 1080, 0f, 0f, 0f, 1080f))
    }
}
