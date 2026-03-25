package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseProfileLockState
import com.nuvio.tv.data.remote.supabase.SupabaseProfile
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nuvio.tv.domain.model.UserProfile
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSyncService"

@Singleton
class ProfileSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileDataStore: ProfileDataStore,
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
            val profiles = profileManager.profiles.value

            val params = buildJsonObject {
                put("p_profiles", buildJsonArray {
                    profiles.forEach { profile ->
                        addJsonObject {
                            put("profile_index", profile.id)
                            put("name", profile.name)
                            put("avatar_color_hex", profile.avatarColorHex)
                            put("uses_primary_addons", profile.usesPrimaryAddons)
                            put("uses_primary_plugins", profile.usesPrimaryPlugins)
                            put("avatar_id", profile.avatarId)
                        }
                    }
                })
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_profiles", params)
            }

            Log.d(TAG, "Pushed ${profiles.size} profiles to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push profiles to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        try {
            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_profiles")
            }
            val remote = response.decodeList<SupabaseProfile>()

            Log.d(TAG, "pullFromRemote: fetched ${remote.size} profiles from Supabase")

            val profiles = remote.map { entry ->
                UserProfile(
                    id = entry.profileIndex,
                    name = entry.name,
                    avatarColorHex = entry.avatarColorHex,
                    usesPrimaryAddons = entry.usesPrimaryAddons,
                    usesPrimaryPlugins = entry.usesPrimaryPlugins,
                    avatarId = entry.avatarId
                )
            }

            if (profiles.isNotEmpty()) {
                profileDataStore.replaceAllProfiles(profiles)
                Log.d(TAG, "Merged ${profiles.size} remote profiles into local store")
            }

            Result.success(profiles)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull profiles from remote", e)
            Result.failure(e)
        }
    }

    suspend fun deleteProfileData(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_delete_profile_data", params)
            }

            Log.d(TAG, "Deleted remote data for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete remote profile data for profile $profileId", e)
            Result.failure(e)
        }
    }

    suspend fun pullProfileLockStates(): Result<Map<Int, Boolean>> = withContext(Dispatchers.IO) {
        try {
            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_profile_locks")
            }
            val remote = response.decodeList<SupabaseProfileLockState>()
            val result = remote.associate { it.profileIndex to it.pinEnabled }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull profile lock states", e)
            Result.failure(e)
        }
    }

    suspend fun setProfilePin(profileId: Int, pin: String, currentPin: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_pin", pin)
                if (!currentPin.isNullOrBlank()) {
                    put("p_current_pin", currentPin)
                }
            }
            withJwtRefreshRetry {
                postgrest.rpc("set_profile_pin", params)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set profile PIN", e)
            Result.failure(e)
        }
    }

    suspend fun clearProfilePin(profileId: Int, currentPin: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                if (!currentPin.isNullOrBlank()) {
                    put("p_current_pin", currentPin)
                }
            }
            withJwtRefreshRetry {
                postgrest.rpc("clear_profile_pin", params)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear profile PIN", e)
            Result.failure(e)
        }
    }

    suspend fun verifyProfilePin(profileId: Int, pin: String): Result<SupabaseProfilePinVerifyResult> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_pin", pin)
            }
            val response = withJwtRefreshRetry {
                postgrest.rpc("verify_profile_pin", params)
            }
            val decoded = response.decodeList<SupabaseProfilePinVerifyResult>().firstOrNull()
                ?: SupabaseProfilePinVerifyResult(unlocked = false, retryAfterSeconds = 0)
            Result.success(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify profile PIN", e)
            Result.failure(e)
        }
    }
}
