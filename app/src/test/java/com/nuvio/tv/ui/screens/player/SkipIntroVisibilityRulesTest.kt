package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipIntroVisibilityRulesTest {

    private val intro = SkipInterval(
        startTime = 10.0,
        endTime = 70.0,
        type = "intro",
        provider = "introdb",
    )

    private val intervals = listOf(intro)

    // ── Interval matching (ViewModel) ──────────────────────────────────────

    @Test
    fun `interval is inactive before start`() {
        assertNull(nextActiveSkipInterval(intervals, positionMs = 9_999L))
    }

    @Test
    fun `interval is active at start boundary`() {
        assertEquals(intro, nextActiveSkipInterval(intervals, positionMs = 10_000L))
    }

    @Test
    fun `interval stays active through mid segment`() {
        assertEquals(intro, nextActiveSkipInterval(intervals, positionMs = 40_000L))
    }

    @Test
    fun `interval stays active in the final half-second of the segment`() {
        assertNotNull(nextActiveSkipInterval(intervals, positionMs = 69_600L))
        assertEquals(intro, nextActiveSkipInterval(intervals, positionMs = 69_999L))
    }

    @Test
    fun `interval clears exactly at endTime`() {
        assertNull(nextActiveSkipInterval(intervals, positionMs = 70_000L))
        assertNull(nextActiveSkipInterval(intervals, positionMs = 120_000L))
    }

    @Test
    fun `empty intervals always resolve to null`() {
        assertNull(nextActiveSkipInterval(emptyList(), positionMs = 30_000L))
    }

    @Test
    fun `state transition clears previous active when playhead exits`() {
        val whileInside = nextActiveSkipInterval(intervals, positionMs = 30_000L)
        assertEquals(intro, whileInside)

        val afterExit = nextActiveSkipInterval(intervals, positionMs = 70_000L)
        assertNull(
            "activeSkipInterval must become null once playhead leaves the segment",
            afterExit,
        )
    }

    // ── Button visibility (SkipIntroButton) ────────────────────────────────

    @Test
    fun `button visible when interval active and not dismissed or auto-hidden`() {
        assertTrue(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = false,
                controlsVisible = false,
                autoHidden = false,
            )
        )
    }

    @Test
    fun `button auto-hides after timeout while controls are hidden`() {
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = false,
                controlsVisible = false,
                autoHidden = true,
            )
        )
    }

    @Test
    fun `auto-hidden button reappears only while controls are visible`() {
        assertTrue(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = false,
                controlsVisible = true,
                autoHidden = true,
            )
        )
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = false,
                controlsVisible = false,
                autoHidden = true,
            )
        )
    }

    @Test
    fun `manually dismissed button stays hidden until controls are shown`() {
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = true,
                controlsVisible = false,
                autoHidden = false,
            )
        )
        assertTrue(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = true,
                controlsVisible = true,
                autoHidden = false,
            )
        )
    }

    @Test
    fun `no active interval means button is never visible`() {
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = false,
                dismissed = false,
                controlsVisible = true,
                autoHidden = false,
            )
        )
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = false,
                dismissed = false,
                controlsVisible = false,
                autoHidden = false,
            )
        )
    }

    @Test
    fun `after interval clears auto-hidden state cannot keep button on screen`() {
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = false,
                dismissed = false,
                controlsVisible = false,
                autoHidden = false,
            )
        )
    }

    // ── Focus while overlays open (#2874) ─────────────────────────────────

    @Test
    fun `skip intro can accept focus when subtitle overlay is closed`() {
        assertTrue(isSkipIntroCanFocus(subtitleOverlayVisible = false))
    }

    @Test
    fun `skip intro cannot accept focus while subtitle selection overlay is open`() {
        // DPAD_DOWN past the last subtitle card must not escape onto Skip Intro.
        assertFalse(isSkipIntroCanFocus(subtitleOverlayVisible = true))
    }

    // ── Auto-hide countdown ────────────────────────────────────────────────

    @Test
    fun `auto-hide remaining duration is full timeout at progress zero`() {
        assertEquals(
            SKIP_INTRO_AUTO_HIDE_TIMEOUT_MS,
            skipIntroAutoHideRemainingMs(progress = 0f),
        )
    }

    @Test
    fun `auto-hide remaining duration halves at mid progress`() {
        assertEquals(5_000, skipIntroAutoHideRemainingMs(progress = 0.5f))
    }

    @Test
    fun `auto-hide remaining duration collapses to 1ms when progress already complete`() {
        assertEquals(1, skipIntroAutoHideRemainingMs(progress = 1f))
    }
}
