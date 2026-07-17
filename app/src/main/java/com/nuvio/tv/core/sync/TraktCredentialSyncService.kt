package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.local.normalizeTraktTokenLifetimeSeconds
import com.nuvio.tv.data.remote.supabase.SupabaseProviderCredential
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TRAKT_PROVIDER = "trakt"
private const val TRAKT_TOKEN_FALLBACK_LIFETIME_SECONDS = 86_400
private const val TRAKT_CREDENTIAL_TAG = "TraktCredentialSync"

@Singleton
class TraktCredentialSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val traktAuthDataStore: TraktAuthDataStore,
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

    /**
     * Push the current profile's Trakt credentials to the remote.
     * Captures the profile ID and auth state at call-time so both
     * the state and the profile_id sent to the RPC are consistent.
     */
    suspend fun pushCurrentToRemote(): Result<Unit> {
        val profileId = profileManager.activeProfileId.value
        val state = traktAuthDataStore.getCurrentState(profileId)
        return pushStateToRemote(state, profileId)
    }

    /**
     * Push a specific Trakt auth state to the remote for a given profile.
     * @param state The Trakt auth state to push
     * @param profileId The profile to associate the credentials with. Must be
     *   captured at call-time to prevent race conditions on profile switch.
     */
    suspend fun pushStateToRemote(
        state: TraktAuthState,
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!authManager.isAuthenticated || !state.isAuthenticated) {
                    return@withLock Result.success(Unit)
                }

                val credentialJson = state.toCredentialJson() ?: return@withLock Result.success(Unit)
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_credentials", buildJsonArray {
                        addJsonObject {
                            put("provider", TRAKT_PROVIDER)
                            put("credential_json", credentialJson)
                        }
                    })
                    putSyncOriginClientId(syncClientIdentity)
                }

                withJwtRefreshRetry {
                    postgrest.rpc("sync_push_provider_credentials", params)
                }

                Log.d(TRAKT_CREDENTIAL_TAG, "Pushed Trakt credentials for profile $profileId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TRAKT_CREDENTIAL_TAG, "Failed to push Trakt credentials", e)
                Result.failure(e)
            }
        }
    }

    suspend fun pullFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!authManager.isAuthenticated) {
                    return@withLock Result.success(false)
                }

                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                }
                val response = withJwtRefreshRetry {
                    postgrest.rpc("sync_pull_provider_credentials", params)
                }
                val credentials = response.decodeList<SupabaseProviderCredential>()
                val traktCredential = credentials.firstOrNull {
                    it.provider.equals(TRAKT_PROVIDER, ignoreCase = true)
                } ?: return@withLock Result.success(false)

                val remoteState = traktCredential.credentialJson.toTraktAuthState()
                    ?: return@withLock Result.success(false)
                val localState = traktAuthDataStore.getCurrentState(profileId)
                if (localState.syncSignature() == remoteState.syncSignature()) {
                    return@withLock Result.success(false)
                }

                traktAuthDataStore.saveSyncedAuthState(remoteState, profileId = profileId)
                Log.d(TRAKT_CREDENTIAL_TAG, "Pulled Trakt credentials for profile $profileId")
                Result.success(true)
            } catch (e: Exception) {
                Log.e(TRAKT_CREDENTIAL_TAG, "Failed to pull Trakt credentials", e)
                Result.failure(e)
            }
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

private fun TraktAuthState.toCredentialJson(): JsonObject? {
    val accessTokenValue = accessToken?.takeIf { it.isNotBlank() } ?: return null
    val refreshTokenValue = refreshToken?.takeIf { it.isNotBlank() } ?: return null
    val createdAtValue = createdAt ?: (System.currentTimeMillis() / 1000L)
    val expiresInValue = normalizeTraktTokenLifetimeSeconds(
        expiresIn ?: TRAKT_TOKEN_FALLBACK_LIFETIME_SECONDS
    )

    return buildJsonObject {
        put("access_token", accessTokenValue)
        put("refresh_token", refreshTokenValue)
        put("token_type", tokenType ?: "bearer")
        put("created_at", createdAtValue)
        put("expires_in", expiresInValue)
        username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
        userSlug?.takeIf { it.isNotBlank() }?.let { put("user_slug", it) }
    }
}

private fun JsonObject.toTraktAuthState(): TraktAuthState? {
    val accessTokenValue = stringValue("access_token")?.takeIf { it.isNotBlank() } ?: return null
    val refreshTokenValue = stringValue("refresh_token")?.takeIf { it.isNotBlank() } ?: return null
    return TraktAuthState(
        accessToken = accessTokenValue,
        refreshToken = refreshTokenValue,
        tokenType = stringValue("token_type") ?: "bearer",
        createdAt = longValue("created_at") ?: (System.currentTimeMillis() / 1000L),
        expiresIn = normalizeTraktTokenLifetimeSeconds(
            intValue("expires_in") ?: TRAKT_TOKEN_FALLBACK_LIFETIME_SECONDS
        ),
        username = stringValue("username"),
        userSlug = stringValue("user_slug")
    )
}

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.longValue(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.intValue(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

private fun TraktAuthState.syncSignature(): String =
    listOf(
        accessToken.orEmpty(),
        refreshToken.orEmpty(),
        tokenType.orEmpty(),
        createdAt?.toString().orEmpty(),
        expiresIn?.toString().orEmpty(),
        username.orEmpty(),
        userSlug.orEmpty()
    ).joinToString("|")
