package com.nuvio.tv.data.wetrakr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrProgressProjectionTest {
    private val show = WeTrakrMedia(WeTrakrIds(imdb = "tt0903747", tmdb = 1396), title = "Breaking Bad")

    @Test
    fun `paused titles under the threshold become continue watching`() {
        val projection = WeTrakrProgressProjection(
            WeTrakrSyncSnapshot(
                1,
                playback = listOf(
                    WeTrakrPlayback(WeTrakrItemType.MOVIE, weTrakrMovie("tt1"), 40f, "2026-09-20T10:00:00Z", runtimeSeconds = 6_000),
                    WeTrakrPlayback(WeTrakrItemType.MOVIE, weTrakrMovie("tt2"), 85f, "2026-09-20T10:00:00Z")
                )
            )
        )

        val item = projection.progress.single()
        assertEquals("tt1", item.contentId)
        assertEquals(6_000_000L, item.duration)
        assertEquals(2_400_000L, item.position)
        assertEquals("wetrakr", item.trackingProviderId)
    }

    @Test
    fun `a play logged after the pause hides the paused session`() {
        val projection = WeTrakrProgressProjection(
            WeTrakrSyncSnapshot(
                1,
                watched = listOf(WeTrakrWatchedRecord(WeTrakrItemType.MOVIE, weTrakrMovie("tt1"), "2026-09-21T10:00:00Z")),
                playback = listOf(WeTrakrPlayback(WeTrakrItemType.MOVIE, weTrakrMovie("tt1"), 40f, "2026-09-20T10:00:00Z"))
            )
        )

        assertTrue(projection.progress.isEmpty())
        assertTrue(projection.isWatched("tt1", null, null))
    }

    @Test
    fun `watched episodes answer under every alias of the show`() {
        val projection = WeTrakrProgressProjection(
            WeTrakrSyncSnapshot(
                1,
                watched = listOf(WeTrakrWatchedRecord(WeTrakrItemType.EPISODE, show, "2026-09-21T10:00:00Z", 1, 2))
            )
        )

        assertTrue(projection.isWatched("tt0903747", 1, 2))
        assertTrue(projection.isWatched("tmdb:1396", 1, 2))
        assertFalse(projection.isWatched("tt0903747", 1, 3))
        assertEquals(1, projection.nextUp(preferFurthest = true).size)
    }
}
