package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBackPressTest {

    @Test
    fun `binge group reuse skip next then back does not open next episode streams`() {
        val autoPlay = playerBackTreatsSkipAsAutoPlay(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            preferBingeGroupForNextEpisode = true
        )

        assertTrue(autoPlay)
        assertFalse(
            playerBackOpensCurrentEpisodeStreams(
                episodeChangedInPlace = true,
                autoPlayEnabled = autoPlay
            )
        )
    }

    @Test
    fun `strict manual skip next still opens current episode streams`() {
        val autoPlay = playerBackTreatsSkipAsAutoPlay(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            preferBingeGroupForNextEpisode = false
        )

        assertFalse(autoPlay)
        assertTrue(
            playerBackOpensCurrentEpisodeStreams(
                episodeChangedInPlace = true,
                autoPlayEnabled = autoPlay
            )
        )
    }

    @Test
    fun `first stream skip next does not open the stream screen`() {
        val autoPlay = playerBackTreatsSkipAsAutoPlay(
            streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            preferBingeGroupForNextEpisode = false
        )

        assertTrue(autoPlay)
        assertFalse(
            playerBackOpensCurrentEpisodeStreams(
                episodeChangedInPlace = true,
                autoPlayEnabled = autoPlay
            )
        )
    }

    @Test
    fun `same episode back does not open a different episode stream screen`() {
        val autoPlay = playerBackTreatsSkipAsAutoPlay(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            preferBingeGroupForNextEpisode = false
        )

        assertFalse(
            playerBackOpensCurrentEpisodeStreams(
                episodeChangedInPlace = false,
                autoPlayEnabled = autoPlay
            )
        )
    }
}
