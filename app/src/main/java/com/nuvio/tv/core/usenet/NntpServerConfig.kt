package com.nuvio.tv.core.usenet

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

enum class NntpTransport(val scheme: String, val defaultPort: Int) {
    PLAIN("nntp", 119),
    TLS("nntps", 563)
}

/** Parsed NNTP endpoint. [toString] is deliberately credential-free. */
data class NntpServerConfig(
    val transport: NntpTransport,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val connections: Int
) {
    val redactedUri: String
        get() = "${transport.scheme}://${formatHost(host)}:$port/$connections"

    override fun toString(): String =
        "NntpServerConfig(uri=$redactedUri)"

    companion object {
        private const val DEFAULT_CONNECTIONS = 1
        private const val MAX_CONNECTIONS = 100

        fun parse(value: String): NntpServerConfig {
            val rawValue = value.trim()
            require(rawValue.isNotEmpty()) { "NNTP server URI is blank" }

            val uri = try {
                URI(rawValue)
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid NNTP server URI", error)
            }
            val transport = when (uri.scheme?.lowercase()) {
                NntpTransport.PLAIN.scheme -> NntpTransport.PLAIN
                NntpTransport.TLS.scheme -> NntpTransport.TLS
                else -> throw IllegalArgumentException("Unsupported NNTP scheme")
            }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "NNTP server URI must not contain a query or fragment"
            }

            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("NNTP server URI has no host")
            val port = uri.port.takeIf { it >= 0 } ?: transport.defaultPort
            require(port in 1..65535) { "NNTP server port is out of range" }

            val (username, password) = parseUserInfo(uri.rawUserInfo)
            val connections = parseConnections(uri.rawPath)
            return NntpServerConfig(
                transport = transport,
                host = host,
                port = port,
                username = username,
                password = password,
                connections = connections
            )
        }

        private fun parseUserInfo(rawUserInfo: String?): Pair<String?, String?> {
            if (rawUserInfo == null) return null to null
            val separator = rawUserInfo.indexOf(':')
            val rawUsername = if (separator >= 0) rawUserInfo.substring(0, separator) else rawUserInfo
            val rawPassword = if (separator >= 0) rawUserInfo.substring(separator + 1) else null
            val username = decodeUriComponent(rawUsername)
            require(username.isNotBlank()) { "NNTP username is blank" }
            return username to rawPassword?.let(::decodeUriComponent)
        }

        private fun parseConnections(rawPath: String?): Int {
            val path = rawPath.orEmpty().trim('/')
            if (path.isEmpty()) return DEFAULT_CONNECTIONS
            require('/' !in path) { "NNTP server URI has an invalid connection path" }
            val connections = path.toIntOrNull()
                ?: throw IllegalArgumentException("NNTP connection count is invalid")
            require(connections in 1..MAX_CONNECTIONS) {
                "NNTP connection count must be between 1 and $MAX_CONNECTIONS"
            }
            return connections
        }

        private fun decodeUriComponent(value: String): String = try {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (error: Exception) {
            throw IllegalArgumentException("NNTP credentials contain invalid escaping", error)
        }

        private fun formatHost(host: String): String =
            if (':' in host && !host.startsWith('[')) "[$host]" else host
    }
}
