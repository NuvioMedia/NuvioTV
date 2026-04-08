package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

class AiKeyConfigServer(
    private val onKeyReceived: (String) -> Unit,
    private val logoProvider: (() -> ByteArray?)? = null,
    port: Int = 8095
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            method == Method.GET && uri == "/" -> serveWebPage()
            method == Method.GET && uri == "/logo.png" -> serveLogo()
            method == Method.POST && uri == "/api/key" -> handleKeySubmit(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html", AiKeyWebPage.getHtml())

    private fun serveLogo(): Response {
        val bytes = logoProvider?.invoke()
        return if (bytes != null) {
            newFixedLengthResponse(
                Response.Status.OK,
                "image/png",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handleKeySubmit(session: IHTTPSession): Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""
        val key = try {
            org.json.JSONObject(body).optString("key", "").trim()
        } catch (e: Exception) {
            ""
        }
        onKeyReceived(key)
        val response = org.json.JSONObject().put("status", "saved")
        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }

    companion object {
        fun startOnAvailablePort(
            onKeyReceived: (String) -> Unit,
            logoProvider: (() -> ByteArray?)? = null,
            startPort: Int = 8095,
            maxAttempts: Int = 10
        ): AiKeyConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = AiKeyConfigServer(onKeyReceived, logoProvider, port)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (e: Exception) {
                    // Port in use, try next
                }
            }
            return null
        }
    }
}
