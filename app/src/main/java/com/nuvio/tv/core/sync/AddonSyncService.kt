package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.supabase.SupabaseAddon
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

private const val TAG = "AddonSyncService"

@Singleton
class AddonSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val addonPreferences: AddonPreferences,
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
     * Push local addon URLs to Supabase via RPC.
     * Uses a SECURITY DEFINER function to handle RLS for linked devices.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val activeProfile = profileManager.activeProfile
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "pushToRemote: activeProfile=${activeProfile?.id} isPrimary=${activeProfile?.isPrimary} usesPrimaryAddons=${activeProfile?.usesPrimaryAddons} profileId=$profileId")

            if (activeProfile != null && !activeProfile.isPrimary && activeProfile.usesPrimaryAddons) {
                Log.d(TAG, "Profile ${activeProfile.id} uses primary addons, skipping push")
                return@withContext Result.success(Unit)
            }

            val localUrls = addonPreferences.installedAddonUrls.first()
            Log.d(TAG, "pushToRemote: localUrls count=${localUrls.size} for profile $profileId")

            val params = buildJsonObject {
                put("p_addons", buildJsonArray {
                    localUrls.forEachIndexed { index, url ->
                        addJsonObject {
                            put("url", url)
                            put("sort_order", index)
                        }
                    }
                })
                put("p_profile_id", profileId)
            }
            Log.d(TAG, "pushToRemote: calling RPC sync_push_addons with profile_id=$profileId")
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_addons", params)
            }

            Log.d(TAG, "Pushed ${localUrls.size} addons to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push addons to remote", e)
            Result.failure(e)
        }
    }

    suspend fun getRemoteAddonUrls(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(
                    IllegalStateException("Unable to resolve sync owner for addon sync")
                )

            val activeProfile = profileManager.activeProfile
            val profileId = if (activeProfile != null && !activeProfile.isPrimary && activeProfile.usesPrimaryAddons) 1
                            else profileManager.activeProfileId.value

            val remoteAddons = withJwtRefreshRetry {
                postgrest.from("addons")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", profileId)
                    } }
                    .decodeList<SupabaseAddon>()
            }

            Result.success(
                remoteAddons
                .sortedBy { it.sortOrder }
                .map { it.url }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote addon URLs", e)
            Result.failure(e)
        }
    }
}
