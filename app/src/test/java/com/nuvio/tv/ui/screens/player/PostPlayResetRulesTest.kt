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

    private fun autoPlayOnEnd(
        state: PlayerUiState,
        enabled: Boolean = true,
        hasVideo: Boolean = true
    ) = shouldAutoPlayNextEpisodeOnPlaybackEnded(
        state = state.copy(nextEpisode = state.nextEpisode ?: nextInfo),
        autoPlayNextEpisodeEnabled = enabled,
        hasNextEpisodeVideo = hasVideo
    )

    @Test
    fun `ended playback auto plays when the card is still waiting for the auto play point`() {
        assertTrue(autoPlayOnEnd(PlayerUiState(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = nextInfo))))
    }

    @Test
    fun `ended playback auto plays when no card is shown or it was dismissed`() {
        assertTrue(autoPlayOnEnd(PlayerUiState()))
        assertTrue(
            autoPlayOnEnd(
                PlayerUiState(
                    postPlayMode = PostPlayMode.AutoPlay(nextEpisode = nextInfo),
                    postPlayDismissedForCurrentEpisode = true
                )
            )
        )
    }

    @Test
    fun `ended playback does not auto play when auto play is off or the episode is unaired`() {
        val waiting = PlayerUiState(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = nextInfo))
        assertFalse(autoPlayOnEnd(waiting, enabled = false))
        assertFalse(autoPlayOnEnd(waiting, hasVideo = false))
        assertFalse(autoPlayOnEnd(PlayerUiState(nextEpisode = nextInfo.copy(hasAired = false))))
    }

    @Test
    fun `ended playback does not auto play over the still watching prompt`() {
        assertFalse(autoPlayOnEnd(PlayerUiState(postPlayMode = PostPlayMode.StillWatching(nextEpisode = nextInfo))))
    }

    @Test
    fun `a rewind hides a card that is waiting for the auto play point`() {
        val state = PlayerUiState(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = nextInfo))
        assertTrue(shouldHideWaitingNextEpisodeCard(state, awayFromEnd = true))
        assertFalse(shouldHideWaitingNextEpisodeCard(state, awayFromEnd = false))
    }

    @Test
    fun `a rewind does not hide a searching, counting down or still watching card`() {
        val modes = listOf(
            PostPlayMode.AutoPlay(nextEpisode = nextInfo, searching = true),
            PostPlayMode.AutoPlay(nextEpisode = nextInfo, countdownSec = 3),
            PostPlayMode.StillWatching(nextEpisode = nextInfo),
            null,
        )
        for (mode in modes) {
            assertFalse(shouldHideWaitingNextEpisodeCard(PlayerUiState(postPlayMode = mode), awayFromEnd = true))
        }
    }
}
