package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContinueWatchingClickAction
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingClickActionTest {
    private val inProgress = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = "tt1",
            contentType = "series",
            name = "Show",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt1:1:2",
            season = 1,
            episode = 2,
            episodeTitle = null,
            position = 1L,
            duration = 3_000L,
            lastWatched = 1L
        )
    )

    private fun nextUp(hasAired: Boolean) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = "tt1",
            contentType = "series",
            name = "Show",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt1:1:3",
            season = 1,
            episode = 3,
            episodeTitle = null,
            thumbnail = null,
            hasAired = hasAired,
            lastWatched = 1L,
            sortTimestamp = 1L
        )
    )

    @Test
    fun `stream never opens details`() {
        for (item in listOf(inProgress, nextUp(hasAired = true), nextUp(hasAired = false))) {
            assertFalse(ContinueWatchingClickAction.STREAM.opensDetails(item))
        }
    }

    @Test
    fun `details always opens details`() {
        for (item in listOf(inProgress, nextUp(hasAired = true), nextUp(hasAired = false))) {
            assertTrue(ContinueWatchingClickAction.DETAILS.opensDetails(item))
        }
    }

    @Test
    fun `details for upcoming opens details only for unaired episodes`() {
        assertTrue(ContinueWatchingClickAction.DETAILS_FOR_UPCOMING.opensDetails(nextUp(hasAired = false)))
        assertFalse(ContinueWatchingClickAction.DETAILS_FOR_UPCOMING.opensDetails(nextUp(hasAired = true)))
        assertFalse(ContinueWatchingClickAction.DETAILS_FOR_UPCOMING.opensDetails(inProgress))
    }
}
