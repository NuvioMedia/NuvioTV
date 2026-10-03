package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.sync.library.LIBRARY_DELTA_PAGE_SIZE
import com.nuvio.tv.core.sync.library.LIBRARY_SNAPSHOT_PAGE_SIZE
import com.nuvio.tv.core.sync.library.LibrarySyncLocalStore
import com.nuvio.tv.core.sync.library.LibrarySyncRemoteDataSource
import com.nuvio.tv.core.sync.library.consumeCursorPages
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.domain.model.LibrarySyncReducer
import com.nuvio.tv.domain.model.LibrarySyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class LibraryRemoteSyncResult(
    val appliedUpserts: Int,
    val appliedDeletes: Int,
    val pushedUpserts: Int,
    val pushedDeletes: Int,
    val usedSnapshot: Boolean,
    val preservedLocalItems: Boolean
)

@Singleton
class LibrarySyncService @Inject constructor(
    private val remoteDataSource: LibrarySyncRemoteDataSource,
    private val localStore: LibrarySyncLocalStore
) {
    private val syncMutex = Mutex()

    suspend fun pushToRemote(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                var state = localStore.getSyncState(profileId)
                if (!state.deltaInitialized) {
                    state = localStore.queueAllItemsForPush(profileId)
                }
                pushPendingMutations(profileId, state)
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to push library mutations for profile $profileId", error)
                Result.failure(error)
            }
        }
    }

    suspend fun syncFromRemote(
        profileId: Int
    ): Result<LibraryRemoteSyncResult> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                var state = localStore.getSyncState(profileId)
                var usedSnapshot = false
                var preservedLocalItems = false
                var appliedUpserts = 0
                var appliedDeletes = 0

                if (!state.deltaInitialized) {
                    val cursorBeforeSnapshot = remoteDataSource.getDeltaCursor(profileId)
                    val remoteItems = remoteDataSource.pullSnapshot(
                        profileId = profileId,
                        pageSize = LIBRARY_SNAPSHOT_PAGE_SIZE
                    )
                    val snapshotResult = localStore.applyRemoteSnapshot(
                        profileId = profileId,
                        remoteItems = remoteItems,
                        cursorEventId = cursorBeforeSnapshot
                    )
                    state = snapshotResult.state
                    usedSnapshot = true
                    preservedLocalItems = snapshotResult.preservedLocalItems
                }

                consumeCursorPages(
                    initialCursor = state.deltaCursorEventId,
                    pageSize = LIBRARY_DELTA_PAGE_SIZE,
                    fetchPage = { cursor, limit ->
                        remoteDataSource.pullDelta(
                            profileId = profileId,
                            sinceEventId = cursor,
                            limit = limit
                        )
                    },
                    applyPage = { events, _ ->
                        val deltaResult = localStore.applyRemoteDelta(
                            profileId = profileId,
                            events = events
                        )
                        appliedUpserts += deltaResult.appliedUpserts
                        appliedDeletes += deltaResult.appliedDeletes
                        deltaResult.state.deltaCursorEventId
                    }
                )

                state = localStore.getSyncState(profileId)
                val pushed = pushPendingMutations(profileId, state)
                val result = LibraryRemoteSyncResult(
                    appliedUpserts = appliedUpserts,
                    appliedDeletes = appliedDeletes,
                    pushedUpserts = pushed.first,
                    pushedDeletes = pushed.second,
                    usedSnapshot = usedSnapshot,
                    preservedLocalItems = preservedLocalItems
                )
                Log.d(
                    TAG,
                    "Library sync completed profile=$profileId snapshot=$usedSnapshot " +
                        "appliedUpserts=$appliedUpserts appliedDeletes=$appliedDeletes " +
                        "pushedUpserts=${pushed.first} pushedDeletes=${pushed.second}"
                )
                Result.success(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to sync library for profile $profileId", error)
                Result.failure(error)
            }
        }
    }

    private suspend fun pushPendingMutations(
        profileId: Int,
        state: LibrarySyncState
    ): Pair<Int, Int> {
        if (!state.hasPendingMutations) return 0 to 0

        val upsertItems = LibrarySyncReducer.pendingUpsertItems(state).filterNot { ServerItemRef.isServerId(it.id) }
        val deleteKeys = state.pendingDeleteKeys.filterNot { ServerItemRef.isServerId(it.contentId) }
        remoteDataSource.pushItems(profileId, upsertItems)
        remoteDataSource.deleteItems(profileId, deleteKeys)
        val acknowledged = localStore.acknowledgePush(
            profileId = profileId,
            expectedMutationRevision = state.mutationRevision
        )
        if (!acknowledged) {
            Log.d(TAG, "Library mutations changed during push for profile $profileId")
        }
        return upsertItems.size to deleteKeys.size
    }

    private companion object {
        const val TAG = "LibrarySyncService"
    }
}
