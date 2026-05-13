package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StillWatchingGatingTest {

    @Test
    fun `gating returns false when still-watching setting disabled`() {
        val result = shouldEnterStillWatchingPrompt(
            stillWatchingEnabled = false,
            autoPlayNextEpisodeEnabled = true,
            nextEpisodeHasAired = true,
            consecutiveAutoPlayCount = 5,
            threshold = 3,
        )
        assertFalse(result)
    }

    @Test
    fun `gating returns false when auto-play next episode disabled`() {
        val result = shouldEnterStillWatchingPrompt(
            stillWatchingEnabled = true,
            autoPlayNextEpisodeEnabled = false,
            nextEpisodeHasAired = true,
            consecutiveAutoPlayCount = 5,
            threshold = 3,
        )
        assertFalse(result)
    }

    @Test
    fun `gating returns false when next episode has not aired`() {
        val result = shouldEnterStillWatchingPrompt(
            stillWatchingEnabled = true,
            autoPlayNextEpisodeEnabled = true,
            nextEpisodeHasAired = false,
            consecutiveAutoPlayCount = 5,
            threshold = 3,
        )
        assertFalse(result)
    }

    @Test
    fun `gating returns false when consecutive count below threshold`() {
        val result = shouldEnterStillWatchingPrompt(
            stillWatchingEnabled = true,
            autoPlayNextEpisodeEnabled = true,
            nextEpisodeHasAired = true,
            consecutiveAutoPlayCount = 2,
            threshold = 3,
        )
        assertFalse(result)
    }

    @Test
    fun `gating returns true when all conditions met`() {
        val result = shouldEnterStillWatchingPrompt(
            stillWatchingEnabled = true,
            autoPlayNextEpisodeEnabled = true,
            nextEpisodeHasAired = true,
            consecutiveAutoPlayCount = 3,
            threshold = 3,
        )
        assertTrue(result)
    }

    @Test
    fun `pendingExitReason defaults to null and copies through`() {
        assertNull(PlayerUiState().pendingExitReason)
        val after = PlayerUiState().copy(pendingExitReason = PlayerExitReason.StillWatchingPrompt)
        assertEquals(PlayerExitReason.StillWatchingPrompt, after.pendingExitReason)
    }

    @Test
    fun `postPlayMode StillWatching carries the expected episode`() {
        val nextInfo = NextEpisodeInfo(
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
        assertNull(PlayerUiState().postPlayMode)
        val state = PlayerUiState(
            postPlayMode = PostPlayMode.StillWatching(nextEpisode = nextInfo)
        )
        val mode = state.postPlayMode
        assertTrue(mode is PostPlayMode.StillWatching)
        mode as PostPlayMode.StillWatching
        assertEquals(nextInfo, mode.nextEpisode)
    }
}
