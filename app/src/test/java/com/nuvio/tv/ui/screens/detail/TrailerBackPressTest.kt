package com.nuvio.tv.ui.screens.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerBackPressTest {
    @Test
    fun `back from full-screen trailer continues in background when play in background is on`() {
        assertTrue(
            shouldContinueTrailerInBackgroundOnBack(
                isTrailerPlaying = true,
                autoplayEnabled = true,
                playInBackground = true
            )
        )
    }

    @Test
    fun `back from full-screen trailer stops when play in background is off`() {
        assertFalse(
            shouldContinueTrailerInBackgroundOnBack(
                isTrailerPlaying = true,
                autoplayEnabled = true,
                playInBackground = false
            )
        )
    }

    @Test
    fun `back from full-screen trailer stops when autoplay is off`() {
        // Play in background is only offered under autoplay, so a stale value is ignored.
        assertFalse(
            shouldContinueTrailerInBackgroundOnBack(
                isTrailerPlaying = true,
                autoplayEnabled = false,
                playInBackground = true
            )
        )
    }

    @Test
    fun `back does nothing to background playback when no full-screen trailer is showing`() {
        assertFalse(
            shouldContinueTrailerInBackgroundOnBack(
                isTrailerPlaying = false,
                autoplayEnabled = true,
                playInBackground = true
            )
        )
    }
}

