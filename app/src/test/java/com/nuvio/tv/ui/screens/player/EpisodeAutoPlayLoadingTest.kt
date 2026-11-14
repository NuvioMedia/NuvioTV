package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeAutoPlayLoadingTest {
    @Test
    fun overlayRemainsVisibleAsPartialResultsArrive() {
        val searching = PlayerUiState(
            showLoadingOverlay = false,
            showEpisodesPanel = true,
            isEpisodeAutoPlaySearching = true,
            isLoadingEpisodeStreams = true
        )

        assertTrue(searching.showEpisodeAutoPlayLoadingOverlay)
        assertTrue(searching.copy(isLoadingEpisodeStreams = false).showEpisodeAutoPlayLoadingOverlay)
    }

    @Test
    fun overlayRespectsLoadingScreenSetting() {
        val searching = PlayerUiState(
            isEpisodeAutoPlaySearching = true,
            loadingOverlayEnabled = false
        )

        assertFalse(searching.showEpisodeAutoPlayLoadingOverlay)
    }

    @Test
    fun failedAutoplayReturnsToStreamPicker() {
        val searching = PlayerUiState(
            showEpisodesPanel = true,
            showEpisodeStreams = false,
            isEpisodeAutoPlaySearching = true,
            isLoadingEpisodeStreams = true
        )

        val failed = searching.finishEpisodeAutoPlaySearch(showStreamPicker = true)

        assertFalse(failed.showEpisodeAutoPlayLoadingOverlay)
        assertFalse(failed.isLoadingEpisodeStreams)
        assertTrue(failed.showEpisodesPanel)
        assertTrue(failed.showEpisodeStreams)
    }

    @Test
    fun failureDoesNotOpenPickerForManualOrAutomaticNextEpisode() {
        val state = PlayerUiState(showEpisodeStreams = false)

        assertFalse(state.finishEpisodeAutoPlaySearch(showStreamPicker = true).showEpisodeStreams)
    }

    @Test
    fun finishingSearchPreservesAnAlreadyOpenPicker() {
        val state = PlayerUiState(showEpisodeStreams = true, isEpisodeAutoPlaySearching = true)

        val finished = state.finishEpisodeAutoPlaySearch()

        assertFalse(finished.isEpisodeAutoPlaySearching)
        assertTrue(finished.showEpisodeStreams)
    }

    @Test
    fun playbackOverlayCanTakeOverWithoutExposingTheOldEpisode() {
        val searching = PlayerUiState(
            showLoadingOverlay = false,
            isEpisodeAutoPlaySearching = true
        )
        val starting = searching.copy(
            showLoadingOverlay = searching.loadingOverlayEnabled,
            isEpisodeAutoPlaySearching = false
        )

        assertTrue(searching.showEpisodeAutoPlayLoadingOverlay)
        assertFalse(starting.showEpisodeAutoPlayLoadingOverlay)
        assertTrue(starting.showLoadingOverlay)
    }
}
