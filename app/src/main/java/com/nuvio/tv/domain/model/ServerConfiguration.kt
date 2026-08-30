package com.nuvio.tv.domain.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ServerCapabilities(
    val emailPasswordAuth: Boolean,
    val tvLogin: Boolean
)

data class ServerConfiguration(
    val backendUrl: String,
    val publishableKey: String,
    val capabilities: ServerCapabilities,
    val isCustom: Boolean,
    val discoveryUrl: String? = null,
    val fallbackBackendUrl: String? = null,
    val tvLoginWebBaseUrl: String? = null,
    val deviceLoginWebBaseUrl: String? = null,
    val avatarPublicBaseUrl: String? = null
) {
    val isSecure: Boolean
        get() = backendUrl.startsWith("https://", ignoreCase = true)

    val isPublicHost: Boolean
        get() = isPublicServerHost(backendUrl)
}

internal fun isPublicServerHost(url: String): Boolean {
    val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return true
    if (host == "localhost" || host.endsWith(".local") || host == "::1") return false
    if (host.startsWith("127.")) return false
    if (host.startsWith("10.")) return false
    if (host.startsWith("192.168.")) return false
    val parts = host.split('.')
    if (parts.size == 4) {
        val first = parts[0].toIntOrNull()
        val second = parts[1].toIntOrNull()
        if (first == 172 && second != null && second in 16..31) return false
        if (first == 169 && second == 254) return false
    }
    return true
}
