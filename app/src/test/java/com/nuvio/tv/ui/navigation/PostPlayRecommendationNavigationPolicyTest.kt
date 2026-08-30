package com.nuvio.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPlayRecommendationNavigationPolicyTest {
    @Test
    fun `manual playback removes stream and player`() {
        assertEquals(
            Screen.Stream.route,
            postPlayRecommendationPopUpRoute(Screen.Stream.route)
        )
    }

    @Test
    fun `autoplay playback removes current player`() {
        assertEquals(
            Screen.Player.route,
            postPlayRecommendationPopUpRoute(Screen.Detail.route)
        )
        assertEquals(
            Screen.Player.route,
            postPlayRecommendationPopUpRoute(null)
        )
    }

    @Test
    fun `detail route carries one shot manual play request`() {
        val route = Screen.Detail.createRoute(
            itemId = "tmdb:42",
            itemType = "series",
            playOnLoad = true,
            manualSelection = true
        )

        assertTrue(route.contains("playOnLoad=true"))
        assertTrue(route.contains("manualSelection=true"))
    }
}
