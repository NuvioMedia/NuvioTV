package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingListManager
import com.nuvio.tv.core.tracking.TrackingRefreshGate
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class MdbListLibraryService(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository,
    auth: MdbListAuthStore,
    activeProfileId: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val writer = MdbListLibraryWriter(api, sync, now)
    val listManager: TrackingListManager = writer
    private val refreshGate = TrackingRefreshGate()
    private val loadState = MutableStateFlow(LibraryLoadState())
    private val snapshots = combine(sync.state, auth.state, activeProfileId) { state, authorization, profileId ->
        state.snapshot?.library?.takeIf {
            authorization.isAuthenticated && state.scope == authorization.scope && state.scope.profileId == profileId
        }
    }.distinctUntilChanged().onEach { refreshAsync() }
    private val projection = snapshots.map { MdbListLibraryProjection(it ?: MdbListLibrarySnapshot()) }

    val items = projection.map { it.entries }.onStart { refreshAsync() }.distinctUntilChanged()
    val tabs = snapshots.map { it?.tabs().orEmpty() }.onStart { refreshAsync() }.distinctUntilChanged()
    val isRefreshing = combine(loadState, auth.state, activeProfileId) { state, authorization, profileId ->
        state.refreshing && state.scope == authorization.scope && authorization.isAuthenticated && state.scope.profileId == profileId
    }.distinctUntilChanged()

    fun observeMembership(id: String, type: String) = projection.map { it.membership(id, type) }.distinctUntilChanged()

    suspend fun getMembershipSnapshot(input: LibraryEntryInput): ListMembershipSnapshot {
        val scope = sync.currentScope()
        refresh(TrackingRefreshIntent.AUTOMATIC)
        if (sync.currentScope() != scope) throw CancellationException("MDBList account changed")
        val library = sync.currentSnapshot()?.library ?: throw loadState.value.error ?: MdbListDecodingException()
        val target = runCatching { input.mdbListLibraryItem() }.getOrNull()
        return ListMembershipSnapshot(library.tabs().associate { tab ->
            tab.key to library.itemsByList[tab.key].orEmpty().any { item ->
                target?.matches(item) == true || item.type == mdbListLibraryType(input.itemType) && input.itemId in item.media.ids.aliases()
            }
        })
    }

    suspend fun applyMembershipChanges(input: LibraryEntryInput, changes: ListMembershipChanges) {
        val scope = sync.currentScope()
        refresh(TrackingRefreshIntent.AUTOMATIC)
        writer.applyMembershipChanges(scope, input, changes)
    }

    suspend fun refresh(intent: TrackingRefreshIntent) {
        val scope = try {
            sync.currentScope()
        } catch (error: Exception) {
            if (intent != TrackingRefreshIntent.AUTOMATIC) throw error
            return
        }
        refreshGate.runIfNeeded(scope.generation, { shouldRefresh(scope, intent) }) {
            try {
                sync.ensureLoaded()
                if (!shouldRefresh(scope, intent)) return@runIfNeeded
                loadState.value = LibraryLoadState(scope, refreshing = true, attemptedAt = now())
                sync.mutate(scope) { previous ->
                    val library = MdbListLibraryRemote(api, scope).synchronize(previous.library, previous.accountId, now())
                    previous.copy(library = library) to Unit
                }
                loadState.value = LibraryLoadState(scope)
            } catch (error: CancellationException) {
                loadState.value = LibraryLoadState(scope)
                throw error
            } catch (error: Exception) {
                loadState.value = LibraryLoadState(scope, error = error, attemptedAt = now())
            }
        }
        if (intent != TrackingRefreshIntent.AUTOMATIC) loadState.value.takeIf { it.scope == scope }?.error?.let { throw it }
    }

    private fun refreshAsync() {
        coroutineScope.launch { refresh(TrackingRefreshIntent.AUTOMATIC) }
    }

    private fun shouldRefresh(scope: MdbListAuthScope, intent: TrackingRefreshIntent): Boolean {
        if (runCatching { sync.currentScope() }.getOrNull() != scope) return false
        val state = loadState.value.takeIf { it.scope == scope }
        val time = now()
        if ((state?.error as? MdbListApiException)?.retryAtEpochMs?.let { it > time } == true) return false
        if (intent != TrackingRefreshIntent.AUTOMATIC) return true
        if (state?.error != null && time - state.attemptedAt in 0 until MdbListSyncRepository.ERROR_RETRY_MS) return false
        val library = sync.currentSnapshot()?.library ?: return true
        return library.invalidated || library.checkedAtEpochMs?.let { time - it !in 0 until MdbListSyncRepository.AUTOMATIC_INTERVAL_MS } != false
    }

    private data class LibraryLoadState(
        val scope: MdbListAuthScope? = null,
        val refreshing: Boolean = false,
        val error: Exception? = null,
        val attemptedAt: Long = 0
    )
}
