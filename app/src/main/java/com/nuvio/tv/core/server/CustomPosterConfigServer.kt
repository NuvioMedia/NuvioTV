package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import fi.iki.elonen.NanoHTTPD
import java.nio.charset.StandardCharsets

class CustomPosterConfigServer(
    private val currentPatternProvider: () -> String,
    private val onPatternChanged: (String) -> Unit,
    private val context: Context? = null,
    port: Int = 8092
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/" -> serveWebPage()
            session.method == Method.GET && session.uri == "/api/pattern" -> serveCurrentPattern()
            session.method == Method.POST && session.uri == "/api/pattern" -> handlePatternUpdate(session)
            session.method == Method.POST && session.uri == "/api/clear" -> handleClear()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            CustomPosterWebPage.html(context)
        )

    private fun serveCurrentPattern(): Response {
        val pattern = currentPatternProvider()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            """{"pattern":"${pattern.replace("\"", "\\\"")}"}"""
        )
    }

    private fun handlePatternUpdate(session: IHTTPSession): Response {
        val body = readUtf8Body(session)
        val pattern = extractJsonString(body, "pattern")
        if (pattern == null) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json; charset=utf-8",
                """{"error":"Missing pattern field"}"""
            )
        }
        onPatternChanged(pattern.trim())
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            """{"status":"saved"}"""
        )
    }

    private fun handleClear(): Response {
        onPatternChanged("")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            """{"status":"cleared"}"""
        )
    }

    private fun extractJsonString(json: String, key: String): String? {
        // Simple extraction without pulling in a JSON library
        val keyPattern = "\"$key\""
        val keyIndex = json.indexOf(keyPattern)
        if (keyIndex < 0) return null
        val colonIndex = json.indexOf(':', keyIndex + keyPattern.length)
        if (colonIndex < 0) return null
        val rest = json.substring(colonIndex + 1).trimStart()
        if (rest.isEmpty()) return null
        return if (rest.startsWith("\"")) {
            val endQuote = rest.indexOf('"', 1)
            if (endQuote < 0) null
            else rest.substring(1, endQuote).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            // unquoted value - take until comma or closing brace
            val end = rest.indexOfFirst { it == ',' || it == '}' }
            if (end < 0) rest.trim() else rest.substring(0, end).trim()
        }
    }

    private fun readUtf8Body(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (length <= 0) return ""
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
        fun startOnAvailablePort(
            currentPatternProvider: () -> String,
            onPatternChanged: (String) -> Unit,
            context: Context? = null,
            startPort: Int = 8092,
            maxAttempts: Int = 10
        ): CustomPosterConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = CustomPosterConfigServer(
                        currentPatternProvider = currentPatternProvider,
                        onPatternChanged = onPatternChanged,
                        context = context,
                        port = port
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
