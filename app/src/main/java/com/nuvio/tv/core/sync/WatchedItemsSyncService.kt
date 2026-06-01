package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.remote.supabase.SupabaseWatchedItem
import com.nuvio.tv.data.remote.supabase.SupabaseWatchedItemEvent
import com.nuvio.tv.domain.model.WatchedItem
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val profileManager: ProfileManager
) {
    private val deltaSyncMutex = Mutex()

    /**
     * Timestamp of the last successful push to remote.
     * Used to protect local items created after this point from being
     * removed during pull (they haven't reached remote yet).
     */
    @Volatile
    var lastSuccessfulPushMs: Long = 0L
        private set

    fun markPushSucceeded() {
        val now = System.currentTimeMillis()
        lastSuccessfulPushMs = now
        CoroutineScope(Dispatchers.IO).launch {
            watchedItemsPreferences.setLastSuccessfulPushMs(now)
        }
    }

    suspend fun restoreLastPushTimestamp() {
        lastSuccessfulPushMs = watchedItemsPreferences.getLastSuccessfulPushMs()
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
        val hasEffectiveTraktConnection = traktAuthDataStore.isEffectivelyAuthenticated.first()
        val source = traktSettingsDataStore.watchProgressSource.first()
        val shouldUseSupabase = !(hasEffectiveTraktConnection && source == WatchProgressSource.TRAKT)
        Log.d(TAG, "shouldUseSupabaseWatchProgressSync: traktConnected=$hasEffectiveTraktConnection source=$source shouldUseSupabase=$shouldUseSupabase")
        return shouldUseSupabase
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

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val items = watchedItemsPreferences.getAllItems()
            Log.d(TAG, "pushToRemote: ${items.size} watched items to push")
            pushItemsToRemote(items, updateLastSuccessfulPush = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watched items to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pushItemsToRemote(
        items: Collection<WatchedItem>,
        updateLastSuccessfulPush: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (items.isEmpty()) return@withContext Result.success(Unit)
            Log.d(TAG, "pushItemsToRemote: ${items.size} watched items to push")
            val profileId = profileManager.activeProfileId.value
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
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_watched_items", params)
            }

            Log.d(TAG, "Pushed ${items.size} watched items to remote for profile $profileId")
            if (updateLastSuccessfulPush) {
                markPushSucceeded()
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
                Log.d(TAG, "Using Trakt watch progress, skipping watched items pull")
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

    private suspend fun syncDeltaFromRemoteLocked(
        profileId: Int
    ): Result<WatchedItemsRemoteSyncResult> {
        return try {
            val deltaInitialized = watchedItemsPreferences.isDeltaInitialized(profileId)
            val deltaCursor = watchedItemsPreferences.getDeltaCursor(profileId)
            val localCount = watchedItemsPreferences.getAllItems().size
            Log.d(
                TAG,
                "syncDeltaFromRemote: start profile=$profileId localCount=$localCount deltaInitialized=$deltaInitialized cursor=$deltaCursor lastPush=$lastSuccessfulPushMs"
            )
            if (!shouldUseSupabaseWatchProgressSync()) {
                Log.d(TAG, "Using Trakt watch progress, skipping watched items delta pull")
                return Result.success(WatchedItemsRemoteSyncResult(0, 0, usedSnapshot = false, preservedLocalItems = false))
            }

            if (!deltaInitialized) {
                Log.d(TAG, "syncDeltaFromRemote: delta not initialized, taking one full snapshot for profile $profileId")
                val cursorBeforeSnapshot = fetchDeltaCursor(profileId)
                val remoteWatchedItems = pullFromRemote(profileId).getOrElse { throw it }
                Log.d(TAG, "syncDeltaFromRemote: snapshot returned ${remoteWatchedItems.size} watched items for profile $profileId")
                val hadUnsyncedItems = watchedItemsPreferences.replaceWithRemoteItems(
                    remoteWatchedItems,
                    lastSuccessfulPushMs = lastSuccessfulPushMs,
                    profileId = profileId
                )
                watchedItemsPreferences.setDeltaState(cursorBeforeSnapshot, initialized = true, profileId = profileId)
                val finalLocalCount = watchedItemsPreferences.getAllItems().size
                Log.d(TAG, "syncDeltaFromRemote: initialized cursor $cursorBeforeSnapshot with ${remoteWatchedItems.size} snapshot items for profile $profileId finalLocalCount=$finalLocalCount preservedLocal=$hadUnsyncedItems")
                return Result.success(
                    WatchedItemsRemoteSyncResult(
                        upsertedItems = remoteWatchedItems.size,
                        deletedItems = 0,
                        usedSnapshot = true,
                        preservedLocalItems = hadUnsyncedItems
                    )
                )
            }

            var cursor = watchedItemsPreferences.getDeltaCursor(profileId)
            var totalUpserts = 0
            var totalDeletes = 0
            var page = 1

            while (true) {
                Log.d(TAG, "syncDeltaFromRemote: pulling delta page $page from cursor $cursor for profile $profileId")
                val events = pullDeltaPage(profileId, cursor)
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

            val finalLocalCount = watchedItemsPreferences.getAllItems().size
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

    suspend fun deleteFromRemote(
        contentId: String,
        season: Int?,
        episode: Int?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_keys", buildJsonArray {
                    addJsonObject {
                        put("content_id", contentId)
                        if (season != null) put("season", season)
                        if (episode != null) put("episode", episode)
                    }
                })
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
        episodes: List<Pair<Int, Int>>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (episodes.isEmpty()) return@withContext Result.success(Unit)

            val profileId = profileManager.activeProfileId.value
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
