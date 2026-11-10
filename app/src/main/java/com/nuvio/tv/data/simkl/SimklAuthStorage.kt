package com.nuvio.tv.data.simkl

import kotlinx.coroutines.flow.StateFlow

data class SimklAuthScope(
    val profileId: Int,
    val generation: Long
)

data class SimklAuthorization(
    val scope: SimklAuthScope,
    val accessToken: String,
    val refreshToken: String? = null,
    val accessTokenExpiresAtEpochMs: Long? = null
) {
    override fun toString(): String =
        "SimklAuthorization(scope=$scope, hasRefreshToken=${refreshToken != null}, " +
            "accessTokenExpiresAtEpochMs=$accessTokenExpiresAtEpochMs)"
}

interface SimklAuthStorage {
    val state: StateFlow<SimklAuthState>

    fun currentScope(): SimklAuthScope
    fun isCurrent(scope: SimklAuthScope): Boolean
    fun authorization(): SimklAuthorization?
    fun accessToken(): String? = authorization()?.accessToken

    /**
     * Persists a pending AUTH V2 device session. [deviceCode] is the polling credential and is
     * kept apart from [session] so it never reaches UI state or logs.
     */
    fun savePinSession(
        session: SimklPinSession,
        deviceCode: String,
        scope: SimklAuthScope = currentScope()
    ): Boolean

    /** Updates the persisted poll interval of the pending session (RFC 8628 `slow_down`). */
    fun updatePinInterval(intervalSeconds: Int, scope: SimklAuthScope = currentScope()): Boolean
    fun pinDeviceCode(): String?
    fun clearPinSession(
        error: SimklAuthError? = null,
        scope: SimklAuthScope = currentScope()
    ): Boolean
    fun completeDeviceAuthorization(grant: SimklTokenGrant, scope: SimklAuthScope): Boolean

    /**
     * Stores refreshed tokens for the grant that [previous] belongs to. Returns the new
     * authorization, or null when the profile or the stored grant changed meanwhile.
     */
    fun saveRefreshedTokens(previous: SimklAuthorization, grant: SimklTokenGrant): SimklAuthorization?
    fun saveIdentity(
        username: String?,
        accountId: Long?,
        settingsActivityWatermark: String? = null,
        scope: SimklAuthScope = currentScope()
    ): Boolean
    fun recordSettingsActivityWatermark(
        watermark: String,
        scope: SimklAuthScope = currentScope()
    ): Boolean
    fun clearAuth(
        error: SimklAuthError? = null,
        scope: SimklAuthScope = currentScope(),
        expectedAccessToken: String? = null
    ): Boolean
    fun removeProfile(profileId: Int)
    fun clearAllProfiles()
}
