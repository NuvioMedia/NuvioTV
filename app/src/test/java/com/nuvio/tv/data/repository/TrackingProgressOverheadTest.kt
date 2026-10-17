package com.nuvio.tv.data.repository

import com.nuvio.tv.data.mdblist.MdbListIds
import com.nuvio.tv.data.mdblist.MdbListItemType
import com.nuvio.tv.data.mdblist.MdbListMedia
import com.nuvio.tv.data.mdblist.MdbListPlayback
import com.nuvio.tv.data.mdblist.MdbListProgressProjection
import com.nuvio.tv.data.mdblist.MdbListSyncSnapshot
import com.nuvio.tv.data.mdblist.MdbListWatchedRecord
import com.nuvio.tv.data.mdblist.applyScrobble
import com.nuvio.tv.data.simkl.SimklLibraryEntry
import com.nuvio.tv.data.simkl.SimklMedia
import com.nuvio.tv.data.simkl.SimklMediaType
import com.nuvio.tv.data.simkl.SimklScrobbleOutcome
import com.nuvio.tv.data.simkl.SimklScrobbleResult
import com.nuvio.tv.data.simkl.SimklSnapshotProjection
import com.nuvio.tv.data.simkl.SimklSyncSnapshot
import com.nuvio.tv.data.simkl.applyScrobbleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingProgressOverheadTest {

    @Test
    fun `verify - simkl withUpdatedPlayback reuses projection collections when entries are unchanged`() {
        val testMedia = SimklMedia(title = "Movie 1", ids = mapOf("imdb" to kotlinx.serialization.json.JsonPrimitive("tt001")))
        val historicalEntries = (1..100).map { i ->
            SimklLibraryEntry(
                mediaType = if (i % 2 == 0) SimklMediaType.MOVIES else SimklMediaType.SHOWS,
                movie = if (i % 2 == 0) SimklMedia(title = "Movie $i", ids = mapOf("imdb" to kotlinx.serialization.json.JsonPrimitive("tt$i"))) else null,
                show = if (i % 2 != 0) SimklMedia(title = "Show $i", ids = mapOf("imdb" to kotlinx.serialization.json.JsonPrimitive("tt$i"))) else null
            )
        }
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "watermark-1",
            entries = historicalEntries,
            playback = emptyList()
        )
        val initialProjection = SimklSnapshotProjection.create(snapshot)

        val pauseResult = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.PAUSE,
            playbackId = 999L,
            progress = 35.5,
            mediaType = SimklMediaType.MOVIES,
            media = testMedia,
            episode = null
        )

        val updatedSnapshot = snapshot.applyScrobbleResult(
            result = pauseResult,
            committedAtEpochMs = System.currentTimeMillis()
        )

        val updatedProjection = initialProjection.withUpdatedPlayback(updatedSnapshot)

        // Verify that entries-derived collections are REUSED by reference (zero allocations/recomputation)
        assertTrue(initialProjection.library === updatedProjection.library)
        assertTrue(initialProjection.watched === updatedProjection.watched)
        assertTrue(initialProjection.watchedShowEpisodes === updatedProjection.watchedShowEpisodes)
        assertTrue(initialProjection.showIdSiblings === updatedProjection.showIdSiblings)
        assertEquals(1, updatedProjection.progress.size)
    }

    @Test
    fun `verify - mdblist withUpdatedPlayback reuses watched collections when only playback changed`() {
        val snapshot = MdbListSyncSnapshot(
            accountId = 123,
            watched = listOf(
                MdbListWatchedRecord(
                    type = MdbListItemType.MOVIE,
                    media = MdbListMedia(ids = MdbListIds(imdb = "tt100")),
                    watchedAt = "2026-01-01T00:00:00Z"
                )
            ),
            playback = emptyList()
        )
        val initialProjection = MdbListProgressProjection(snapshot)

        val newPlayback = listOf(
            MdbListPlayback(
                id = 1L,
                type = MdbListItemType.MOVIE,
                media = MdbListMedia(ids = MdbListIds(imdb = "tt200")),
                progress = 45f,
                updatedAt = "2026-01-02T00:00:00Z"
            )
        )

        val updatedProjection = initialProjection.withUpdatedPlayback(newPlayback)

        assertTrue(initialProjection.watchedItems === updatedProjection.watchedItems)
        assertTrue(initialProjection.watchedMovieIds === updatedProjection.watchedMovieIds)
        assertTrue(initialProjection.watchedShowEpisodes === updatedProjection.watchedShowEpisodes)
        assertEquals(1, updatedProjection.progress.size)
    }

    @Test
    fun `verify - simkl applyScrobbleResult on pause with large library is fast and preserves entries reference`() {
        // Construct large library (1,000 entries with seasons and episodes)
        val entries = (1..1000).map { i ->
            SimklLibraryEntry(
                mediaType = if (i % 2 == 0) SimklMediaType.MOVIES else SimklMediaType.SHOWS,
                movie = if (i % 2 == 0) SimklMedia(title = "Movie $i", ids = mapOf("imdb" to kotlinx.serialization.json.JsonPrimitive("tt$i"))) else null,
                show = if (i % 2 != 0) SimklMedia(title = "Show $i", ids = mapOf("imdb" to kotlinx.serialization.json.JsonPrimitive("tt$i"))) else null
            )
        }
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = entries,
            playback = emptyList()
        )
        val pauseResult = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.PAUSE,
            playbackId = 42L,
            progress = 25.0,
            mediaType = SimklMediaType.MOVIES,
            media = SimklMedia(title = "Movie 500", ids = mapOf("imdb" to kotlinx.serialization.json.JsonPrimitive("tt500"))),
            episode = null
        )

        val startTime = System.nanoTime()
        val updated = snapshot.applyScrobbleResult(pauseResult, System.currentTimeMillis())
        val durationMs = (System.nanoTime() - startTime) / 1_000_000

        // Entries reference must be completely untouched
        assertTrue(snapshot.entries === updated.entries)
        assertEquals(1, updated.playback.size)
        // Must complete in less than 50ms even on cold JVM
        assertTrue("Expected < 50ms but was ${durationMs}ms", durationMs < 50)
    }

    @Test
    fun `verify - mdblist applyScrobble on pause with thousands of watched items preserves watched reference and resolves media`() {
        val watched = (1..2500).map { i ->
            MdbListWatchedRecord(
                type = MdbListItemType.MOVIE,
                media = MdbListMedia(ids = MdbListIds(imdb = "tt$i", tmdb = i.toLong())),
                watchedAt = "2026-01-01T00:00:00Z"
            )
        }
        val snapshot = MdbListSyncSnapshot(
            accountId = 123,
            watched = watched,
            playback = emptyList()
        )
        val target = com.nuvio.tv.data.mdblist.MdbListMutationTarget(
            type = MdbListItemType.MOVIE,
            media = MdbListMedia(ids = MdbListIds(imdb = "tt500"))
        )
        val receipt = com.nuvio.tv.data.mdblist.MdbListScrobbleReceipt(
            target = target,
            action = com.nuvio.tv.core.tracking.TrackingScrobbleAction.PAUSE,
            progress = 42f,
            timestamp = "2026-01-02T00:00:00Z",
            playbackId = 999L
        )

        val startTime = System.nanoTime()
        val updated = snapshot.applyScrobble(receipt)
        val durationMs = (System.nanoTime() - startTime) / 1_000_000

        // Watched list must be identical by reference (zero allocations, no copy)
        assertTrue(snapshot.watched === updated.watched)
        assertEquals(1, updated.playback.size)
        // Target media should have resolved TMDB id from matching watched record tt500
        assertEquals(500L, updated.playback.single().media.ids.tmdb)
        assertTrue("Expected < 50ms but was ${durationMs}ms", durationMs < 50)
    }
}
