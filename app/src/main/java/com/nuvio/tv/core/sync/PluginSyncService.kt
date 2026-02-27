package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.data.remote.supabase.SupabasePlugin
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PluginSyncService"

@Singleton
class PluginSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val pluginDataStore: PluginDataStore,
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

    /**
     * Push local plugin repository URLs to Supabase via RPC.
     * Uses a SECURITY DEFINER function to handle RLS for linked devices.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val activeProfile = profileManager.activeProfile
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "pushToRemote: activeProfile=${activeProfile?.id} isPrimary=${activeProfile?.isPrimary} usesPrimaryPlugins=${activeProfile?.usesPrimaryPlugins} profileId=$profileId")

            if (activeProfile != null && !activeProfile.isPrimary && activeProfile.usesPrimaryPlugins) {
                Log.d(TAG, "Profile ${activeProfile.id} uses primary plugins, skipping push")
                return@withContext Result.success(Unit)
            }

            val localRepos = pluginDataStore.repositories.first()
            Log.d(TAG, "pushToRemote: localRepos count=${localRepos.size} for profile $profileId")

            val params = buildJsonObject {
                put("p_plugins", buildJsonArray {
                    localRepos.forEachIndexed { index, repo ->
                        addJsonObject {
                            put("url", repo.url)
                            put("name", repo.name)
                            put("enabled", repo.enabled)
                            put("sort_order", index)
                        }
                    }
                })
                put("p_profile_id", profileId)
            }
            Log.d(TAG, "pushToRemote: calling RPC sync_push_plugins with profile_id=$profileId")
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_plugins", params)
            }

            Log.d(TAG, "Pushed ${localRepos.size} plugin repos to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push plugins to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteRepoUrls(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(
                    IllegalStateException("Unable to resolve sync owner for plugin sync")
                )

            val activeProfile = profileManager.activeProfile
            val profileId = if (activeProfile != null && !activeProfile.isPrimary && activeProfile.usesPrimaryPlugins) 1
                            else profileManager.activeProfileId.value

            val remotePlugins = withJwtRefreshRetry {
                postgrest.from("plugins")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", profileId)
                    } }
                    .decodeList<SupabasePlugin>()
            }

            Result.success(
                remotePlugins
                .sortedBy { it.sortOrder }
                .map { it.url }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote repo URLs", e)
            Result.failure(e)
        }
    }
}
