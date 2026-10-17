package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MdbListSyncRepositoryStorageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `scrobble pause updates only playback storage and skips full snapshot serialization`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val harness = MdbListSyncTestHarness(TestScope(testDispatcher))
        val media = mdbListTestMovie(1)
        val snapshot = harness.snapshot().copy(
            watched = (1..500).map { mdbListTestMovie(it.toLong()) },
            playback = emptyList()
        )
        harness.seed(snapshot)
        harness.repository.ensureLoaded()

        val service = MdbListScrobbleService(harness.http.api, harness.repository)
        harness.http.reply(body = """{"action":"pause","progress":42.0,"paused_at":"$MDBLIST_TEST_TIME"}""")

        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                title = "Movie 1",
                year = 2026,
                ids = TrackingExternalIds(imdb = "tt1", tmdb = 1L)
            ),
            progressPercent = 42.0
        )

        val beforeSaveCalls = harness.storage.saveCalls
        val beforePlaybackCalls = harness.storage.savePlaybackCalls

        service.scrobble(harness.http.store.scope(), TrackingScrobbleAction.PAUSE, event)

        // Verify only savePlayback was called, saving thousands of watched items was bypassed
        assertEquals(beforePlaybackCalls + 1, harness.storage.savePlaybackCalls)
        assertEquals(beforeSaveCalls, harness.storage.saveCalls)

        // Verify playback state updated in repository
        val currentSnapshot = harness.repository.currentSnapshot()
        assertNotNull(currentSnapshot)
        assertEquals(1, currentSnapshot!!.playback.size)
        assertEquals(42.0f, currentSnapshot.playback.first().progress, 0.001f)
    }

    @Test
    fun `loadCurrent restores playback state from playback storage on restart`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val harness = MdbListSyncTestHarness(TestScope(testDispatcher))

        val baseSnapshot = harness.snapshot().copy(
            watched = listOf(mdbListTestMovie(1)),
            playback = emptyList()
        )
        harness.storage.profiles[1] = json.encodeToString(baseSnapshot)
        harness.storage.playbacks[1] = json.encodeToString(listOf(mdbListTestPlayback(progress = 55.0f)))

        harness.repository.ensureLoaded()

        val current = harness.repository.currentSnapshot()
        assertNotNull(current)
        assertEquals(1, current!!.playback.size)
        assertEquals(55.0f, current.playback.first().progress, 0.001f)
    }

    @Test
    fun `watched mutation saves full snapshot and clears playback storage`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val harness = MdbListSyncTestHarness(TestScope(testDispatcher))

        val baseSnapshot = harness.snapshot().copy(
            watched = listOf(mdbListTestMovie(1)),
            playback = listOf(mdbListTestPlayback(progress = 80.0f))
        )
        harness.seed(baseSnapshot)
        harness.storage.playbacks[1] = json.encodeToString(baseSnapshot.playback)
        harness.repository.ensureLoaded()

        // Mutate watched list
        val newWatched = mdbListTestMovie(2)
        harness.repository.mutate(harness.http.store.scope()) { snap ->
            snap.copy(watched = snap.watched + newWatched) to Unit
        }

        // Full snapshot save was called
        assertTrue(harness.storage.saveCalls > 0)
        // Playback storage was cleared by save
        assertNull(harness.storage.playbacks[1])
    }
}
