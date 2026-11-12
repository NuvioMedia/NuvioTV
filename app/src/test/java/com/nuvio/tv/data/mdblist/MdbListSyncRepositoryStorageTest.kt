package com.nuvio.tv.data.mdblist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MdbListSyncRepositoryStorageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `scrobble pause updates only playback storage and skips full snapshot serialization`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val harness = MdbListSyncTestHarness(TestScope(testDispatcher))
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
    fun `account change drops a persisted playback override from the previous account`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val harness = MdbListSyncTestHarness(TestScope(testDispatcher))
        harness.seed(harness.snapshot(accountId = 42).copy(watched = emptyList(), playback = emptyList()))
        harness.storage.playbacks[1] = json.encodeToString(listOf(mdbListTestPlayback(progress = 55f)))
        harness.repository.ensureLoaded()
        assertEquals(55f, harness.repository.currentSnapshot()!!.playback.single().progress, 0.001f)

        harness.switch(profileId = 1, accountId = 99)
        harness.repository.ensureLoaded()

        val current = requireNotNull(harness.repository.currentSnapshot())
        assertEquals(99L, current.accountId)
        assertTrue("playback from account 42 must not leak into account 99", current.playback.isEmpty())
    }

    @Test
    fun `playback-only update keeps dropped alias handling equal to a full projection rebuild`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val harness = MdbListSyncTestHarness(TestScope(testDispatcher))
        val droppedIds = MdbListIds(tmdb = 123L)
        harness.seed(
            harness.snapshot().copy(
                watched = emptyList(),
                dropped = listOf(MdbListDroppedRecord(ids = droppedIds)),
                playback = emptyList()
            )
        )
        harness.repository.ensureLoaded()

        val playback = MdbListPlayback(
            id = 7L,
            type = MdbListItemType.EPISODE,
            media = MdbListMedia(ids = MdbListIds(imdb = "ttExample", tmdb = 123L)),
            season = 1,
            episode = 2,
            progress = 40f,
            updatedAt = MDBLIST_TEST_TIME
        )
        harness.repository.mutate(harness.http.store.scope()) { snapshot ->
            snapshot.copy(playback = listOf(playback)) to Unit
        }

        val updatedSnapshot = requireNotNull(harness.repository.currentSnapshot())
        val incremental = harness.repository.currentProjection()
        val full = MdbListProgressProjection(updatedSnapshot)

        assertEquals(full.progress, incremental.progress)
        assertEquals(full.isHidden("ttExample"), incremental.isHidden("ttExample"))
        assertTrue(incremental.progress.isEmpty())
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

    @Test
    fun `failed snapshot save or remove preserves the latest playback override`() = runTest {
        val snapshotKey = stringPreferencesKey("snapshot")
        val playbackKey = stringPreferencesKey("playback")
        val opLog = mutableListOf<String>()
        val storedSnapshot = """{"accountId":1}"""
        val storedPlayback = """[{"id":7,"progress":33.0}]"""

        val initialSync = emptyPreferences().toMutablePreferences().apply {
            this[snapshotKey] = storedSnapshot
        }.toPreferences()
        val initialPlayback = emptyPreferences().toMutablePreferences().apply {
            this[playbackKey] = storedPlayback
        }.toPreferences()

        val syncStore = RecordingPreferencesDataStore("sync", initialSync, opLog)
        val playbackStore = RecordingPreferencesDataStore("playback", initialPlayback, opLog)
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, "mdblist_sync") } returns syncStore
            every { get(1, "mdblist_playback") } returns playbackStore
            every { isProfileDeleted(1) } returns false
        }
        val storage = AndroidMdbListSyncStorage(factory)

        syncStore.failEdits = true
        expectStorageFailure { storage.save(1, """{"accountId":1,"watched":[]}""") {} }
        assertEquals(storedSnapshot, syncStore.value[snapshotKey])
        assertEquals(storedPlayback, storage.loadPlayback(1))

        syncStore.failEdits = false
        storage.savePlayback(1, storedPlayback) {}
        syncStore.failEdits = true
        expectStorageFailure { storage.remove(1) {} }
        assertEquals(storedSnapshot, syncStore.value[snapshotKey])
        assertEquals(storedPlayback, storage.loadPlayback(1))
    }

    @Test
    fun `AndroidMdbListSyncStorage migrates legacy playback and saves the snapshot before clearing playback`() = runTest {
        val snapshotKey = stringPreferencesKey("snapshot")
        val playbackKey = stringPreferencesKey("playback")
        val opLog = mutableListOf<String>()

        val initialLegacyPrefs = emptyPreferences().toMutablePreferences().apply {
            this[snapshotKey] = """{"accountId":1}"""
            this[playbackKey] = """[{"id":7,"progress":33.0}]"""
        }.toPreferences()

        val syncStore = RecordingPreferencesDataStore("sync", initialLegacyPrefs, opLog)
        val playbackStore = RecordingPreferencesDataStore("playback", emptyPreferences(), opLog)
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, "mdblist_sync") } returns syncStore
            every { get(1, "mdblist_playback") } returns playbackStore
            every { isProfileDeleted(1) } returns false
        }
        val storage = AndroidMdbListSyncStorage(factory)

        // 1. Before migration, loadPlayback falls back to legacy key in mdblist_sync
        assertEquals("""[{"id":7,"progress":33.0}]""", storage.loadPlayback(1))
        assertEquals(1, syncStore.readCount)

        // 2. save() commits the snapshot first, then clears the override (writing "" tombstone)
        opLog.clear()
        syncStore.resetReadCount()
        storage.save(1, """{"accountId":1,"watched":[]}""") {}

        assertEquals(listOf("sync:edit", "playback:edit"), opLog)
        assertEquals("", playbackStore.value[playbackKey])
        assertNull(syncStore.value[playbackKey])
        assertEquals("""{"accountId":1,"watched":[]}""", syncStore.value[snapshotKey])

        // 3. Subsequent loadPlayback sees "" tombstone in mdblist_playback and returns null WITHOUT reading mdblist_sync
        syncStore.resetReadCount()
        assertNull(storage.loadPlayback(1))
        assertEquals(0, syncStore.readCount)

        // 4. remove() removes the snapshot first, then clears the override
        storage.savePlayback(1, """[{"id":8,"progress":50.0}]""") {}
        opLog.clear()
        storage.remove(1) {}
        assertEquals(listOf("sync:edit", "playback:edit"), opLog)
        assertEquals("", playbackStore.value[playbackKey])
        assertNull(syncStore.value[snapshotKey])
    }

    private class RecordingPreferencesDataStore(
        private val name: String,
        initial: Preferences = emptyPreferences(),
        private val opLog: MutableList<String>
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)
        var readCount: Int = 0
            private set
        var failEdits = false

        val value: Preferences
            get() = state.value

        override val data: Flow<Preferences> = state.onEach { readCount += 1 }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                opLog += "$name:edit"
                if (failEdits) throw IOException("Disk unavailable")
                transform(state.value).also { updated ->
                    state.value = updated
                }
            }

        fun resetReadCount() {
            readCount = 0
        }
    }

    private suspend fun expectStorageFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected the storage write to fail")
        } catch (_: IOException) {
            Unit
        }
    }
}
