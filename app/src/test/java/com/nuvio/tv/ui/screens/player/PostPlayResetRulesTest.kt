package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPlayResetRulesTest {

    private val nextInfo = NextEpisodeInfo(
        videoId = "v1",
        season = 1,
        episode = 2,
        title = "Ep",
        thumbnail = null,
        overview = null,
        released = null,
        hasAired = true,
        unairedMessage = null,
    )

    @Test
    fun `ended playback does not reset while next episode autoplay search is active`() {
        val state = PlayerUiState(
            postPlayMode = PostPlayMode.AutoPlay(
                nextEpisode = nextInfo,
                searching = true,
            )
        )

        assertFalse(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = state,
                hasInFlightNextEpisodeAutoPlay = true
            )
        )
    }

    @Test
    fun `ended playback does not reset during next episode autoplay countdown`() {
        val state = PlayerUiState(
            postPlayMode = PostPlayMode.AutoPlay(
                nextEpisode = nextInfo,
                countdownSec = 2,
            )
        )

        assertFalse(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = state,
                hasInFlightNextEpisodeAutoPlay = true
            )
        )
    }

    @Test
    fun `ended playback resets when no post play flow is active`() {
        assertTrue(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = PlayerUiState(),
                hasInFlightNextEpisodeAutoPlay = false
            )
        )
    }

    @Test
    fun `ended playback does not reset while still watching prompt is active`() {
        assertFalse(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = PlayerUiState(
                    postPlayMode = PostPlayMode.StillWatching(nextEpisode = nextInfo)
                ),
                hasInFlightNextEpisodeAutoPlay = false
            )
        )
    }

    @Test
    fun `ended signal is ignored while the next stream is still loading`() {
        // Stale completion tick from the previous episode while an auto-play
        // switch is still loading: acting on it would skip an extra episode.
        assertFalse(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = PlayerUiState(postPlayDismissedForCurrentEpisode = true),
                hasInFlightNextEpisodeAutoPlay = false,
                hasObservedFreshPlaybackForCurrentStream = false
            )
        )
    }

    @Test
    fun `ended playback resets once the current stream has actually played`() {
        assertTrue(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = PlayerUiState(postPlayDismissedForCurrentEpisode = true),
                hasInFlightNextEpisodeAutoPlay = false,
                hasObservedFreshPlaybackForCurrentStream = true
            )
        )
    }

    @Test
    fun `position at the end of the timeline is treated as playback end`() {
        assertTrue(isPositionAtEndOfPlayback(positionMs = 1_200_000L, durationMs = 1_200_000L))
        assertTrue(isPositionAtEndOfPlayback(positionMs = 1_199_600L, durationMs = 1_200_000L))
    }

    @Test
    fun `position before the end and unknown duration are not playback end`() {
        assertFalse(isPositionAtEndOfPlayback(positionMs = 1_198_000L, durationMs = 1_200_000L))
        assertFalse(isPositionAtEndOfPlayback(positionMs = 0L, durationMs = 1_200_000L))
        // Live / not-yet-known duration must never look like the end of playback.
        assertFalse(isPositionAtEndOfPlayback(positionMs = 5_000L, durationMs = 0L))
    }

    @Test
    fun `unknown duration does not prove playback belongs to the new stream`() {
        assertFalse(isFreshPlaybackSample(positionMs = 0L, durationMs = 0L))
        assertFalse(isFreshPlaybackSample(positionMs = 5_000L, durationMs = 0L))
        assertFalse(isFreshPlaybackSample(positionMs = 0L, durationMs = -1L))
    }
}
