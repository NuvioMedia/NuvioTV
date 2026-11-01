package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import java.nio.charset.StandardCharsets

/**
 * Shared request checks for the local config servers.
 *
 * These servers listen on every network interface so a phone on the same Wi-Fi can manage the
 * app. Two cheap checks shrink that exposure a lot:
 *
 * - `Host` must be an IP literal or localhost. A DNS-rebinding request arrives with the
 *   attacker's domain name in `Host`, so it is rejected.
 * - A request that carries an `Origin` header must come from this server's own origin.
 *   Browsers send `Origin` on cross-site POSTs, which stops any web page the user visits from
 *   changing settings here.
 *
 * This is not authentication: a device on the LAN that speaks HTTP directly can still post.
 */
internal object ConfigServerGuard {

    /** Settings payloads are a few KB; anything larger is not one of ours. */
    const val MAX_BODY_BYTES = 512 * 1024

    /** Returns the response to send when the request must be refused, or null to continue. */
    fun rejection(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val hostHeader = session.headers["host"].orEmpty().trim()
        if (hostHeader.isNotEmpty() && !isAcceptedHostHeader(hostHeader)) {
            return rejectionResponse("Unexpected Host header")
        }
        if (session.method != NanoHTTPD.Method.GET && isCrossOrigin(session, hostHeader)) {
            return rejectionResponse("Cross-origin request rejected")
        }
        val declaredLength = session.headers["content-length"]?.trim()?.toIntOrNull()
        if (declaredLength != null && declaredLength > MAX_BODY_BYTES) {
            return rejectionResponse("Request body too large")
        }
        return null
    }

    /**
     * Reads the request body. Never allocates more than [MAX_BODY_BYTES]: the length comes from
     * a client-supplied header, so a reader without a cap is a one-request out-of-memory crash.
     */
    fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val length = session.headers["content-length"]?.trim()?.toIntOrNull() ?: return ""
        if (length <= 0 || length > MAX_BODY_BYTES) return ""
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(buffer, 0, offset, StandardCharsets.UTF_8)
    }

    private fun rejectionResponse(message: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.FORBIDDEN,
            "text/plain; charset=utf-8",
            message
        )

    private fun isCrossOrigin(session: NanoHTTPD.IHTTPSession, hostHeader: String): Boolean {
        val origin = session.headers["origin"]?.trim().orEmpty()
        if (origin.isEmpty()) return false
        // "null" is an opaque origin (sandboxed iframe, file:// page): always foreign.
        if (origin.equals("null", ignoreCase = true)) return true
        val authority = origin.substringAfter("://", "").trimEnd('/')
        return !authority.equals(hostHeader, ignoreCase = true)
    }

    private fun isAcceptedHostHeader(header: String): Boolean {
        val bracketed = header.startsWith("[")
        val host = if (bracketed) {
            header.substringAfter('[').substringBefore(']')
        } else {
            header.substringBefore(':')
        }
        if (host.equals("localhost", ignoreCase = true)) return true
        if (host.equals("::1", ignoreCase = true)) return true
        return if (bracketed) host.contains(':') else isIpv4Literal(host)
    }

    private fun isIpv4Literal(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.all { it.isDigit() } && part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
