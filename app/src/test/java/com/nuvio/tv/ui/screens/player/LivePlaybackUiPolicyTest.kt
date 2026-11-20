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

    private fun growth(vararg readings: Pair<Long, Boolean>, mediaKey: String = "a"): LivePlaybackUiPolicy.DurationGrowth =
        readings.fold(LivePlaybackUiPolicy.DurationGrowth()) { state, (durationMs, fromRequestedMedia) ->
            LivePlaybackUiPolicy.nextDurationGrowth(state, mediaKey, durationMs, fromRequestedMedia)
        }

    @Test
    fun `fixed vod duration does not count as growing`() {
        assertFalse(growth(1_320_000L to true, 1_320_000L to true, 1_325_000L to true).grew)
    }

    @Test
    fun `growing mpv duration marks live`() {
        assertTrue(growth(8_000L to true, 18_000L to true, 32_000L to true).grew)
    }

    @Test
    fun `readings before the requested media are skipped`() {
        // The previous file's 20 min is reported before MPV switches to a 45 min episode
        assertFalse(growth(1_200_000L to false, 2_700_000L to true, 2_700_000L to true).grew)
    }

    @Test
    fun `a new media key starts over`() {
        val live = growth(8_000L to true, 46_000L to true)
        assertTrue(live.grew)
        val next = LivePlaybackUiPolicy.nextDurationGrowth(live, "b", 2_700_000L, true)
        assertFalse(next.grew)
        assertEquals(2_700_000L, next.firstDurationMs)
    }
}
