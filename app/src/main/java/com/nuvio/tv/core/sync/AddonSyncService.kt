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
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity
) {
    fun effectiveAddonProfileId(): Int {
        val activeProfile = profileManager.activeProfile
        return when {
            activeProfile == null -> profileManager.activeProfileId.value
            !activeProfile.isPrimary && activeProfile.usesPrimaryAddons -> 1
            else -> activeProfile.id
        }
    }

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
            val userSetNames = addonPreferences.userSetNames.first()
            val enabledStates = addonPreferences.addonEnabledStates.first()
            Log.d(TAG, "pushToRemote: localUrls count=${localUrls.size} for profile $profileId")

            val params = buildJsonObject {
                put("p_addons", buildJsonArray {
                    localUrls.forEachIndexed { index, url ->
                        val canonicalUrl = canonicalizeUrl(url)
                        addJsonObject {
                            put("url", url)
                            put("sort_order", index)
                            put("enabled", enabledStates[canonicalUrl] ?: true)
                            val name = userSetNames[canonicalUrl] ?: userSetNames[url]
                            if (!name.isNullOrBlank()) {
                                put("name", name)
                            }
                        }
                    }
                })
                put("p_profile_id", profileId)
                putSyncOriginClientId(syncClientIdentity)
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

    suspend fun getRemoteAddonSnapshot(profileId: Int): Result<RemoteAddonSnapshot> =
        withContext(Dispatchers.IO) {
            try {
                val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Unable to resolve sync owner for addon sync")
                    )

                val remoteAddons = withJwtRefreshRetry {
                    postgrest.from("addons")
                        .select { filter {
                            eq("user_id", effectiveUserId)
                            eq("profile_id", profileId)
                        } }
                        .decodeList<SupabaseAddon>()
                }

                Result.success(
                    RemoteAddonSnapshot(
                        profileId = profileId,
                        addons = remoteAddons
                            .sortedBy { it.sortOrder }
                            .map { addon ->
                                RemoteAddonEntry(
                                    url = canonicalizeUrl(addon.url),
                                    name = addon.name?.takeIf { it.isNotBlank() },
                                    enabled = addon.enabled
                                )
                            }
                            .filter { it.url.isNotBlank() }
                            .distinctBy { it.url.lowercase() }
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get remote addons for profile $profileId", e)
                Result.failure(e)
            }
        }

    suspend fun getRemoteAddonUrls(): Result<List<String>> {
        val profileId = effectiveAddonProfileId()
        val snapshot = getRemoteAddonSnapshot(profileId).getOrElse {
            return Result.failure(it)
        }

        return try {
            if (snapshot.addons.isNotEmpty()) {
                addonPreferences.setUserSetNames(
                    snapshot.addons.mapNotNull { addon ->
                        addon.name?.let { addon.url to it }
                    }.toMap()
                )
                addonPreferences.setAddonEnabledStates(
                    snapshot.addons.associate { it.url to it.enabled }
                )
            }
            Result.success(snapshot.addons.map { it.url })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote addon URLs for profile $profileId", e)
            Result.failure(e)
        }
    }

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith("/manifest.json", ignoreCase = true)) {
            path.dropLast("/manifest.json".length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }
}
