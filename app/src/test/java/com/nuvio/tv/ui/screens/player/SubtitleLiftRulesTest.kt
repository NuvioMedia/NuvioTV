package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLiftRulesTest {

    @Test
    fun `overlay preview uses the configured bottom offset instead of lifting`() {
        val lift = computeSubtitleLiftPx(
            previewConfiguredPosition = true,
            controlsVisible = true,
            controlsClearancePx = 180f,
            fallbackControlsLiftPx = 130f,
            skipVisible = true,
            skipClearancePx = 90f,
            postPlayVisible = true,
            postPlayClearancePx = 120f,
            seekVisible = true,
            seekClearancePx = 70f,
        )

        assertEquals(0f, lift)
    }

    @Test
    fun `controls lift uses the measured clearance when controls are actually visible`() {
        val lift = computeSubtitleLiftPx(
            previewConfiguredPosition = false,
            controlsVisible = true,
            controlsClearancePx = 180f,
            fallbackControlsLiftPx = 130f,
            skipVisible = false,
            skipClearancePx = 90f,
            postPlayVisible = false,
            postPlayClearancePx = 120f,
            seekVisible = false,
            seekClearancePx = 70f,
        )

        assertEquals(180f, lift)
    }

    @Test
    fun `hidden controls do not lift just because showControls stayed true`() {
        val lift = computeSubtitleLiftPx(
            previewConfiguredPosition = false,
            controlsVisible = false,
            controlsClearancePx = 180f,
            fallbackControlsLiftPx = 130f,
            skipVisible = false,
            skipClearancePx = 0f,
            postPlayVisible = false,
            postPlayClearancePx = 0f,
            seekVisible = false,
            seekClearancePx = 0f,
        )

        assertEquals(0f, lift)
    }

    @Test
    fun `the tallest visible bottom overlay wins`() {
        val lift = computeSubtitleLiftPx(
            previewConfiguredPosition = false,
            controlsVisible = false,
            controlsClearancePx = 180f,
            fallbackControlsLiftPx = 130f,
            skipVisible = true,
            skipClearancePx = 80f,
            postPlayVisible = true,
            postPlayClearancePx = 140f,
            seekVisible = true,
            seekClearancePx = 60f,
        )

        assertEquals(140f, lift)
    }
}
