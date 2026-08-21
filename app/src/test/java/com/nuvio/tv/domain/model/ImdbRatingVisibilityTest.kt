package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImdbRatingVisibilityTest {

    @Test
    fun `overall visibility controls home and standard detail ratings`() {
        assertTrue(HomeImdbRatingsVisibility.SHOW_ALL.showRatings)
        assertFalse(HomeImdbRatingsVisibility.HIDE_ALL.showRatings)

        assertTrue(
            HomeImdbRatingsVisibility.SHOW_ALL.showStandardDetailRatings(
                isMdbListActive = false
            )
        )
        assertFalse(
            HomeImdbRatingsVisibility.HIDE_ALL.showStandardDetailRatings(
                isMdbListActive = false
            )
        )
    }

    @Test
    fun `active mdblist takes priority over overall detail visibility`() {
        assertFalse(
            HomeImdbRatingsVisibility.SHOW_ALL.showStandardDetailRatings(
                isMdbListActive = true
            )
        )
        assertFalse(
            HomeImdbRatingsVisibility.HIDE_ALL.showStandardDetailRatings(
                isMdbListActive = true
            )
        )
    }

    @Test
    fun `episode visibility exposes only the three supported behaviours`() {
        assertTrue(DetailImdbRatingsVisibility.SHOW_ALL.showEpisodeRatings)
        assertTrue(DetailImdbRatingsVisibility.HIDE_UNWATCHED_EPISODES.showEpisodeRatings)
        assertFalse(DetailImdbRatingsVisibility.HIDE_EPISODES.showEpisodeRatings)

        assertEquals(
            DetailImdbRatingsVisibility.HIDE_EPISODES,
            DetailImdbRatingsVisibility.HIDE_ALL.asEpisodeVisibility()
        )
    }

    @Test
    fun `detail visibility can hide only unwatched episode ratings`() {
        assertTrue(DetailImdbRatingsVisibility.SHOW_ALL.showEpisodeRating(isWatched = false))
        assertTrue(DetailImdbRatingsVisibility.SHOW_ALL.showEpisodeRating(isWatched = true))

        assertFalse(DetailImdbRatingsVisibility.HIDE_UNWATCHED_EPISODES.showEpisodeRating(isWatched = false))
        assertTrue(DetailImdbRatingsVisibility.HIDE_UNWATCHED_EPISODES.showEpisodeRating(isWatched = true))

        assertFalse(DetailImdbRatingsVisibility.HIDE_EPISODES.showEpisodeRating(isWatched = false))
        assertFalse(DetailImdbRatingsVisibility.HIDE_EPISODES.showEpisodeRating(isWatched = true))

        assertFalse(DetailImdbRatingsVisibility.HIDE_ALL.showEpisodeRating(isWatched = false))
        assertFalse(DetailImdbRatingsVisibility.HIDE_ALL.showEpisodeRating(isWatched = true))
    }
}
