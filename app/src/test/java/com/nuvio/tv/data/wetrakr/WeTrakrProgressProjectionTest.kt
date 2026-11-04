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
    fun `the watched setting decides which paused titles stay in continue watching`() {
        val snapshot = WeTrakrSyncSnapshot(
            1,
            playback = listOf(
                WeTrakrPlayback(WeTrakrItemType.MOVIE, weTrakrMovie("tt1"), 75f, "2026-09-20T10:00:00Z", runtimeSeconds = 6_000),
                WeTrakrPlayback(WeTrakrItemType.MOVIE, weTrakrMovie("tt2"), 92f, "2026-09-20T10:00:00Z", runtimeSeconds = 6_000)
            )
        )

        assertEquals(listOf("tt1"), WeTrakrProgressProjection(snapshot).progress.map { it.contentId })
        assertTrue(WeTrakrProgressProjection(snapshot, watchedPercent = 70).progress.isEmpty())
        assertEquals(
            listOf("tt2", "tt1"),
            WeTrakrProgressProjection(snapshot, watchedPercent = 95).progress.map { it.contentId }.sorted().reversed()
        )
        assertEquals(50, weTrakrWatchedPercent(10))
        assertEquals(100, weTrakrWatchedPercent(150))
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

    @Test
    fun `seeds without a title leave the name blank for the catalog to fill`() {
        val untitled = WeTrakrMedia(WeTrakrIds(imdb = "tt13649510"))
        val projection = WeTrakrProgressProjection(
            WeTrakrSyncSnapshot(
                1,
                watched = listOf(WeTrakrWatchedRecord(WeTrakrItemType.EPISODE, untitled, "2026-09-21T10:00:00Z", 1, 1))
            )
        )

        assertEquals("", projection.nextUp(preferFurthest = true).single().name)
    }
}
