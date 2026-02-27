package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseLibraryItem
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LibrarySyncService"

@Singleton
class LibrarySyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val libraryPreferences: LibraryPreferences,
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
                Log.d(TAG, "Trakt connected, skipping library push")
                return@withContext Result.success(Unit)
            }

            val items = libraryPreferences.getAllItems()
            Log.d(TAG, "pushToRemote: ${items.size} local library items to push")

            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_items", buildJsonArray {
                    items.forEach { item ->
                        addJsonObject {
                            put("content_id", item.id)
                            put("content_type", item.type)
                            put("name", item.name)
                            put("poster", item.poster)
                            put("poster_shape", item.posterShape.name)
                            put("background", item.background)
                            put("description", item.description)
                            put("release_info", item.releaseInfo)
                            item.imdbRating?.let { put("imdb_rating", it.toDouble()) }
                            put("genres", buildJsonArray {
                                item.genres.forEach { genre -> add(kotlinx.serialization.json.JsonPrimitive(genre)) }
                            })
                            put("addon_base_url", item.addonBaseUrl)
                        }
                    }
                })
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_library", params)
            }

            Log.d(TAG, "Pushed ${items.size} library items to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push library to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<List<SavedLibraryItem>> = withContext(Dispatchers.IO) {
        try {
            if (traktAuthDataStore.isAuthenticated.first()) {
                Log.d(TAG, "Trakt connected, skipping library pull")
                return@withContext Result.success(emptyList())
            }

            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_profile_id", profileId)
            }
            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_library", params)
            }
            val remote = response.decodeList<SupabaseLibraryItem>()

            Log.d(TAG, "pullFromRemote: fetched ${remote.size} library items from Supabase for profile $profileId")

            Result.success(remote.map { entry ->
                SavedLibraryItem(
                    id = entry.contentId,
                    type = entry.contentType,
                    name = entry.name,
                    poster = entry.poster,
                    posterShape = runCatching { PosterShape.valueOf(entry.posterShape) }.getOrDefault(PosterShape.POSTER),
                    background = entry.background,
                    description = entry.description,
                    releaseInfo = entry.releaseInfo,
                    imdbRating = entry.imdbRating,
                    genres = entry.genres,
                    addonBaseUrl = entry.addonBaseUrl
                )
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull library from remote", e)
            Result.failure(e)
        }
    }
}
