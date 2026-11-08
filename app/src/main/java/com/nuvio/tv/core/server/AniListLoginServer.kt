package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import java.nio.charset.StandardCharsets
import org.json.JSONObject

class AniListLoginServer(
    private val pairingCode: String,
    private val authorizeUrl: String,
    private val onTokenSubmitted: (String) -> AniListLoginResult,
    port: Int
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET && session.uri == "/" -> newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            AniListLoginWebPage.html(authorizeUrl)
        )
        session.method == Method.POST && session.uri == "/api/token" -> handleToken(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }

    private fun handleToken(session: IHTTPSession): Response {
        val body = runCatching { JSONObject(readUtf8Body(session)) }.getOrNull()
        val code = body?.optString("code").orEmpty().trim()
        val token = body?.optString("token").orEmpty().trim()
        if (code != pairingCode) return json(Response.Status.FORBIDDEN, false, "This sign-in link has expired. Scan the QR code on your TV again.")
        if (token.isBlank()) return json(Response.Status.BAD_REQUEST, false, "Paste the token shown by AniList.")
        val result = runCatching { onTokenSubmitted(token) }.getOrElse {
            AniListLoginResult(false, "Could not reach your TV. Try again.")
        }
        return json(if (result.success) Response.Status.OK else Response.Status.BAD_REQUEST, result.success, result.message)
    }

    private fun json(status: Response.Status, success: Boolean, message: String?): Response =
        newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            JSONObject().put("success", success).put("message", message.orEmpty()).toString()
        )

    private fun readUtf8Body(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
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

    companion object {
        private const val MAX_BODY_BYTES = 64 * 1024

        fun startOnAvailablePort(
            pairingCode: String,
            authorizeUrl: String,
            onTokenSubmitted: (String) -> AniListLoginResult,
            startPort: Int = 8095,
            maxAttempts: Int = 10
        ): AniListLoginServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = AniListLoginServer(pairingCode, authorizeUrl, onTokenSubmitted, port)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}

data class AniListLoginResult(
    val success: Boolean,
    val message: String?
)
