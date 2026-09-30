package com.nuvio.tv.data.mdblist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbListProgressProjectionTest {
    @Test
    fun `resume uses actual duration instead of assuming catalog runtime matches the stream`() {
        val playback = mdbListTestPlayback(40f).copy(runtimeMinutes = 100)
        val progress = projection(playback = listOf(playback)).progress.single()
        assertEquals(0L, progress.position)
        assertEquals(6_000_000L, progress.duration)
        assertEquals(1_920_000L, progress.resolveResumePosition(4_800_000L))
        assertEquals("mdblist", progress.trackingProviderId)
    }

    @Test
    fun `unknown runtime retains percentage progress and resume remains available`() {
        val progress = projection(playback = listOf(mdbListTestPlayback(30f))).progress.single()
        assertEquals(0L, progress.duration)
        assertTrue(progress.isInProgress())
        assertEquals(1_080_000L, progress.resolveResumePosition(3_600_000L))
    }

    @Test
    fun `eighty percent is completed and never projected as paused progress`() {
        for (percent in listOf(0f, 1.99f, 2f, 79.99f, 80f, 99f, 100f)) {
            val projection = projection(playback = listOf(mdbListTestPlayback(percent)))
            assertEquals(percent < 80f, projection.progress.isNotEmpty())
            if (percent < 80f) assertEquals(percent >= 2f, projection.progress.single().isInProgress())
        }
        val progress = projection(playback = listOf(mdbListTestPlayback(79f))).progress.single().copy(progressPercent = 80f)
        assertTrue(progress.isCompleted())
        assertFalse(progress.isInProgress())
    }

    @Test
    fun `completed history suppresses stale sessions but allows a later rewatch`() {
        val watched = listOf(mdbListTestMovie())
        val stale = projection(watched, listOf(mdbListTestPlayback(timestamp = "2026-09-05T00:00:00Z")))
        val rewatch = projection(watched, listOf(mdbListTestPlayback(timestamp = "2026-09-06T00:10:00Z")))
        assertTrue(stale.progress.isEmpty())
        assertEquals(1, rewatch.progress.size)
        assertTrue(rewatch.isWatched("tt1", null, null))
    }

    @Test
    fun `exact episode history is available through every parent alias`() {
        val projection = projection(watched = listOf(mdbListTestEpisode(2, 3)))
        for (id in listOf("tt1", "imdb:tt1", "tmdb:1")) {
            assertTrue(projection.isWatched(id, 2, 3))
            assertFalse(projection.isWatched(id, 2, 4))
            assertEquals(setOf(2 to 3), projection.watchedShowEpisodes[id])
            assertTrue(projection.showIdSiblings[id].orEmpty().contains("tmdb:1"))
        }
        assertEquals(1, projection.watchedItems.size)
    }

    @Test
    fun `next up respects furthest episode and most recently watched preferences`() {
        val projection = projection(watched = listOf(
            mdbListTestEpisode(1, 2, "2026-09-06T00:00:00Z"),
            mdbListTestEpisode(3, 7, "2026-09-05T00:00:00Z")
        ))
        assertEquals(3, projection.nextUp(true).single().season)
        assertEquals(7, projection.nextUp(true).single().episode)
        assertEquals(1, projection.nextUp(false).single().season)
        assertEquals(2, projection.nextUp(false).single().episode)
    }

    @Test
    fun `dropped show suppresses progress and next up through aliases while keeping history`() {
        val projection = projection(
            watched = listOf(mdbListTestEpisode()),
            playback = listOf(mdbListTestPlayback(timestamp = "2026-09-06T01:00:00Z").copy(type = MdbListItemType.EPISODE, season = 1, episode = 2)),
            dropped = listOf(MdbListDroppedRecord(MdbListIds(tmdb = 1)))
        )
        assertTrue(projection.isHidden("tt1"))
        assertTrue(projection.progress.isEmpty())
        assertTrue(projection.nextUp(true).isEmpty())
        assertTrue(projection.isWatched("tt1", 1, 1))
    }

    @Test
    fun `dropped seasons exclude next up candidates without inventing watched episodes`() {
        val projection = projection(
            watched = listOf(mdbListTestEpisode()),
            dropped = listOf(MdbListDroppedRecord(MdbListIds(tmdb = 1), 2))
        )
        assertFalse(projection.isHidden("tt1"))
        assertEquals(setOf(2), projection.nextUp(true).single().excludedNextUpSeasons)
        assertFalse(projection.isWatched("tt1", 2, 1))
        assertEquals(setOf(1 to 1), projection.watchedShowEpisodes["tt1"])
    }

    @Test
    fun `show and season markers never fabricate exact episodes or completed badges`() {
        val show = mdbListTestMovie().copy(type = MdbListItemType.SHOW)
        val season = show.copy(type = MdbListItemType.SEASON, season = 1)
        val projection = projection(watched = listOf(show, season))
        assertTrue(projection.watchedItems.isEmpty())
        assertTrue(projection.watchedMovieIds.isEmpty())
        assertTrue(projection.watchedShowEpisodes.isEmpty())
        assertFalse(projection.isWatched("tt1", 1, 1))
    }

    @Test
    fun `specials remain in history without becoming next up seeds`() {
        val projection = projection(watched = listOf(mdbListTestEpisode(0, 3)))
        assertTrue(projection.nextUp(true).isEmpty())
        assertTrue(projection.isWatched("tt1", 0, 3))
    }

    @Test
    fun `multiple local sessions without server IDs are not collapsed together`() {
        val first = mdbListTestPlayback().copy(id = null)
        val second = first.copy(media = mdbListTestMovie(2).media)
        val normalized = MdbListSyncSnapshot(42, playback = listOf(first, second)).normalizeMedia()
        assertEquals(2, normalized.playback.size)
    }

    private fun projection(
        watched: List<MdbListWatchedRecord> = emptyList(),
        playback: List<MdbListPlayback> = emptyList(),
        dropped: List<MdbListDroppedRecord> = emptyList()
    ) = MdbListProgressProjection(MdbListSyncSnapshot(42, watched, playback, dropped).normalizeMedia())
}
