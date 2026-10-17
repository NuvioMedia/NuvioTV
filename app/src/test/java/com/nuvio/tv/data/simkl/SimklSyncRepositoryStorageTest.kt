package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}
