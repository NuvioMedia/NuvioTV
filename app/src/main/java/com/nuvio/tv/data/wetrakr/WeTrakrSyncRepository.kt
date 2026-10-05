package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingRefreshGate
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WeTrakrSyncRepository(
    private val storage: WeTrakrSyncStorage,
    private val auth: WeTrakrAuthStore,
    private val api: WeTrakrApiClient,
    private val activeProfileId: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val remote: (WeTrakrAuthScope) -> WeTrakrSyncRemote = { WeTrakrHttpSyncRemote(api, it) }
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val gate = TrackingRefreshGate()
    private val mutableState = MutableStateFlow(WeTrakrSyncState())
    private val mutableProjection = MutableStateFlow(WeTrakrProgressState())
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
                    if (matchesCurrent(auth.scope())) mutableState.value = WeTrakrSyncState(
                        scope = auth.scope(), error = error.toWeTrakrSyncError()
                    )
                }
            }
        }
    }

    fun currentScope(): WeTrakrAuthScope = auth.scope().also(::checkScope)

    fun currentSnapshot(): WeTrakrSyncSnapshot? = mutableState.value.takeIf { matchesCurrent(it.scope) }?.snapshot

    internal fun currentProjection(): WeTrakrProgressProjection = mutableProjection.value
        .takeIf { matchesCurrent(it.scope) && auth.state.value.isAuthenticated }?.projection ?: WeTrakrProgressProjection.Empty

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
                    if (auth.state.value.user == null) api.refreshUser(scope)
                    val snapshot = loadCurrent() ?: return@withLock
                    mutableState.value = mutableState.value.copy(isLoading = true, error = null, attemptedAtEpochMs = attemptedAt)
                    val result = WeTrakrSyncEngine(remote(scope), now).synchronize(snapshot)
                    commit(scope, result)
                    mutableState.value = mutableState.value.copy(attemptedAtEpochMs = attemptedAt)
                } catch (error: CancellationException) {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLoading = false)
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(
                        scope = scope,
                        isLoading = false,
                        error = error.toWeTrakrSyncError(),
                        retryAtEpochMs = (error as? WeTrakrApiException)?.retryAtEpochMs,
                        attemptedAtEpochMs = attemptedAt
                    )
                }
            }
        }
    }

    internal suspend fun <T> mutate(
        scope: WeTrakrAuthScope,
        block: suspend (WeTrakrSyncSnapshot) -> Pair<WeTrakrSyncSnapshot, T>
    ): T = withContext(dispatcher) {
        mutex.withLock {
            checkScope(scope)
            if (auth.state.value.user == null) api.refreshUser(scope)
            val previous = loadCurrent() ?: throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
            val (next, result) = block(previous)
            if (next != previous) commit(scope, next)
            checkScope(scope)
            result
        }
    }

    internal suspend fun <T> write(
        scope: WeTrakrAuthScope,
        buckets: Set<WeTrakrSyncBucket>,
        block: suspend (WeTrakrSyncSnapshot) -> Pair<WeTrakrSyncSnapshot, T>
    ): T = try {
        mutate(scope, block)
    } catch (error: CancellationException) {
        withContext(NonCancellable) {
            try {
                invalidate(scope, buckets)
            } catch (_: Exception) {
            }
        }
        throw error
    } catch (error: Exception) {
        if (error !is WeTrakrApiException || error.status != 404) {
            try {
                invalidate(scope, buckets)
                refreshAsync(TrackingRefreshIntent.INVALIDATED)
            } catch (_: Exception) {
            }
        }
        throw error
    }

    private suspend fun invalidate(scope: WeTrakrAuthScope, buckets: Set<WeTrakrSyncBucket>) {
        mutate(scope) { previous -> previous.copy(invalidatedBuckets = previous.invalidatedBuckets + buckets) to Unit }
    }

    private suspend fun loadCurrent(): WeTrakrSyncSnapshot? {
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
        val snapshot = payload?.let { runCatching { json.decodeFromString<WeTrakrSyncSnapshot>(it) }.getOrNull() }
            ?.takeIf { it.accountId == accountId } ?: WeTrakrSyncSnapshot(accountId)
        checkScope(scope)
        val projection = runCatching { WeTrakrProgressProjection(snapshot) }.getOrNull()
        val usable = if (projection != null) snapshot else WeTrakrSyncSnapshot(accountId)
        mutableProjection.value = WeTrakrProgressState(scope, projection ?: WeTrakrProgressProjection.Empty)
        mutableState.value = WeTrakrSyncState(scope, usable)
        return usable
    }

    private suspend fun commit(scope: WeTrakrAuthScope, snapshot: WeTrakrSyncSnapshot) {
        checkScope(scope)
        val previous = mutableState.value.snapshot
        val projection = if (previous != null && previous.watched === snapshot.watched &&
            previous.playback === snapshot.playback) {
            mutableProjection.value.projection
        } else WeTrakrProgressProjection(snapshot)
        storage.save(scope.profileId, json.encodeToString(snapshot)) { checkScope(scope) }
        checkScope(scope)
        mutableProjection.value = WeTrakrProgressState(scope, projection)
        mutableState.value = WeTrakrSyncState(scope, snapshot)
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

    private fun checkScope(scope: WeTrakrAuthScope, requireAuthorization: Boolean = true) {
        if (!matchesCurrent(scope)) throw CancellationException("WeTrakr account changed")
        if (requireAuthorization && !auth.state.value.isAuthenticated) {
            throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
        }
    }

    private fun matchesCurrent(scope: WeTrakrAuthScope?): Boolean =
        scope != null && scope.profileId == activeProfileId.value && auth.isCurrent(scope)

    private fun clearPublished() {
        mutableProjection.value = WeTrakrProgressState()
        mutableState.value = WeTrakrSyncState()
    }

    companion object {
        const val AUTOMATIC_INTERVAL_MS = 15L * 60 * 1_000
        const val ERROR_RETRY_MS = 5L * 60 * 1_000
    }
}
