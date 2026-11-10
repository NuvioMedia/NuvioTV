package com.nuvio.tv.data.simkl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class SimklConnectionMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED
}

enum class SimklAuthError {
    MISSING_CLIENT_ID,
    INVALID_CLIENT,
    INVALID_PIN_RESPONSE,
    PIN_EXPIRED,
    PIN_INVALIDATED,
    AUTHORIZATION_REVOKED,
    REAUTHORIZATION_REQUIRED,
    NETWORK
}

@Serializable
data class SimklPinSession(
    val userCode: String,
    val verificationUri: String,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int,
    /** AUTH V2 link with the code pre-filled; rendered as a QR code on the TV. */
    val verificationUriComplete: String? = null
) {
    /** What the QR code should open: the pre-filled link when Simkl provided one. */
    val qrUri: String
        get() = verificationUriComplete?.takeIf(String::isNotBlank) ?: verificationUri
}

/** Tokens returned by `POST /oauth2/token` (AUTH V2). */
data class SimklTokenGrant(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAtEpochMs: Long?,
    val scope: String?
) {
    val canWrite: Boolean
        get() = scope?.split(' ')?.any { it == SIMKL_SCOPE_WRITE } == true

    override fun toString(): String =
        "SimklTokenGrant(accessTokenExpiresAtEpochMs=$accessTokenExpiresAtEpochMs, scope=$scope)"
}

const val SIMKL_SCOPE_READ = "media:read"
const val SIMKL_SCOPE_WRITE = "media:write"
const val SIMKL_REQUESTED_SCOPE = "$SIMKL_SCOPE_READ $SIMKL_SCOPE_WRITE"
const val SIMKL_DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

data class SimklAuthState(
    val isAuthenticated: Boolean = false,
    val username: String? = null,
    val accountId: Long? = null,
    val hasFetchedUserSettings: Boolean = false,
    val settingsActivityWatermark: String? = null,
    val pinSession: SimklPinSession? = null,
    val error: SimklAuthError? = null
) {
    val mode: SimklConnectionMode
        get() = when {
            isAuthenticated -> SimklConnectionMode.CONNECTED
            pinSession != null -> SimklConnectionMode.AWAITING_APPROVAL
            else -> SimklConnectionMode.DISCONNECTED
        }
}

sealed interface SimklPinPollResult {
    data object Pending : SimklPinPollResult
    data object SlowDown : SimklPinPollResult
    data object Authorized : SimklPinPollResult
    data object Expired : SimklPinPollResult
    data object Invalidated : SimklPinPollResult
}

class SimklAuthException(val error: SimklAuthError, cause: Throwable? = null) : Exception(error.name, cause)

@Serializable
internal data class SimklStoredAuthMetadata(
    val username: String? = null,
    val accountId: Long? = null,
    val hasFetchedUserSettings: Boolean = false,
    val settingsActivityWatermark: String? = null,
    val pinSession: SimklPinSession? = null,
    val accessTokenExpiresAtEpochMs: Long? = null,
    val tokenScope: String? = null
)

internal enum class SimklSettingsRefreshAction {
    NONE,
    RECORD_WATERMARK,
    FETCH
}

internal fun simklSettingsRefreshAction(
    state: SimklAuthState,
    activityWatermark: String?
): SimklSettingsRefreshAction = when {
    activityWatermark.isNullOrBlank() -> SimklSettingsRefreshAction.NONE
    activityWatermark == state.settingsActivityWatermark -> SimklSettingsRefreshAction.NONE
    state.settingsActivityWatermark == null && state.hasFetchedUserSettings -> {
        SimklSettingsRefreshAction.RECORD_WATERMARK
    }
    else -> SimklSettingsRefreshAction.FETCH
}

/** Response of `POST /oauth2/device` (RFC 8628 device authorization). */
@Serializable
internal data class SimklDeviceAuthorizationResponse(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int? = null
)

/** Response of `POST /oauth2/token` for every AUTH V2 grant. */
@Serializable
internal data class SimklTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null
)

/**
 * Builds a [SimklTokenGrant] from a token response. Refresh is non-rotating, so a refresh
 * response may repeat the refresh token; [fallbackRefreshToken] covers a response that omits it.
 */
internal fun SimklTokenResponse.toGrant(
    nowEpochMs: Long,
    fallbackRefreshToken: String? = null
): SimklTokenGrant? {
    val access = accessToken?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (tokenType != null && !tokenType.equals("bearer", ignoreCase = true)) return null
    val refresh = refreshToken?.trim()?.takeIf(String::isNotBlank)
        ?: fallbackRefreshToken?.trim()?.takeIf(String::isNotBlank)
        ?: return null
    return SimklTokenGrant(
        accessToken = access,
        refreshToken = refresh,
        accessTokenExpiresAtEpochMs = expiresIn?.takeIf { it > 0L }?.let { nowEpochMs + it * 1_000L },
        scope = scope?.trim()?.takeIf(String::isNotBlank)
    )
}

@Serializable
internal data class SimklUserSettingsResponse(
    val user: SimklUser? = null,
    val account: SimklAccount? = null
)

@Serializable
internal data class SimklUser(val name: String? = null)

@Serializable
internal data class SimklAccount(val id: Long? = null)
