package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.remote.supabase.SupabaseWatchedItem
import com.nuvio.tv.domain.model.WatchedItem
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

@Singleton
class WatchedItemsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val profileManager: ProfileManager
) {
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
        return !(hasEffectiveTraktConnection && source == WatchProgressSource.TRAKT)
    }

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!shouldUseSupabaseWatchProgressSync()) {
                Log.d(TAG, "Using Trakt watch progress, skipping watched items push")
                return@withContext Result.success(Unit)
            }

            val items = watchedItemsPreferences.getAllItems()
            Log.d(TAG, "pushToRemote: ${items.size} watched items to push")

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
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watched items to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<List<WatchedItem>> = withContext(Dispatchers.IO) {
        try {
            if (!shouldUseSupabaseWatchProgressSync()) {
                Log.d(TAG, "Using Trakt watch progress, skipping watched items pull")
                return@withContext Result.success(emptyList())
            }

            val profileId = profileManager.activeProfileId.value
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

    suspend fun deleteFromRemote(
        contentId: String,
        season: Int?,
        episode: Int?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!shouldUseSupabaseWatchProgressSync()) {
                return@withContext Result.success(Unit)
            }

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
            if (!shouldUseSupabaseWatchProgressSync()) {
                return@withContext Result.success(Unit)
            }
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
