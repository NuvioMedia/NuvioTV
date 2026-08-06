package com.nuvio.tv.data.simkl

import kotlinx.coroutines.flow.StateFlow

data class SimklAuthScope(
    val profileId: Int,
    val generation: Long
)

data class SimklAuthorization(
    val scope: SimklAuthScope,
    val accessToken: String
)

interface SimklAuthStorage {
    val state: StateFlow<SimklAuthState>

    fun currentScope(): SimklAuthScope
    fun isCurrent(scope: SimklAuthScope): Boolean
    fun authorization(): SimklAuthorization?
    fun accessToken(): String? = authorization()?.accessToken
    fun savePinSession(session: SimklPinSession, scope: SimklAuthScope = currentScope()): Boolean
    fun clearPinSession(
        error: SimklAuthError? = null,
        scope: SimklAuthScope = currentScope()
    ): Boolean
    fun completePinAuthorization(token: String, scope: SimklAuthScope): Boolean
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
