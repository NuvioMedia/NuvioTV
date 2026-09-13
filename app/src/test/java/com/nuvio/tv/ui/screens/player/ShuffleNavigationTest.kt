package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleNavigationTest {
    @Test
    fun `stream and player routes carry active shuffle session`() {
        val streamRoute = Screen.Stream.createRoute(
            videoId = "series:1:1",
            contentType = "series",
            title = "Series",
            shuffleSession = true
        )
        val playerRoute = Screen.Player.createRoute(
            streamUrl = "https://example.com/video.mkv",
            title = "Series",
            shuffleSession = true
        )

        assertTrue(streamRoute.contains("shuffleSession=true"))
        assertTrue(playerRoute.contains("shuffleSession=true"))
    }

    @Test
    fun `player navigation defaults ordinary playback to sequential`() {
        val ordinary = PlayerNavigationArgs.from(
            SavedStateHandle(mapOf("streamUrl" to "stream", "title" to "Series"))
        )
        val shuffled = PlayerNavigationArgs.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "stream",
                    "title" to "Series",
                    "shuffleSession" to "true"
                )
            )
        )

        assertFalse(ordinary.shuffleSession)
        assertTrue(shuffled.shuffleSession)
    }
}
