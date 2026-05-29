package com.nuvio.tv.core.debrid

data class DebridDeviceAuthorization(
    val providerId: String,
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val friendlyVerificationUrl: String,
    val intervalSeconds: Int,
    val expiresAt: String?
)

sealed interface DebridDeviceAuthorizationTokenResult {
    data class Authorized(val accessToken: String) : DebridDeviceAuthorizationTokenResult
    data object Pending : DebridDeviceAuthorizationTokenResult
    data object Expired : DebridDeviceAuthorizationTokenResult
    data object Unsupported : DebridDeviceAuthorizationTokenResult
    data class Failed(val message: String?) : DebridDeviceAuthorizationTokenResult
}
