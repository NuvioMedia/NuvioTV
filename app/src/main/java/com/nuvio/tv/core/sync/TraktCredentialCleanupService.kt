package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TRAKT_PROVIDER = "trakt"
private const val TRAKT_CREDENTIAL_TAG = "TraktCredentialCleanup"

@Singleton
class TraktCredentialCleanupService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity
) {
    private val mutex = Mutex()

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun deleteRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!authManager.isAuthenticated) {
                    return@withLock Result.success(Unit)
                }

                val profileId = profileManager.activeProfileId.value
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_provider", TRAKT_PROVIDER)
                    putSyncOriginClientId(syncClientIdentity)
                }

                withJwtRefreshRetry {
                    postgrest.rpc("sync_delete_provider_credentials", params)
                }

                Log.d(TRAKT_CREDENTIAL_TAG, "Deleted remote Trakt credentials for profile $profileId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TRAKT_CREDENTIAL_TAG, "Failed to delete remote Trakt credentials", e)
                Result.failure(e)
            }
        }
    }
}
