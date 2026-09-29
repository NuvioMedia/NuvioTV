package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackUiPolicyTest {

    @Test
    fun `vod hls is not live without player flag or channel type`() {
        assertFalse(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = false,
                contentType = "movie"
            )
        )
        assertFalse(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = false,
                contentType = "series"
            )
        )
        assertFalse(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = false,
                contentType = "tv"
            )
        )
    }

    @Test
    fun `channel catalog type is live even before player reports`() {
        assertTrue(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = false,
                contentType = "channel"
            )
        )
    }

    @Test
    fun `player live window flag marks live hls regardless of movie type`() {
        assertTrue(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = true,
                contentType = "movie"
            )
        )
    }

    @Test
    fun `live latch sticks after the player reported live once`() {
        val latched = LivePlaybackUiPolicy.nextLiveLatch(
            playerReportsLive = true,
            previouslyLatched = false
        )
        assertTrue(latched)
        assertTrue(
            LivePlaybackUiPolicy.isLivePlayback(
                playerReportsLive = false,
                contentType = "movie",
                latchedLive = latched
            )
        )
    }

    @Test
    fun `watch clock accumulates only while playing live`() {
        val clock = LivePlaybackWatchClock()
        assertEquals(
            0L,
            clock.watchedDurationMs(isLive = true, isPlaying = true, nowElapsedMs = 1_000L)
        )
        assertEquals(
            6_000L,
            clock.watchedDurationMs(isLive = true, isPlaying = true, nowElapsedMs = 7_000L)
        )
        assertEquals(
            6_000L,
            clock.watchedDurationMs(isLive = true, isPlaying = false, nowElapsedMs = 7_000L)
        )
        assertEquals(
            6_000L,
            clock.watchedDurationMs(isLive = true, isPlaying = false, nowElapsedMs = 20_000L)
        )
        assertEquals(
            6_000L,
            clock.watchedDurationMs(isLive = true, isPlaying = true, nowElapsedMs = 22_000L)
        )
        assertEquals(
            8_000L,
            clock.watchedDurationMs(isLive = true, isPlaying = true, nowElapsedMs = 24_000L)
        )
    }

    @Test
    fun `watch clock resets when leaving live`() {
        val clock = LivePlaybackWatchClock()
        clock.watchedDurationMs(isLive = true, isPlaying = true, nowElapsedMs = 0L)
        clock.watchedDurationMs(isLive = true, isPlaying = false, nowElapsedMs = 4_000L)
        assertEquals(
            0L,
            clock.watchedDurationMs(isLive = false, isPlaying = true, nowElapsedMs = 10_000L)
        )
    }
}
