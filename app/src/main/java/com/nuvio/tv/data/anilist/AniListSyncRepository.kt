package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.tracking.TrackingRefreshGate
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.simkl.SimklAnimeIdPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class AniListProjectionState(
    val scope: AniListAuthScope? = null,
    val projection: AniListProjection = AniListProjection.Empty
)

class AniListSyncRepository internal constructor(
    private val storage: AniListSyncStorage,
    private val auth: AniListAuthStore,
    private val api: AniListApiClient,
    private val resolver: AniListIdResolver,
    private val activeProfileId: StateFlow<Int>,
    animeIdPreference: Flow<SimklAnimeIdPreference>,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val mappingMutex = Mutex()
    private val gate = TrackingRefreshGate()
    private val mutableState = MutableStateFlow(AniListSyncState())
    private val mutableProjection = MutableStateFlow(AniListProjectionState())
    @Volatile private var currentPreference = SimklAnimeIdPreference.DEFAULT
    val state: StateFlow<AniListSyncState> = mutableState.asStateFlow()
    internal val projectionState: StateFlow<AniListProjectionState> = mutableProjection.asStateFlow()

    init {
        coroutineScope.launch {
            combine(activeProfileId, auth.state) { profileId, authorization ->
                Triple(profileId, authorization.scope, authorization.isAuthenticated)
            }.distinctUntilChanged().collectLatest {
                if (!matchesCurrent(mutableState.value.scope)) clearPublished()
                try {
                    ensureLoaded()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(auth.scope())) {
                        mutableState.value = AniListSyncState(scope = auth.scope(), error = error.toAniListSyncError())
                    }
                }
            }
        }
        coroutineScope.launch {
            animeIdPreference
                .distinctUntilChanged()
                .collectLatest { preference ->
                    currentPreference = preference
                    val current = mutableProjection.value
                    if (current.scope != null && current.projection.preference != preference) {
                        mutableProjection.value = current.copy(
                            projection = AniListProjection(current.projection.snapshot, preference)
                        )
                        refreshMappingsAsync()
                    }
                }
        }
    }

    fun currentScope(): AniListAuthScope = auth.scope().also { checkScope(it) }

    fun currentSnapshot(): AniListSyncSnapshot? = mutableState.value.takeIf { matchesCurrent(it.scope) }?.snapshot

    internal fun currentProjection(): AniListProjection = mutableProjection.value
        .takeIf { matchesCurrent(it.scope) && auth.state.value.isAuthenticated }
        ?.projection
        ?: AniListProjection.Empty

    suspend fun ensureLoaded() = withContext(Dispatchers.Default) { mutex.withLock { loadCurrent() } }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        coroutineScope.launch {
            try {
                refresh(intent)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    suspend fun refresh(intent: TrackingRefreshIntent): Unit = withContext(Dispatchers.Default) {
        val scope = auth.scope()
        gate.runIfNeeded(scope.generation, shouldRun = {
            auth.state.value.isAuthenticated && matchesCurrent(scope) && shouldRefresh(intent)
        }) {
            mutex.withLock {
                checkScope(scope)
                val attemptedAt = now()
                try {
                    val viewerId = ensureViewer(scope)
                    val previous = loadCurrent() ?: return@withLock
                    mutableState.value = mutableState.value.copy(isLoading = true, error = null, attemptedAtEpochMs = attemptedAt)
                    val entries = api.collection(scope, viewerId)
                    checkScope(scope)
                    val liveIds = entries.mapTo(mutableSetOf(), AniListEntry::mediaId)
                    val next = previous.copy(
                        viewerId = viewerId,
                        entries = entries,
                        dismissed = previous.dismissed.filterKeys(liveIds::contains),
                        checkedAtEpochMs = attemptedAt
                    )
                    commit(scope, next)
                    mutableState.value = mutableState.value.copy(isLoading = false, attemptedAtEpochMs = attemptedAt)
                } catch (error: CancellationException) {
                    if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLoading = false)
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(scope)) {
                        mutableState.value = mutableState.value.copy(
                            scope = scope,
                            isLoading = false,
                            error = error.toAniListSyncError(),
                            retryAtEpochMs = (error as? AniListApiException)?.retryAtEpochMs,
                            attemptedAtEpochMs = attemptedAt
                        )
                    }
                }
            }
        }
        if (matchesCurrent(scope)) resolveMappingsAsync(scope)
        Unit
    }

    internal suspend fun <T> mutate(
        scope: AniListAuthScope,
        block: suspend (AniListSyncSnapshot) -> Pair<AniListSyncSnapshot, T>
    ): T = withContext(Dispatchers.Default) {
        mutex.withLock {
            checkScope(scope)
            val previous = loadCurrent() ?: throw AniListAuthException(AniListAuthError.AUTHORIZATION_REVOKED)
            val (next, result) = block(previous)
            if (next != previous) commit(scope, next)
            result
        }
    }

    internal suspend fun applyEntry(scope: AniListAuthScope, entry: AniListEntry, media: AniListMedia?) {
        mutate(scope) { snapshot ->
            val existing = snapshot.entries.firstOrNull { it.mediaId == entry.mediaId }
            val merged = entry.copy(media = entry.media ?: media ?: existing?.media)
            val entries = if (existing == null) {
                listOf(merged) + snapshot.entries
            } else {
                snapshot.entries.map { if (it.mediaId == entry.mediaId) merged else it }
            }
            snapshot.copy(entries = entries) to Unit
        }
        resolveMappingsAsync(scope)
    }

    internal suspend fun removeEntry(scope: AniListAuthScope, mediaId: Long) {
        mutate(scope) { snapshot ->
            snapshot.copy(entries = snapshot.entries.filterNot { it.mediaId == mediaId }) to Unit
        }
    }

    internal suspend fun dismiss(scope: AniListAuthScope, contentIds: Collection<String>) {
        mutate(scope) { snapshot ->
            val projection = currentProjection()
            val dismissed = snapshot.dismissed.toMutableMap()
            contentIds.forEach { contentId ->
                projection.mediaIdFor(contentId)?.let { mediaId ->
                    projection.entryByMediaId[mediaId]?.let { dismissed[mediaId] = it.updatedAt }
                }
            }
            snapshot.copy(dismissed = dismissed) to Unit
        }
    }

    private suspend fun ensureViewer(scope: AniListAuthScope): Long {
        auth.state.value.viewer?.id?.let { return it }
        val viewer = api.viewer(scope)
        auth.saveViewer(viewer, scope)
        return viewer.id
    }

    private suspend fun loadCurrent(): AniListSyncSnapshot? {
        val scope = auth.scope()
        if (!matchesCurrent(scope)) throw CancellationException("AniList account changed")
        val authorization = auth.state.value
        if (!authorization.isAuthenticated) {
            clearPublished()
            storage.remove(scope.profileId) { if (!matchesCurrent(scope)) throw CancellationException("AniList account changed") }
            return null
        }
        val viewerId = authorization.viewer?.id ?: 0L
        val current = mutableState.value
        if (current.scope == scope && current.snapshot != null &&
            (viewerId == 0L || current.snapshot.viewerId == 0L || current.snapshot.viewerId == viewerId)
        ) {
            return current.snapshot
        }
        val payload = storage.load(scope.profileId)
        val snapshot = payload
            ?.let { runCatching { aniListJson.decodeFromString(AniListSyncSnapshot.serializer(), it) }.getOrNull() }
            ?.takeIf { viewerId == 0L || it.viewerId == viewerId || it.viewerId == 0L }
            ?: AniListSyncSnapshot(viewerId)
        checkScope(scope)
        val projection = runCatching { AniListProjection(snapshot, preference()) }.getOrNull()
        val usable = if (projection != null) snapshot else AniListSyncSnapshot(viewerId)
        mutableProjection.value = AniListProjectionState(scope, projection ?: AniListProjection(usable, preference()))
        mutableState.value = AniListSyncState(scope = scope, snapshot = usable)
        return usable
    }

    private suspend fun commit(scope: AniListAuthScope, snapshot: AniListSyncSnapshot) {
        checkScope(scope)
        val previous = mutableProjection.value
        val projection = if (previous.scope == scope && previous.projection.snapshot.entries === snapshot.entries &&
            previous.projection.snapshot.mappings === snapshot.mappings &&
            previous.projection.snapshot.dismissed === snapshot.dismissed && previous.projection.preference == preference()
        ) {
            previous.projection
        } else {
            AniListProjection(snapshot, preference())
        }
        val payload = aniListJson.encodeToString(AniListSyncSnapshot.serializer(), snapshot)
        storage.save(scope.profileId, payload) { checkScope(scope) }
        checkScope(scope)
        mutableProjection.value = AniListProjectionState(scope, projection)
        mutableState.value = mutableState.value.copy(scope = scope, snapshot = snapshot)
    }

    private fun refreshMappingsAsync() {
        val scope = runCatching { currentScope() }.getOrNull() ?: return
        resolveMappingsAsync(scope)
    }

    private fun resolveMappingsAsync(scope: AniListAuthScope) {
        coroutineScope.launch {
            try {
                resolveMappings(scope)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun resolveMappings(scope: AniListAuthScope) {
        if (!mappingMutex.tryLock()) return
        try {
            repeat(MAX_MAPPING_PASSES) { pass ->
                if (!matchesCurrent(scope)) return
                if (pass > 0) delay(MAPPING_PASS_DELAY_MS)
                if (!resolveMappingPass(scope)) return
            }
        } finally {
            mappingMutex.unlock()
        }
    }

    private suspend fun resolveMappingPass(scope: AniListAuthScope): Boolean {
        val snapshot = currentSnapshot() ?: return false
        val wantSeason = preference().let { it == SimklAnimeIdPreference.IMDB || it == SimklAnimeIdPreference.TVDB }
        val nowMs = now()
        val pending = snapshot.entries
            .filter { it.media != null }
            .filter { entry ->
                val existing = snapshot.mappings[entry.mediaId]
                existing == null || existing.resolvedAtEpochMs <= 0L ||
                    nowMs - existing.resolvedAtEpochMs > AniListIdResolver.MAPPING_TTL_MS ||
                    (wantSeason && entry.media?.isMovie == false && !existing.seasonResolved)
            }
            .sortedWith(
                compareByDescending<AniListEntry> { it.status == AniListListStatus.CURRENT }
                    .thenByDescending { it.status == AniListListStatus.REPEATING }
                    .thenByDescending { it.status == AniListListStatus.PAUSED }
                    .thenByDescending { it.updatedAt }
            )
            .take(MAX_MAPPINGS_PER_PASS)
        if (pending.isEmpty()) return false
        val semaphore = Semaphore(MAPPING_CONCURRENCY)
        val updates = coroutineScope {
            pending.map { entry ->
                async {
                    semaphore.withPermit {
                        val media = entry.media ?: return@withPermit null
                        try {
                            resolver.mapping(media, snapshot.mappings[entry.mediaId], wantSeason)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (updates.isEmpty() || !matchesCurrent(scope)) return false
        mutate(scope) { current ->
            current.copy(mappings = current.mappings + updates.associateBy(AniListMapping::anilist)) to Unit
        }
        return updates.size == pending.size
    }

    private fun preference(): SimklAnimeIdPreference = currentPreference

    private fun shouldRefresh(intent: TrackingRefreshIntent): Boolean {
        val current = mutableState.value.takeIf { matchesCurrent(it.scope) } ?: return true
        val nowMs = now()
        if (current.retryAtEpochMs?.let { it > nowMs } == true) return false
        if (intent != TrackingRefreshIntent.AUTOMATIC) return true
        if (current.error != null) {
            return current.attemptedAtEpochMs?.let { nowMs - it !in 0 until ERROR_RETRY_MS } ?: true
        }
        return current.snapshot?.checkedAtEpochMs?.let { nowMs - it !in 0 until AUTOMATIC_INTERVAL_MS } ?: true
    }

    private fun checkScope(scope: AniListAuthScope) {
        if (!matchesCurrent(scope)) throw CancellationException("AniList account changed")
        if (!auth.state.value.isAuthenticated) throw AniListAuthException(AniListAuthError.AUTHORIZATION_REVOKED)
    }

    private fun matchesCurrent(scope: AniListAuthScope?): Boolean =
        scope != null && scope.profileId == activeProfileId.value && auth.isCurrent(scope)

    private fun clearPublished() {
        mutableProjection.value = AniListProjectionState()
        mutableState.value = AniListSyncState()
    }

    companion object {
        const val AUTOMATIC_INTERVAL_MS = 10L * 60L * 1_000L
        const val ERROR_RETRY_MS = 60_000L
        const val MAX_MAPPINGS_PER_PASS = 40
        const val MAX_MAPPING_PASSES = 30
        const val MAPPING_PASS_DELAY_MS = 2_000L
        const val MAPPING_CONCURRENCY = 3
    }
}
