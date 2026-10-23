package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `lift rises right away even while a line is showing`() {
        assertEquals(
            SubtitleLiftDecision(200f, null),
            nextSubtitleLift(200f, 0f, cueVersion = 3, cueVisible = true, applyImmediately = false, pendingDropCueVersion = null, dropTimedOut = false)
        )
    }

    @Test
    fun `lift drops only after the shown line changes`() {
        val waiting = nextSubtitleLift(0f, 200f, cueVersion = 3, cueVisible = true, applyImmediately = false, pendingDropCueVersion = null, dropTimedOut = false)
        assertEquals(SubtitleLiftDecision(200f, 3L), waiting)
        assertEquals(waiting, nextSubtitleLift(0f, 200f, 3, true, false, waiting.pendingDropCueVersion, false))
        assertEquals(SubtitleLiftDecision(0f, null), nextSubtitleLift(0f, 200f, 4, true, false, waiting.pendingDropCueVersion, false))
    }

    @Test
    fun `lift drops right away with no line, when paused or previewing, or after the timeout`() {
        assertEquals(SubtitleLiftDecision(0f, null), nextSubtitleLift(0f, 200f, 3, cueVisible = false, applyImmediately = false, pendingDropCueVersion = null, dropTimedOut = false))
        assertEquals(SubtitleLiftDecision(0f, null), nextSubtitleLift(0f, 200f, 3, cueVisible = true, applyImmediately = true, pendingDropCueVersion = null, dropTimedOut = false))
        assertEquals(SubtitleLiftDecision(0f, null), nextSubtitleLift(0f, 200f, 3, cueVisible = true, applyImmediately = false, pendingDropCueVersion = 3L, dropTimedOut = true))
    }

    private val metrics = SubtitleTextMetrics(
        textSizePx = 40f,
        bold = false,
        scaleX = 1f,
        innerPaddingPx = 5f,
        wrapWidthPx = 1800f,
        centerXPx = 960f
    )

    @Test
    fun `block width uses the widest line plus padding and scale`() {
        val width = subtitleBlockWidthPx("short\na much longer line", metrics.copy(scaleX = 1.5f)) { it.length * 10f }
        assertEquals((180f + 10f) * 1.5f, width)
    }

    @Test
    fun `block width caps a wrapping line at the wrap width`() {
        assertEquals(1810f, subtitleBlockWidthPx("x".repeat(500), metrics) { it.length * 10f })
    }

    @Test
    fun `subtitle clears the title only when its left edge stays right of the title`() {
        assertTrue(subtitleClearsTitle(600f, centerXPx = 960f, titleRightPx = 600f, marginPx = 16f))
        assertFalse(subtitleClearsTitle(800f, centerXPx = 960f, titleRightPx = 600f, marginPx = 16f))
        assertFalse(subtitleClearsTitle(null, centerXPx = 960f, titleRightPx = 600f, marginPx = 16f))
        assertTrue(subtitleClearsTitle(1800f, centerXPx = 960f, titleRightPx = 0f, marginPx = 16f))
    }

    @Test
    fun `progress bar clearance is used only when the subtitle clears the title`() {
        assertEquals(120f, effectiveSubtitleControlsClearancePx(260f, 120f, clearsTitle = true))
        assertEquals(260f, effectiveSubtitleControlsClearancePx(260f, 120f, clearsTitle = false))
        assertEquals(260f, effectiveSubtitleControlsClearancePx(260f, 0f, clearsTitle = true))
    }
}
