package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProfileRouteTest {
    @Test
    fun `stream route carries playback profile without changing existing flags`() {
        val route = Screen.Stream.createRoute(
            videoId = "video",
            contentType = "series",
            title = "Title",
            startFromBeginning = true,
            returnToDetailOnBack = true,
            profileId = 7
        )

        assertTrue(route.contains("profileId=7"))
        assertTrue(route.contains("startFromBeginning=true"))
        assertTrue(route.contains("returnToDetailOnBack=true"))
    }

    @Test
    fun `player route carries playback profile without changing launch timing`() {
        val route = Screen.Player.createRoute(
            streamUrl = "https://example.com/video.mkv",
            title = "Title",
            launchStartedAtMs = 123L,
            profileId = 7
        )

        assertTrue(route.contains("profileId=7"))
        assertTrue(route.contains("launchStartedAtMs=123"))
    }

    @Test
    fun `player navigation args parse explicit profile`() {
        val args = PlayerNavigationArgs.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "stream",
                    "title" to "Title",
                    "profileId" to "7"
                )
            )
        )

        assertEquals(7, args.profileId)
    }

    @Test
    fun `legacy player route leaves profile unresolved for active-profile fallback`() {
        val args = PlayerNavigationArgs.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "stream",
                    "title" to "Title"
                )
            )
        )

        assertNull(args.profileId)
    }
}
