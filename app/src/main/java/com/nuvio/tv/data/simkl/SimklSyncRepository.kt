package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class SimklSyncRepository @Inject constructor(
    private val engine: SimklSyncEngine,
    private val storage: SimklSyncStorage,
    private val authRepository: SimklAuthRepository,
    private val authStorage: SimklAuthStorage,
    private val profileManager: ProfileManager
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val snapshotMutex = Mutex()
    private val refreshGate = SimklRefreshGate()
    private val _state = MutableStateFlow(SimklSyncState())
    private val _projection = MutableStateFlow(SimklSnapshotProjection.Empty)
    private var loadedProfileId: Int? = null
    private var profileGeneration = 0L

    val state: StateFlow<SimklSyncState> = _state.asStateFlow()
    internal val projection: StateFlow<SimklSnapshotProjection> = _projection.asStateFlow()

    init {
        scope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (loadedProfileId != profileId) {
                    profileGeneration += 1L
                    loadedProfileId = null
                    _state.value = SimklSyncState()
                    _projection.value = SimklSnapshotProjection.Empty
                    loadProfile(profileId)
                }
            }
        }
    }

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        if (loadedProfileId != profileId) loadProfile(profileId)
    }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        scope.launch { refresh(intent) }
    }

    suspend fun refresh(intent: TrackingRefreshIntent) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        refreshGate.runIfNeeded(
            profileGeneration = generation,
            shouldRun = {
                val current = _state.value
                profileId == profileManager.activeProfileId.value &&
                    generation == profileGeneration &&
                    authStorage.state.value.isAuthenticated &&
                    shouldRunSimklRefresh(
                        intent = intent,
                        lastCheckedAtEpochMs = current.snapshot.lastCheckedAtEpochMs,
                        nowEpochMs = System.currentTimeMillis(),
                        hasError = current.errorMessage != null
                    )
            }
        ) {
            refreshSnapshot(profileId, generation)
        }
    }

    suspend fun clearCurrentProfile() = withContext(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        profileGeneration += 1L
        storage.remove(profileId)
        if (profileId == profileManager.activeProfileId.value) {
            loadedProfileId = profileId
            _projection.value = SimklSnapshotProjection.Empty
            _state.value = SimklSyncState(hasLoaded = true)
        }
    }

    suspend fun removeProfile(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == profileManager.activeProfileId.value) {
            clearCurrentProfile()
        } else {
            storage.remove(profileId)
        }
    }

    suspend fun removePlaybackSessions(sessionIds: Set<Long>) = withContext(Dispatchers.IO) {
        if (sessionIds.isEmpty()) return@withContext
        ensureLoaded()
        snapshotMutex.withLock {
            val profileId = profileManager.activeProfileId.value
            val current = _state.value
            val playback = current.snapshot.playback.filterNot { session -> session.id in sessionIds }
            if (playback.size == current.snapshot.playback.size) return@withLock
            val snapshot = current.snapshot.copy(playback = playback)
            val projection = buildProjection(snapshot)
            storage.save(profileId, encodeSnapshot(snapshot))
            if (profileId == profileManager.activeProfileId.value) {
                _projection.value = projection
                _state.value = current.copy(snapshot = snapshot)
            }
        }
    }

    internal suspend fun commitScrobble(result: SimklScrobbleResult) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        snapshotMutex.withLock {
            if (!isCurrent(profileId, generation)) return@withLock
            val current = _state.value
            val snapshot = current.snapshot.applyScrobbleResult(
                result = result,
                committedAtEpochMs = System.currentTimeMillis()
            )
            if (snapshot == current.snapshot) return@withLock
            val projection = buildProjection(snapshot)
            storage.save(profileId, encodeSnapshot(snapshot))
            if (isCurrent(profileId, generation)) {
                _projection.value = projection
                _state.value = current.copy(snapshot = snapshot)
            }
        }
    }

    internal suspend fun commitMutation(receipt: SimklMutationReceipt) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        snapshotMutex.withLock {
            if (!isCurrent(profileId, generation)) return@withLock
            val current = _state.value
            val snapshot = current.snapshot.applyMutationReceipt(
                receipt = receipt,
                committedAtEpochMs = System.currentTimeMillis()
            )
            if (snapshot == current.snapshot) return@withLock
            val projection = buildProjection(snapshot)
            storage.save(profileId, encodeSnapshot(snapshot))
            if (isCurrent(profileId, generation)) {
                _projection.value = projection
                _state.value = current.copy(snapshot = snapshot)
            }
        }
    }

    private suspend fun loadProfile(profileId: Int) = loadMutex.withLock {
        if (loadedProfileId == profileId) return@withLock
        val snapshot = storage.load(profileId)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { decodeSnapshot(payload) }
                    .onFailure { error -> Log.w(TAG, "Unable to decode Simkl snapshot", error) }
                    .getOrNull()
            }
            ?: SimklSyncSnapshot()
        val projection = buildProjection(snapshot)
        if (profileId == profileManager.activeProfileId.value) {
            loadedProfileId = profileId
            _projection.value = projection
            _state.value = SimklSyncState(snapshot = snapshot, hasLoaded = true)
        }
    }

    private suspend fun refreshSnapshot(profileId: Int, generation: Long) = snapshotMutex.withLock {
        val previous = _state.value
        _state.value = previous.copy(isLoading = true, errorMessage = null)
        val result = try {
            engine.synchronize(previous.snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Simkl sync failed", error)
            if (isCurrent(profileId, generation)) {
                _state.value = previous.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = error.message ?: "Unable to sync Simkl"
                )
            }
            return@withLock
        }
        if (!isCurrent(profileId, generation)) return@withLock
        val projection = if (
            result.entries === previous.snapshot.entries &&
            result.playback === previous.snapshot.playback
        ) {
            _projection.value
        } else {
            buildProjection(result)
        }
        authRepository.synchronizeUserSettings(result.activities?.settings?.all)
        if (!isCurrent(profileId, generation)) return@withLock
        storage.save(profileId, encodeSnapshot(result))
        if (isCurrent(profileId, generation)) {
            _projection.value = projection
            _state.value = SimklSyncState(snapshot = result, hasLoaded = true)
        }
    }

    private fun isCurrent(profileId: Int, generation: Long): Boolean =
        profileId == profileManager.activeProfileId.value && generation == profileGeneration

    fun invalidateProjections(animeIdPreference: SimklAnimeIdPreference? = null) {
        animeIdPreference?.let { preference -> SimklAnimeIdPreferenceHolder.current = preference }
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        scope.launch {
            snapshotMutex.withLock {
                if (!isCurrent(profileId, generation)) return@withLock
                val current = _state.value
                val projection = buildProjection(current.snapshot)
                if (!isCurrent(profileId, generation) || current.snapshot !== _state.value.snapshot) {
                    return@withLock
                }
                _projection.value = projection
                _state.value = current.copy(projectionVersion = current.projectionVersion + 1L)
            }
        }
    }

    private suspend fun buildProjection(snapshot: SimklSyncSnapshot): SimklSnapshotProjection =
        withContext(Dispatchers.Default) { SimklSnapshotProjection.create(snapshot) }

    private suspend fun decodeSnapshot(payload: String): SimklSyncSnapshot =
        withContext(Dispatchers.Default) { json.decodeFromString(payload) }

    private suspend fun encodeSnapshot(snapshot: SimklSyncSnapshot): String =
        withContext(Dispatchers.Default) { json.encodeToString(snapshot) }

    private companion object {
        const val TAG = "SimklSync"
    }
}
