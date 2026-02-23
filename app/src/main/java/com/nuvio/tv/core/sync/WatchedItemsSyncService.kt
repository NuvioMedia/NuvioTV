package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktAuthDataStore
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

@Singleton
class WatchedItemsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
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

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (traktAuthDataStore.isAuthenticated.first()) {
                Log.d(TAG, "Trakt connected, skipping watched items push")
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
            if (traktAuthDataStore.isAuthenticated.first()) {
                Log.d(TAG, "Trakt connected, skipping watched items pull")
                return@withContext Result.success(emptyList())
            }

            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_profile_id", profileId)
            }
            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_watched_items", params)
            }
            val remote = response.decodeList<SupabaseWatchedItem>()

            Log.d(TAG, "pullFromRemote: fetched ${remote.size} watched items from Supabase for profile $profileId")

            Result.success(remote.map { entry ->
                WatchedItem(
                    contentId = entry.contentId,
                    contentType = entry.contentType,
                    title = entry.title,
                    season = entry.season,
                    episode = entry.episode,
                    watchedAt = entry.watchedAt
                )
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items from remote", e)
            Result.failure(e)
        }
    }
}
