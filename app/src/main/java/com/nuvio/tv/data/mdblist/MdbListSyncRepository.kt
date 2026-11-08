package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingRefreshGate
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MdbListSyncRepository(
    private val storage: MdbListSyncStorage,
    private val auth: MdbListAuthStore,
    private val api: MdbListApiClient,
    private val activeProfileId: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val remote: (MdbListAuthScope) -> MdbListSyncRemote = { MdbListHttpSyncRemote(api, it) }
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val gate = TrackingRefreshGate()
    private val mutableState = MutableStateFlow(MdbListSyncState())
    private val mutableProjection = MutableStateFlow(MdbListProgressState())
    val state = mutableState.asStateFlow()
    internal val progressState = mutableProjection.asStateFlow()

    init {
        coroutineScope.launch {
            combine(activeProfileId, auth.state) { profileId, authorization ->
                Triple(profileId, authorization.scope, authorization.user?.id)
            }.distinctUntilChanged().collectLatest {
                if (!matchesCurrent(mutableState.value.scope)) clearPublished()
                try {
                    ensureLoaded()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(auth.scope())) mutableState.value = MdbListSyncState(
                        scope = auth.scope(), error = error.toMdbListSyncError()
                    )
                }
            }
        }
    }

    fun currentScope(): MdbListAuthScope = auth.scope().also(::checkScope)

    fun currentSnapshot(): MdbListSyncSnapshot? = mutableState.value.takeIf { matchesCurrent(it.scope) }?.snapshot

    internal fun currentProjection(): MdbListProgressProjection = mutableProjection.value
        .takeIf { matchesCurrent(it.scope) && auth.state.value.isAuthenticated }?.projection ?: MdbListProgressProjection.Empty

    suspend fun ensureLoaded() = withContext(dispatcher) { mutex.withLock { loadCurrent() } }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        coroutineScope.launch { refresh(intent) }
    }

    suspend fun refresh(intent: TrackingRefreshIntent) = withContext(dispatcher) {
        val scope = auth.scope()
        gate.runIfNeeded(scope.generation, shouldRun = {
            auth.state.value.isAuthenticated && matchesCurrent(scope) && shouldRefresh(intent)
        }) {
            mutex.withLock {
                checkScope(scope)
                val attemptedAt = now()
                try {
                    if (auth.state.value.user?.id == null) api.refreshUser(scope)
                    val snapshot = loadCurrent() ?: return@withLock
                    mutableState.value = mutableState.value.copy(isLoading = true, error = null, attemptedAtEpochMs = attemptedAt)
                    val result = MdbListSyncEngine(remote(scope), now).synchronize(snapshot)
                    commit(scope, result)
                    mutableState.value = mutableState.value.copy(attemptedAtEpochMs = attemptedAt)
                } catch (error: CancellationException) {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLoading = false)
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(
                        scope = scope,
                        isLoading = false,
                        error = error.toMdbListSyncError(),
                        retryAtEpochMs = (error as? MdbListApiException)?.retryAtEpochMs,
                        attemptedAtEpochMs = attemptedAt
                    )
                }
            }
        }
    }

    internal suspend fun <T> mutate(
        scope: MdbListAuthScope,
        block: suspend (MdbListSyncSnapshot) -> Pair<MdbListSyncSnapshot, T>
    ): T = withContext(dispatcher) {
        mutex.withLock {
            checkScope(scope)
            if (auth.state.value.user?.id == null) api.refreshUser(scope)
            val previous = loadCurrent() ?: throw MdbListAuthException(MdbListAuthError.AUTHORIZATION_REVOKED)
            val (next, result) = block(previous)
            if (next != previous) commit(scope, next)
            checkScope(scope)
            result
        }
    }

    internal suspend fun invalidate(scope: MdbListAuthScope, buckets: Set<MdbListSyncBucket>) {
        mutate(scope) { previous -> previous.copy(invalidatedBuckets = previous.invalidatedBuckets + buckets) to Unit }
    }

    private suspend fun loadCurrent(): MdbListSyncSnapshot? {
        val scope = auth.scope()
        checkScope(scope, requireAuthorization = false)
        val authorization = auth.state.value
        if (!authorization.isAuthenticated) {
            clearPublished()
            storage.remove(scope.profileId) { checkScope(scope, requireAuthorization = false) }
            return null
        }
        val accountId = authorization.user?.id ?: return null
        val current = mutableState.value
        if (current.scope == scope && current.snapshot?.accountId == accountId) return current.snapshot
        val payload = storage.load(scope.profileId)
        val persistedSnapshot = payload?.let { runCatching { json.decodeFromString<MdbListSyncSnapshot>(it) }.getOrNull() }
            ?.takeIf { it.accountId == accountId }
        var snapshot = persistedSnapshot ?: MdbListSyncSnapshot(accountId)
        storage.loadPlayback(scope.profileId)
            ?.takeIf(String::isNotBlank)
            ?.let { storagePlayback ->
                decodePlaybackOverride(storagePlayback, persistedSnapshot, accountId)?.let { playback ->
                    snapshot = snapshot.copy(playback = playback)
                }
            }
        checkScope(scope)
        val projection = runCatching { MdbListProgressProjection(snapshot) }.getOrNull()
        val usable = if (projection != null) snapshot else MdbListSyncSnapshot(accountId)
        mutableProjection.value = MdbListProgressState(scope, projection ?: MdbListProgressProjection.Empty)
        mutableState.value = MdbListSyncState(scope, usable)
        return usable
    }

    private suspend fun commit(scope: MdbListAuthScope, snapshot: MdbListSyncSnapshot) {
        checkScope(scope)
        val previous = mutableState.value.snapshot
        val currentProjection = mutableProjection.value.projection
        val onlyPlaybackChanged = previous != null &&
            previous.watched === snapshot.watched &&
            previous.dropped === snapshot.dropped &&
            previous.library === snapshot.library &&
            previous.invalidatedBuckets === snapshot.invalidatedBuckets &&
            previous.watermark == snapshot.watermark &&
            previous.activities === snapshot.activities &&
            previous.checkedAtEpochMs == snapshot.checkedAtEpochMs &&
            previous.isInitialized == snapshot.isInitialized &&
            previous.accountId == snapshot.accountId
        val storedSnapshot = if (onlyPlaybackChanged) {
            snapshot
        } else {
            snapshot.copy(storageRevision = UUID.randomUUID().toString())
        }
        val projection = if (previous != null && previous.watched === snapshot.watched &&
            previous.playback === snapshot.playback && previous.dropped === snapshot.dropped) {
            currentProjection
        } else if (onlyPlaybackChanged && currentProjection !== MdbListProgressProjection.Empty) {
            currentProjection.withUpdatedPlayback(snapshot.playback)
        } else MdbListProgressProjection(storedSnapshot)
        if (onlyPlaybackChanged) {
            val playbackPayload = json.encodeToString(
                MdbListPlaybackOverride(
                    accountId = storedSnapshot.accountId,
                    storageRevision = storedSnapshot.storageRevision,
                    playback = storedSnapshot.playback
                )
            )
            storage.savePlayback(scope.profileId, playbackPayload) { checkScope(scope) }
        } else {
            val payload = json.encodeToString(storedSnapshot)
            storage.save(scope.profileId, payload) { checkScope(scope) }
        }
        checkScope(scope)
        mutableProjection.value = MdbListProgressState(scope, projection)
        mutableState.value = MdbListSyncState(scope, storedSnapshot)
    }

    private fun decodePlaybackOverride(
        payload: String,
        persistedSnapshot: MdbListSyncSnapshot?,
        accountId: Long
    ): List<MdbListPlayback>? {
        val override = runCatching {
            json.decodeFromString<MdbListPlaybackOverride>(payload)
        }.getOrNull()
        if (override != null) {
            return override.playback.takeIf {
                persistedSnapshot != null && override.accountId == accountId &&
                    override.storageRevision == persistedSnapshot.storageRevision
            }
        }
        return runCatching { json.decodeFromString<List<MdbListPlayback>>(payload) }.getOrNull()
            ?.takeIf { persistedSnapshot != null && persistedSnapshot.storageRevision == null }
    }

    private fun shouldRefresh(intent: TrackingRefreshIntent): Boolean {
        val current = mutableState.value.takeIf { matchesCurrent(it.scope) } ?: return true
        val now = now()
        if (current.retryAtEpochMs?.let { it > now } == true) return false
        if (intent != TrackingRefreshIntent.AUTOMATIC) return true
        if (current.error != null) return current.attemptedAtEpochMs?.let { now - it !in 0 until ERROR_RETRY_MS } ?: true
        if (current.snapshot?.invalidatedBuckets?.isNotEmpty() == true) return true
        return current.snapshot?.checkedAtEpochMs?.let { now - it !in 0 until AUTOMATIC_INTERVAL_MS } ?: true
    }

    private fun checkScope(scope: MdbListAuthScope, requireAuthorization: Boolean = true) {
        if (!matchesCurrent(scope)) throw CancellationException("MDBList account changed")
        if (requireAuthorization && !auth.state.value.isAuthenticated) {
            throw MdbListAuthException(MdbListAuthError.AUTHORIZATION_REVOKED)
        }
    }

    private fun matchesCurrent(scope: MdbListAuthScope?): Boolean =
        scope != null && scope.profileId == activeProfileId.value && auth.isCurrent(scope)

    private fun clearPublished() {
        mutableProjection.value = MdbListProgressState()
        mutableState.value = MdbListSyncState()
    }

    companion object {
        const val AUTOMATIC_INTERVAL_MS = 15L * 60 * 1_000
        const val ERROR_RETRY_MS = 60_000L
    }
}

@Serializable
private data class MdbListPlaybackOverride(
    val accountId: Long,
    val storageRevision: String? = null,
    val playback: List<MdbListPlayback>
)
