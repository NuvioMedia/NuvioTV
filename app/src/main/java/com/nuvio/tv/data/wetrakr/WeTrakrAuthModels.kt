package com.nuvio.tv.data.wetrakr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class WeTrakrAuthError {
    MISSING_CLIENT_ID,
    INVALID_CLIENT,
    INVALID_RESPONSE,
    CODE_EXPIRED,
    ACCESS_DENIED,
    TOO_MANY_CODES,
    AUTHORIZATION_REVOKED
}

data class WeTrakrAuthScope(val profileId: Int, val generation: Long)

@Serializable
data class WeTrakrDeviceSession(
    val userCode: String,
    val verificationUrl: String,
    val verificationUrlComplete: String,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int,
    val nextPollAtEpochMs: Long
) {
    override fun toString(): String = "WeTrakrDeviceSession(expiresAtEpochMs=$expiresAtEpochMs)"
}

data class WeTrakrAuthState(
    val scope: WeTrakrAuthScope = WeTrakrAuthScope(1, 0),
    val isAuthenticated: Boolean = false,
    val user: WeTrakrUser? = null,
    val session: WeTrakrDeviceSession? = null,
    val error: WeTrakrAuthError? = null
)

@Serializable
data class WeTrakrUser(
    val id: Long,
    val username: String? = null,
    val displayName: String? = null
)

@Serializable
data class WeTrakrTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long
) {
    override fun toString(): String = "WeTrakrTokens(expiresAtEpochMs=$expiresAtEpochMs)"
}

class WeTrakrAuthorization(val scope: WeTrakrAuthScope, val tokens: WeTrakrTokens) {
    override fun toString(): String = "WeTrakrAuthorization(scope=$scope)"
}

sealed interface WeTrakrDevicePollResult {
    data object Pending : WeTrakrDevicePollResult
    data object Authorized : WeTrakrDevicePollResult
    data object Expired : WeTrakrDevicePollResult
    data object Denied : WeTrakrDevicePollResult
}

class WeTrakrAuthException(val error: WeTrakrAuthError) : Exception(error.name)

@Serializable
internal data class WeTrakrStoredAuth(
    val tokens: WeTrakrTokens? = null,
    val user: WeTrakrUser? = null,
    val session: WeTrakrDeviceSession? = null,
    val deviceCode: String? = null
) {
    override fun toString(): String = "WeTrakrStoredAuth()"
}

@Serializable
internal data class WeTrakrDeviceResponse(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_url") val verificationUrl: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int = 5
)

@Serializable
internal data class WeTrakrTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("new_refresh_token") val newRefreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
)
