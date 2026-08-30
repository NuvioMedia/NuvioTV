package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.remote.supabase.SupabaseWatchedItem
import com.nuvio.tv.data.remote.supabase.SupabaseWatchedItemEvent
import com.nuvio.tv.domain.model.WatchedItem
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchedItemsSyncService"
private const val WATCHED_ITEMS_PAGE_SIZE = 900
private const val WATCHED_ITEMS_DELTA_PAGE_SIZE = 900
private const val WATCHED_ITEM_EVENT_UPSERT = "upsert"
private const val WATCHED_ITEM_EVENT_DELETE = "delete"

data class WatchedItemsRemoteSyncResult(
    val upsertedItems: Int,
    val deletedItems: Int,
    val usedSnapshot: Boolean,
    val preservedLocalItems: Boolean
)

@Singleton
class WatchedItemsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val trackingProviderRegistry: TrackingProgressProviderRegistry,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity
) {
    private val deltaSyncMutex = Mutex()

    /** Serializes full pushes. Two overlapping ones would each claim a sync point for a
     *  payload the other did not contain. */
    private val pushMutex = Mutex()

    /**
     * Read time of the last full push, per profile. Used to protect local entries
     * created after that point: they have not reached remote yet, so their absence
     * from a pull response does not mean they were deleted on another device.
     *
     * Kept per profile because the payload each stamp describes belongs to one
     * profile. A single shared value would let one profile's push vouch for
     * another profile's entries, which is the same misread this whole guard exists
     * to prevent.
     */
    private val syncPoints = ConcurrentHashMap<Int, Long>()

    private fun syncPointFor(profileId: Int): Long = syncPoints[profileId] ?: 0L

    /**
     * @param syncPointMs when the pushed items were read, not when the upload finished.
     * Anything saved while the upload was in flight is missing from that payload, so
     * stamping the finish time would mark it as already synced and the next pull would
     * delete it for never showing up in the remote response.
     */
    suspend fun markPushSucceeded(profileId: Int, syncPointMs: Long) {
        // A push never moves its own profile's point backwards: one that read older data
        // can still finish last, and its stamp would otherwise retract a newer push's
        // claim. This is about competing pushes only. Restore below is exempt.
        // Both halves are monotonic on their own terms, so this holds without callers
        // being serialized elsewhere: merge compares and writes memory in one atomic
        // step, and the store does the same for the durable copy.
        val advanced = syncPoints.merge(profileId, syncPointMs) { current, candidate ->
            maxOf(current, candidate)
        } ?: syncPointMs
        // Awaited rather than fired off: the stored point is the durable half of a
        // successful push, and losing it to a process death leaves the next start
        // re-claiming ground the push already covered.
        watchedItemsPreferences.advanceLastSuccessfulPushMs(advanced, profileId)
    }

    /**
     * Loads the persisted point for [profileId] into memory.
     *
     * Deliberately an assignment rather than a max. This is the persisted record of what
     * the profile has pushed, and it has to be able to move the in-memory copy down.
     * Profile ids get reused (ProfileManager picks the lowest free one) and deleting a
     * profile clears its stored point, so a recreated profile relies on this to drop the
     * previous occupant's value. A stored zero is not an older boundary, it means this id
     * has no push boundary at all, so a max would let the new profile inherit one it
     * never earned and delete its own unsynced entries on first pull.
     */
    suspend fun restoreLastPushTimestamp(profileId: Int = profileManager.activeProfileId.value) {
        syncPoints[profileId] = watchedItemsPreferences.getLastSuccessfulPushMs(profileId)
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    private suspend fun shouldUseSupabaseWatchProgressSync(): Boolean {
        val source = traktSettingsDataStore.watchProgressSource.first()
        val providerId = source.providerId ?: return true
        return trackingProviderRegistry.provider(providerId)?.isAuthenticated?.first() != true
    }

    private suspend fun fetchDeltaCursor(profileId: Int): Long {
        Log.d(TAG, "fetchDeltaCursor: requesting cursor for profile $profileId")
        val params = buildJsonObject {
            put("p_profile_id", profileId)
        }
        return withJwtRefreshRetry {
            postgrest.rpc("sync_get_watched_items_delta_cursor", params).decodeAs<Long>()
        }.also { cursor ->
            Log.d(TAG, "fetchDeltaCursor: cursor=$cursor for profile $profileId")
        }
    }

    private suspend fun pullDeltaPage(profileId: Int, cursor: Long): List<SupabaseWatchedItemEvent> {
        Log.d(TAG, "pullDeltaPage: requesting events after cursor $cursor for profile $profileId limit=$WATCHED_ITEMS_DELTA_PAGE_SIZE")
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_since_event_id", cursor)
            put("p_limit", WATCHED_ITEMS_DELTA_PAGE_SIZE)
        }
        return withJwtRefreshRetry {
            postgrest.rpc("sync_pull_watched_items_delta", params).decodeList<SupabaseWatchedItemEvent>()
        }.also { events ->
            val firstEvent = events.firstOrNull()?.eventId
            val lastEvent = events.lastOrNull()?.eventId
            val upserts = events.count { it.operation.equals(WATCHED_ITEM_EVENT_UPSERT, ignoreCase = true) }
            val deletes = events.count { it.operation.equals(WATCHED_ITEM_EVENT_DELETE, ignoreCase = true) }
            Log.d(TAG, "pullDeltaPage: received ${events.size} events for profile $profileId first=$firstEvent last=$lastEvent upserts=$upserts deletes=$deletes")
        }
    }

    suspend fun pushToRemote(profileId: Int = profileManager.activeProfileId.value): Result<Unit> = withContext(Dispatchers.IO) {
        pushMutex.withLock {
            try {
                val syncPointMs = System.currentTimeMillis()
                // Read the same profile the sync point is stamped against. Falling back to
                // the active profile here would stamp one profile for another's payload.
                val items = watchedItemsPreferences.getAllItems(profileId)
                Log.d(TAG, "pushToRemote: ${items.size} watched items to push")
                pushItemsToRemote(items = items, profileId = profileId, syncPointMs = syncPointMs)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push watched items to remote", e)
                Result.failure(e)
            }
        }
    }

    /**
     * @param syncPointMs moment [items] were read, when they are the whole local set.
     * Passing it moves the sync point; leaving it null does not, which is what a partial
     * batch has to do since uploading some items says nothing about the rest.
     */
    suspend fun pushItemsToRemote(
        items: Collection<WatchedItem>,
        profileId: Int = profileManager.activeProfileId.value,
        syncPointMs: Long? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (items.isEmpty()) return@withContext Result.success(Unit)
            Log.d(TAG, "pushItemsToRemote: ${items.size} watched items to push")
            val params = buildJsonObject {
                put("p_items", buildJsonArray {
                    items.forEach { item ->
                        addJsonObject {
                            put("content_id", item.contentId)
                            put("content_type", item.contentType)
                            put("title", item.title)
                            if (item.season != null) put("season", item.season)
                            else put("season", JsonPrimitive(null as Int?))
                            if (item.episode != null) put("episode", item.episode)
                            else put("episode", JsonPrimitive(null as Int?))
                            put("watched_at", item.watchedAt)
                        }
                    }
                })
                put("p_profile_id", profileId)
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_watched_items", params)
            }

            Log.d(TAG, "Pushed ${items.size} watched items to remote for profile $profileId")
            if (syncPointMs != null) {
                markPushSucceeded(profileId, syncPointMs)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watched item batch to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<List<WatchedItem>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "pullFromRemote: starting full watched items snapshot for profile $profileId")
            if (!shouldUseSupabaseWatchProgressSync()) {
                Log.d(TAG, "Using tracking provider watch progress, skipping watched items pull")
                return@withContext Result.success(emptyList())
            }
            val allItems = mutableListOf<WatchedItem>()
            var page = 1

            while (true) {
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_page", page)
                    put("p_page_size", WATCHED_ITEMS_PAGE_SIZE)
                }
                val response = withJwtRefreshRetry {
                    postgrest.rpc("sync_pull_watched_items", params)
                }
                val remote = response.decodeList<SupabaseWatchedItem>()

                Log.d(TAG, "pullFromRemote: page $page fetched ${remote.size} watched items for profile $profileId")

                allItems.addAll(remote.map { entry ->
                    WatchedItem(
                        contentId = entry.contentId,
                        contentType = entry.contentType,
                        title = entry.title,
                        season = entry.season,
                        episode = entry.episode,
                        watchedAt = entry.watchedAt
                    )
                })

                if (remote.size < WATCHED_ITEMS_PAGE_SIZE) break
                page++
            }

            Log.d(TAG, "pullFromRemote: fetched ${allItems.size} total watched items from Supabase for profile $profileId")
            Result.success(allItems)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items from remote", e)
            Result.failure(e)
        }
    }

    suspend fun syncDeltaFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<WatchedItemsRemoteSyncResult> = withContext(Dispatchers.IO) {
        deltaSyncMutex.withLock {
            syncDeltaFromRemoteLocked(profileId)
        }
    }

    suspend fun syncSnapshotFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<WatchedItemsRemoteSyncResult> = withContext(Dispatchers.IO) {
        deltaSyncMutex.withLock {
            syncSnapshotFromRemoteLocked(profileId)
        }
    }

    private suspend fun syncSnapshotFromRemoteLocked(
        profileId: Int
    ): Result<WatchedItemsRemoteSyncResult> {
        return try {
            if (!shouldUseSupabaseWatchProgressSync()) {
                Log.d(TAG, "Using tracking provider watch progress, skipping watched items snapshot pull")
                return Result.success(WatchedItemsRemoteSyncResult(0, 0, usedSnapshot = false, preservedLocalItems = false))
            }
            val cursorBeforeSnapshot = try {
                fetchDeltaCursor(profileId)
            } catch (e: Exception) {
                Log.w(TAG, "syncSnapshotFromRemote: delta cursor unavailable, applying snapshot without initialized cursor for profile $profileId", e)
                null
            }
            val result = pullSnapshotFromRemote(profileId, resetDeltaState = cursorBeforeSnapshot == null)
            if (cursorBeforeSnapshot != null) {
                watchedItemsPreferences.setDeltaState(cursorBeforeSnapshot, initialized = true, profileId = profileId)
            }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items snapshot from remote", e)
            Result.failure(e)
        }
    }

    private suspend fun syncDeltaFromRemoteLocked(
        profileId: Int
    ): Result<WatchedItemsRemoteSyncResult> {
        return try {
            val deltaInitialized = watchedItemsPreferences.isDeltaInitialized(profileId)
            val deltaCursor = watchedItemsPreferences.getDeltaCursor(profileId)
            val localCount = watchedItemsPreferences.getAllItems(profileId).size
            Log.d(
                TAG,
                "syncDeltaFromRemote: start profile=$profileId localCount=$localCount deltaInitialized=$deltaInitialized cursor=$deltaCursor lastPush=${syncPointFor(profileId)}"
            )
            if (!shouldUseSupabaseWatchProgressSync()) {
                Log.d(TAG, "Using tracking provider watch progress, skipping watched items delta pull")
                return Result.success(WatchedItemsRemoteSyncResult(0, 0, usedSnapshot = false, preservedLocalItems = false))
            }

            if (!deltaInitialized) {
                Log.d(TAG, "syncDeltaFromRemote: delta not initialized, taking one full snapshot for profile $profileId")
                val cursorBeforeSnapshot = try {
                    fetchDeltaCursor(profileId)
                } catch (e: Exception) {
                    Log.w(TAG, "syncDeltaFromRemote: delta cursor unavailable, falling back to snapshot for profile $profileId", e)
                    val fallbackResult = pullSnapshotFromRemote(profileId, resetDeltaState = true)
                    return Result.success(fallbackResult)
                }
                val snapshotResult = pullSnapshotFromRemote(profileId, resetDeltaState = false)
                watchedItemsPreferences.setDeltaState(cursorBeforeSnapshot, initialized = true, profileId = profileId)
                Log.d(TAG, "syncDeltaFromRemote: initialized cursor $cursorBeforeSnapshot after watched items snapshot for profile $profileId")
                return Result.success(snapshotResult)
            }

            var cursor = watchedItemsPreferences.getDeltaCursor(profileId)
            var totalUpserts = 0
            var totalDeletes = 0
            var page = 1

            while (true) {
                Log.d(TAG, "syncDeltaFromRemote: pulling delta page $page from cursor $cursor for profile $profileId")
                val events = try {
                    pullDeltaPage(profileId, cursor)
                } catch (e: Exception) {
                    Log.w(TAG, "syncDeltaFromRemote: watched items delta pull unavailable, falling back to snapshot for profile $profileId", e)
                    val fallbackResult = pullSnapshotFromRemote(profileId, resetDeltaState = true)
                    return Result.success(fallbackResult)
                }
                if (events.isEmpty()) {
                    Log.d(TAG, "syncDeltaFromRemote: no watched item delta events for profile $profileId at cursor $cursor")
                    break
                }

                val upserts = events
                    .filter { it.operation.equals(WATCHED_ITEM_EVENT_UPSERT, ignoreCase = true) }
                    .map { event ->
                        WatchedItem(
                            contentId = event.contentId,
                            contentType = event.contentType,
                            title = event.title,
                            season = event.season,
                            episode = event.episode,
                            watchedAt = event.watchedAt
                        )
                    }
                val deletes = events
                    .filter { it.operation.equals(WATCHED_ITEM_EVENT_DELETE, ignoreCase = true) }
                    .map { event ->
                        Triple(event.contentId, event.season, event.episode)
                    }

                watchedItemsPreferences.applyRemoteChanges(upserts, deletes, profileId)
                cursor = maxOf(cursor, events.maxOf { it.eventId })
                watchedItemsPreferences.setDeltaState(cursor, initialized = true, profileId = profileId)
                totalUpserts += upserts.size
                totalDeletes += deletes.size
                Log.d(TAG, "syncDeltaFromRemote: applied page $page for profile $profileId newCursor=$cursor pageUpserts=${upserts.size} pageDeletes=${deletes.size}")

                if (events.size < WATCHED_ITEMS_DELTA_PAGE_SIZE) break
                page++
            }

            val finalLocalCount = watchedItemsPreferences.getAllItems(profileId).size
            Log.d(TAG, "syncDeltaFromRemote: finished profile=$profileId appliedUpserts=$totalUpserts appliedDeletes=$totalDeletes cursor=$cursor finalLocalCount=$finalLocalCount")
            Result.success(
                WatchedItemsRemoteSyncResult(
                    upsertedItems = totalUpserts,
                    deletedItems = totalDeletes,
                    usedSnapshot = false,
                    preservedLocalItems = false
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items delta from remote", e)
            Result.failure(e)
        }
    }

    private suspend fun pullSnapshotFromRemote(
        profileId: Int,
        resetDeltaState: Boolean
    ): WatchedItemsRemoteSyncResult {
        val remoteWatchedItems = pullFromRemote(profileId).getOrElse { throw it }
        Log.d(TAG, "pullSnapshotFromRemote: snapshot returned ${remoteWatchedItems.size} watched items for profile $profileId")
        val hadUnsyncedItems = watchedItemsPreferences.replaceWithRemoteItems(
            remoteWatchedItems,
            lastSuccessfulPushMs = syncPointFor(profileId),
            profileId = profileId
        )
        if (resetDeltaState) {
            watchedItemsPreferences.setDeltaState(0L, initialized = false, profileId = profileId)
        }
        val finalLocalCount = watchedItemsPreferences.getAllItems(profileId).size
        Log.d(TAG, "pullSnapshotFromRemote: applied ${remoteWatchedItems.size} snapshot items for profile $profileId finalLocalCount=$finalLocalCount preservedLocal=$hadUnsyncedItems resetDeltaState=$resetDeltaState")
        return WatchedItemsRemoteSyncResult(
            upsertedItems = remoteWatchedItems.size,
            deletedItems = 0,
            usedSnapshot = true,
            preservedLocalItems = hadUnsyncedItems
        )
    }

    suspend fun deleteFromRemote(
        contentId: String,
        season: Int?,
        episode: Int?,
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_keys", buildJsonArray {
                    addJsonObject {
                        put("content_id", contentId)
                        if (season != null) put("season", season)
                        if (episode != null) put("episode", episode)
                    }
                })
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_delete_watched_items", params)
            }

            Log.d(TAG, "Deleted watched item from remote: $contentId s=$season e=$episode for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete watched item from remote", e)
            Result.failure(e)
        }
    }

    suspend fun deleteFromRemoteBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>,
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (episodes.isEmpty()) return@withContext Result.success(Unit)

            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_keys", buildJsonArray {
                    episodes.forEach { (season, episode) ->
                        addJsonObject {
                            put("content_id", contentId)
                            put("season", season)
                            put("episode", episode)
                        }
                    }
                })
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_delete_watched_items", params)
            }

            Log.d(TAG, "Batch deleted ${episodes.size} watched items from remote for $contentId profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch delete watched items from remote", e)
            Result.failure(e)
        }
    }
}
