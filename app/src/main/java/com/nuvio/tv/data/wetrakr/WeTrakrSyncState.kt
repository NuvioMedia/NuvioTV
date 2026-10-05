package com.nuvio.tv.data.wetrakr

enum class WeTrakrSyncError { UNAVAILABLE, INVALID_RESPONSE, RATE_LIMIT, QUOTA_EXCEEDED, AUTHORIZATION_REVOKED }

internal data class WeTrakrProgressState(
    val scope: WeTrakrAuthScope? = null,
    val projection: WeTrakrProgressProjection = WeTrakrProgressProjection.Empty
)

data class WeTrakrSyncState(
    val scope: WeTrakrAuthScope? = null,
    val snapshot: WeTrakrSyncSnapshot? = null,
    val isLoading: Boolean = false,
    val error: WeTrakrSyncError? = null,
    val retryAtEpochMs: Long? = null,
    val attemptedAtEpochMs: Long? = null
) {
    val hasLoaded: Boolean
        get() = snapshot?.isInitialized == true
}

internal fun Throwable.toWeTrakrSyncError(): WeTrakrSyncError = when {
    this is WeTrakrAuthException -> WeTrakrSyncError.AUTHORIZATION_REVOKED
    this is WeTrakrDecodingException -> WeTrakrSyncError.INVALID_RESPONSE
    this is WeTrakrApiException && status == 429 && code == "QUOTA_EXCEEDED" -> WeTrakrSyncError.QUOTA_EXCEEDED
    this is WeTrakrApiException && status == 429 -> WeTrakrSyncError.RATE_LIMIT
    else -> WeTrakrSyncError.UNAVAILABLE
}
