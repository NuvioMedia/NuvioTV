package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.EpisodeOptionsOverlayStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeOptionsOverlayTest {
    @Test
    fun `none style does not show episode artwork`() {
        assertFalse(shouldShowEpisodeOverlayBackdrop(EpisodeOptionsOverlayStyle.NONE))
        assertFalse(
            shouldBlurEpisodeOverlayBackdrop(
                style = EpisodeOptionsOverlayStyle.NONE,
                blurUnwatchedEpisodes = true,
                isWatched = false
            )
        )
    }

    @Test
    fun `artwork style uses sharp artwork by default`() {
        assertTrue(shouldShowEpisodeOverlayBackdrop(EpisodeOptionsOverlayStyle.ARTWORK))
        assertFalse(
            shouldBlurEpisodeOverlayBackdrop(
                style = EpisodeOptionsOverlayStyle.ARTWORK,
                blurUnwatchedEpisodes = false,
                isWatched = false
            )
        )
    }

    @Test
    fun `artwork style respects unwatched blur preference`() {
        assertTrue(
            shouldBlurEpisodeOverlayBackdrop(
                style = EpisodeOptionsOverlayStyle.ARTWORK,
                blurUnwatchedEpisodes = true,
                isWatched = false
            )
        )
        assertFalse(
            shouldBlurEpisodeOverlayBackdrop(
                style = EpisodeOptionsOverlayStyle.ARTWORK,
                blurUnwatchedEpisodes = true,
                isWatched = true
            )
        )
    }

    @Test
    fun `blur style always blurs episode artwork`() {
        assertTrue(shouldShowEpisodeOverlayBackdrop(EpisodeOptionsOverlayStyle.BLUR))
        assertTrue(
            shouldBlurEpisodeOverlayBackdrop(
                style = EpisodeOptionsOverlayStyle.BLUR,
                blurUnwatchedEpisodes = false,
                isWatched = true
            )
        )
    }
}
