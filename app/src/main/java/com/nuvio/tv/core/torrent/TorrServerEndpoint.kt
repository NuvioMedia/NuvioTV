package com.nuvio.tv.core.torrent

/**
 * Normalizes a user-entered TorrServer endpoint.
 *
 * - Blank input -> "" (use the built-in server)
 * - Missing scheme -> "http://" is prepended
 * - Valid http(s) URL with a host -> trailing "/" trimmed and returned
 * - Anything else -> null (invalid)
 */
fun normalizeTorrServerEndpoint(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""

    val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
    val normalized = withScheme.trimEnd('/')

    return try {
        val uri = java.net.URI(normalized)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if ((scheme == "http" || scheme == "https") && !host.isNullOrBlank()) {
            normalized
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}