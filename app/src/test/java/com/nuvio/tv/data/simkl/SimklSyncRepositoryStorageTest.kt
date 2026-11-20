package com.nuvio.tv.data.simkl

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import java.io.IOException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SimklSyncRepositoryStorageTest {

    private class FakeSimklSyncStorage : SimklSyncStorage {
        val snapshots = mutableMapOf<Int, String>()
        val playbacks = mutableMapOf<Int, String>()
        var saveSnapshotCalls = 0
        var savePlaybackCalls = 0
        var clearPlaybackCalls = 0

        override suspend fun load(profileId: Int): String? = snapshots[profileId]

        override suspend fun save(profileId: Int, payload: String) {
            saveSnapshotCalls++
            snapshots[profileId] = payload
            playbacks.remove(profileId)
        }

        override suspend fun remove(profileId: Int) {
            snapshots.remove(profileId)
            playbacks.remove(profileId)
        }

        override suspend fun loadPlayback(profileId: Int): String? = playbacks[profileId]

        override suspend fun savePlayback(profileId: Int, payload: String) {
            savePlaybackCalls++
            playbacks[profileId] = payload
        }

        override suspend fun clearPlayback(profileId: Int) {
            clearPlaybackCalls++
            playbacks.remove(profileId)
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `commitScrobble with pause writes only to playback storage and does not re-serialize base snapshot`() = runTest {
        val storage = FakeSimklSyncStorage()
        val activeProfile = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns activeProfile
        }
        val engine = mockk<SimklSyncEngine>(relaxed = true)
        val authRepo = mockk<SimklAuthRepository>(relaxed = true)
        val authStorage = mockk<SimklAuthStorage>(relaxed = true) {
            every { state } returns MutableStateFlow(SimklAuthState(isAuthenticated = true))
        }

        // Seed 500 library entries into base snapshot
        val entries = (1..500).map { i ->
            SimklLibraryEntry(
                mediaType = SimklMediaType.MOVIES,
                movie = SimklMedia(title = "Movie $i", ids = mapOf("imdb" to JsonPrimitive("tt$i")))
            )
        }
        val baseSnapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = entries,
            playback = emptyList()
        )
        storage.snapshots[1] = json.encodeToString(baseSnapshot)

        val repository = SimklSyncRepository(
            engine = engine,
            storage = storage,
            authRepository = authRepo,
            authStorage = authStorage,
            profileManager = profileManager
        )
        repository.ensureLoaded()

        assertEquals(0, storage.saveSnapshotCalls)
        assertEquals(0, storage.savePlaybackCalls)

        val loadedEntries = repository.state.value.snapshot.entries

        // Execute a pause scrobble
        val pauseResult = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.PAUSE,
            playbackId = 42L,
            progress = 45.0,
            mediaType = SimklMediaType.MOVIES,
            media = SimklMedia(title = "Movie 1", ids = mapOf("imdb" to JsonPrimitive("tt1"))),
            episode = null
        )
        repository.commitScrobble(pauseResult)

        // Verify that savePlayback was called, but saveSnapshot was NEVER called
        assertEquals(1, storage.savePlaybackCalls)
        assertEquals(0, storage.saveSnapshotCalls)
        assertNotNull(storage.playbacks[1])

        // Verify playback state is updated in repository
        val current = repository.state.value.snapshot
        assertEquals(1, current.playback.size)
        assertEquals(45.0, current.playback.first().progress, 0.001)
        // Verify entries collection was reused by reference
        assertTrue(loadedEntries === current.entries)
    }

    @Test
    fun `loadProfile restores playback session from separate playback storage after process restart`() = runTest {
        val storage = FakeSimklSyncStorage()
        val activeProfile = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns activeProfile
        }
        val engine = mockk<SimklSyncEngine>(relaxed = true)
        val authRepo = mockk<SimklAuthRepository>(relaxed = true)
        val authStorage = mockk<SimklAuthStorage>(relaxed = true) {
            every { state } returns MutableStateFlow(SimklAuthState(isAuthenticated = true))
        }

        // Base snapshot on disk has empty playback
        val baseSnapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.MOVIES,
                    movie = SimklMedia(title = "Movie 1", ids = mapOf("imdb" to JsonPrimitive("tt1")))
                )
            ),
            playback = emptyList()
        )
        storage.snapshots[1] = json.encodeToString(baseSnapshot)

        // Separate playback storage has active playback session from a previous pause
        val playbackSession = SimklPlaybackSession(
            id = 999L,
            progress = 62.5,
            type = "movie",
            movie = SimklMedia(title = "Movie 1", ids = mapOf("imdb" to JsonPrimitive("tt1")))
        )
        storage.playbacks[1] = json.encodeToString(listOf(playbackSession))

        val repository = SimklSyncRepository(
            engine = engine,
            storage = storage,
            authRepository = authRepo,
            authStorage = authStorage,
            profileManager = profileManager
        )
        repository.ensureLoaded()

        // Verify loaded snapshot merged the playback session from playback storage
        val loaded = repository.state.value.snapshot
        assertEquals(1, loaded.playback.size)
        assertEquals(999L, loaded.playback.first().id)
        assertEquals(62.5, loaded.playback.first().progress, 0.001)
    }

    @Test
    fun `removePlaybackSessions updates only playback storage without touching base snapshot`() = runTest {
        val storage = FakeSimklSyncStorage()
        val activeProfile = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns activeProfile
        }
        val engine = mockk<SimklSyncEngine>(relaxed = true)
        val authRepo = mockk<SimklAuthRepository>(relaxed = true)
        val authStorage = mockk<SimklAuthStorage>(relaxed = true) {
            every { state } returns MutableStateFlow(SimklAuthState(isAuthenticated = true))
        }

        val session = SimklPlaybackSession(
            id = 123L,
            progress = 30.0,
            type = "movie",
            movie = SimklMedia(title = "Movie 1", ids = mapOf("imdb" to JsonPrimitive("tt1")))
        )
        val baseSnapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = listOf(
                SimklLibraryEntry(
                    mediaType = SimklMediaType.MOVIES,
                    movie = SimklMedia(title = "Movie 1", ids = mapOf("imdb" to JsonPrimitive("tt1")))
                )
            ),
            playback = listOf(session)
        )
        storage.snapshots[1] = json.encodeToString(baseSnapshot)

        val repository = SimklSyncRepository(
            engine = engine,
            storage = storage,
            authRepository = authRepo,
            authStorage = authStorage,
            profileManager = profileManager
        )
        repository.ensureLoaded()

        repository.removePlaybackSessions(setOf(123L))

        assertEquals(1, storage.savePlaybackCalls)
        assertEquals(0, storage.saveSnapshotCalls)
        val current = repository.state.value.snapshot
        assertTrue(current.playback.isEmpty())
    }

    @Test
    fun `failed snapshot save or remove preserves the latest playback override`() = runTest {
        val snapshotKey = stringPreferencesKey("snapshot")
        val playbackKey = stringPreferencesKey("playback")
        val opLog = mutableListOf<String>()
        val storedSnapshot = """{"isInitialized":true}"""
        val storedPlayback = """[{"id":42,"progress":50.0}]"""

        val initialSync = emptyPreferences().toMutablePreferences().apply {
            this[snapshotKey] = storedSnapshot
        }.toPreferences()
        val initialPlayback = emptyPreferences().toMutablePreferences().apply {
            this[playbackKey] = storedPlayback
        }.toPreferences()

        val syncStore = RecordingPreferencesDataStore("sync", initialSync, opLog)
        val playbackStore = RecordingPreferencesDataStore("playback", initialPlayback, opLog)
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, "simkl_sync") } returns syncStore
            every { get(1, "simkl_playback") } returns playbackStore
        }
        val storage = AndroidSimklSyncStorage(factory)

        syncStore.failEdits = true
        expectStorageFailure { storage.save(1, """{"isInitialized":true,"entries":[]}""") }
        assertEquals(storedSnapshot, syncStore.value[snapshotKey])
        assertEquals(storedPlayback, storage.loadPlayback(1))

        syncStore.failEdits = false
        storage.savePlayback(1, storedPlayback)
        syncStore.failEdits = true
        expectStorageFailure { storage.remove(1) }
        assertEquals(storedSnapshot, syncStore.value[snapshotKey])
        assertEquals(storedPlayback, storage.loadPlayback(1))
    }

    @Test
    fun `AndroidSimklSyncStorage migrates legacy playback and saves the snapshot before clearing playback`() = runTest {
        val snapshotKey = stringPreferencesKey("snapshot")
        val playbackKey = stringPreferencesKey("playback")
        val opLog = mutableListOf<String>()

        val initialLegacyPrefs = emptyPreferences().toMutablePreferences().apply {
            this[snapshotKey] = """{"isInitialized":true}"""
            this[playbackKey] = """[{"id":42,"progress":50.0}]"""
        }.toPreferences()

        val syncStore = RecordingPreferencesDataStore("sync", initialLegacyPrefs, opLog)
        val playbackStore = RecordingPreferencesDataStore("playback", emptyPreferences(), opLog)
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, "simkl_sync") } returns syncStore
            every { get(1, "simkl_playback") } returns playbackStore
        }
        val storage = AndroidSimklSyncStorage(factory)

        // 1. Before migration, loadPlayback falls back to legacy key in simkl_sync
        assertEquals("""[{"id":42,"progress":50.0}]""", storage.loadPlayback(1))
        assertEquals(1, syncStore.readCount)

        // 2. save() commits the snapshot first, then clears the override (writing "" tombstone)
        opLog.clear()
        syncStore.resetReadCount()
        storage.save(1, """{"isInitialized":true,"entries":[]}""")

        assertEquals(listOf("sync:edit", "playback:edit"), opLog)
        assertEquals("", playbackStore.value[playbackKey])
        assertNull(syncStore.value[playbackKey])
        assertEquals("""{"isInitialized":true,"entries":[]}""", syncStore.value[snapshotKey])

        // 3. Subsequent loadPlayback sees "" tombstone in simkl_playback and returns null WITHOUT reading simkl_sync
        syncStore.resetReadCount()
        assertNull(storage.loadPlayback(1))
        assertEquals(0, syncStore.readCount)

        // 4. remove() removes the snapshot first, then clears the override
        storage.savePlayback(1, """[{"id":99,"progress":75.0}]""")
        opLog.clear()
        storage.remove(1)
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
