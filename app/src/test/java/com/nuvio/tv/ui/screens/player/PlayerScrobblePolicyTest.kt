package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrobblePolicyTest {
    @Test
    fun `ready non-playing state emits pause`() {
        assertEquals(
            TrackingScrobbleAction.PAUSE,
            trackingActionForNonPlayingState(Player.STATE_READY)
        )
    }

    @Test
    fun `ended and idle states emit stop`() {
        assertEquals(
            TrackingScrobbleAction.STOP,
            trackingActionForNonPlayingState(Player.STATE_ENDED)
        )
        assertEquals(
            TrackingScrobbleAction.STOP,
            trackingActionForNonPlayingState(Player.STATE_IDLE)
        )
    }

    @Test
    fun `buffering state emits no terminal scrobble`() {
        assertNull(trackingActionForNonPlayingState(Player.STATE_BUFFERING))
    }

    @Test
    fun `active pause remains eligible above watched threshold`() {
        assertTrue(
            shouldSendPauseScrobble(
                hasActiveScrobble = true,
                hasStartAboveCompletionBar = false,
                progressPercent = 90f
            )
        )
    }

    @Test
    fun `pause requires a session or a start above the bar and accepts early progress`() {
        assertFalse(
            shouldSendPauseScrobble(
                hasActiveScrobble = false,
                hasStartAboveCompletionBar = false,
                progressPercent = 45f
            )
        )
        assertTrue(
            shouldSendPauseScrobble(
                hasActiveScrobble = true,
                hasStartAboveCompletionBar = false,
                progressPercent = 0f
            )
        )
        assertTrue(
            shouldSendPauseScrobble(
                hasActiveScrobble = true,
                hasStartAboveCompletionBar = false,
                progressPercent = 0.5f
            )
        )
    }

    @Test
    fun `a start above the bar lets its playback report a pause anywhere`() {
        assertTrue(
            shouldSendPauseScrobble(
                hasActiveScrobble = false,
                hasStartAboveCompletionBar = true,
                progressPercent = 92f
            )
        )
        assertTrue(
            shouldSendPauseScrobble(
                hasActiveScrobble = false,
                hasStartAboveCompletionBar = true,
                progressPercent = 40f
            )
        )
    }

    @Test
    fun `stop closes active sessions below one percent`() {
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = true,
                hasStartAboveCompletionBar = false,
                progressPercent = 0f
            )
        )
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = true,
                hasStartAboveCompletionBar = false,
                progressPercent = 0.5f
            )
        )
        assertFalse(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                hasStartAboveCompletionBar = false,
                progressPercent = 0.5f
            )
        )
    }

    @Test
    fun `completion stop remains eligible without an active session`() {
        assertFalse(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                hasStartAboveCompletionBar = false,
                progressPercent = 79.99f
            )
        )
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                hasStartAboveCompletionBar = false,
                progressPercent = 80f
            )
        )
    }

    @Test
    fun `stop of a playback a start above the bar reported is sent from anywhere`() {
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                hasStartAboveCompletionBar = true,
                progressPercent = 40f
            )
        )
        assertTrue(
            shouldSendStopScrobble(
                hasActiveScrobble = false,
                hasStartAboveCompletionBar = true,
                progressPercent = 95f
            )
        )
    }

    @Test
    fun `a start above the completion bar is sent once, and only above the bar`() {
        assertFalse(shouldSendStartAboveCompletionBar(hasSentAboveCompletionBar = false, progressPercent = 79.9f))
        assertTrue(shouldSendStartAboveCompletionBar(hasSentAboveCompletionBar = false, progressPercent = 80f))
        assertTrue(shouldSendStartAboveCompletionBar(hasSentAboveCompletionBar = false, progressPercent = 95f))
        // Sent once per item: pausing and resuming again does not send another one.
        assertFalse(shouldSendStartAboveCompletionBar(hasSentAboveCompletionBar = true, progressPercent = 95f))
    }
}
