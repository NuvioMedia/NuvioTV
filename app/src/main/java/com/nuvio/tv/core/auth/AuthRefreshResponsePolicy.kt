package com.nuvio.tv.core.auth

import java.io.IOException

internal class TransientAuthRefreshException(statusCode: Int) :
    IOException("Transient Supabase auth refresh response: HTTP $statusCode")

internal fun shouldRetryAuthRefreshResponse(
    statusCode: Int,
    path: String,
    grantType: String?,
    server: String?,
    cloudflareRay: String?
): Boolean {
    if (!path.endsWith("/auth/v1/token") || grantType != "refresh_token") return false
    if (statusCode == 408 || statusCode == 429) return true
    return statusCode == 403 && (
        !cloudflareRay.isNullOrBlank() ||
            server?.contains("cloudflare", ignoreCase = true) == true
        )
}

internal fun isInvalidAuthRefreshResponse(statusCode: Int, responseBody: String): Boolean {
    if (statusCode !in setOf(400, 401, 403)) return false
    val normalizedBody = responseBody.lowercase()
    return INVALID_AUTH_SESSION_MARKERS.any(normalizedBody::contains)
}

private val INVALID_AUTH_SESSION_MARKERS = listOf(
    "invalid refresh token",
    "refresh token is not valid",
    "refresh token not found",
    "refresh_token_not_found",
    "invalid_grant",
    "session not found",
    "session_not_found",
    "invalid session"
)
