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
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 90f))
    }

    @Test
    fun `pause requires active scrobble and accepts early progress`() {
        assertFalse(shouldSendPauseScrobble(hasActiveScrobble = false, progressPercent = 45f))
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 0f))
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 0.5f))
    }

    @Test
    fun `stop closes active sessions below one percent`() {
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = true, progressPercent = 0f))
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = true, progressPercent = 0.5f))
        assertFalse(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 0.5f))
    }

    @Test
    fun `completion stop remains eligible without an active session`() {
        assertFalse(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 79.99f))
        assertTrue(shouldSendStopScrobble(hasActiveScrobble = false, progressPercent = 80f))
    }
}
