package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.core.player.SubtitleFonts
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class SubtitleFontUploadServer(
    private val context: Context,
    private val onFontUploaded: (fileName: String, bytes: ByteArray) -> Boolean,
    private val logoProvider: () -> ByteArray?,
    port: Int
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET && session.uri == "/" -> newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            SubtitleFontWebPage.html(context)
        )
        session.method == Method.GET && session.uri == "/logo.png" -> serveLogo()
        session.method == Method.POST && session.uri == "/api/font" -> handleUpload(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }

    private fun serveLogo(): Response {
        val bytes = logoProvider() ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val fileName = session.headers["x-file-name"]
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull() }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        val length = session.headers["content-length"]?.toLongOrNull()
        if (fileName == null || length == null || length <= 0 || length > SubtitleFonts.MAX_BYTES) {
            return jsonResponse(Response.Status.BAD_REQUEST, """{"error":"invalid"}""")
        }
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = session.inputStream.read(bytes, offset, bytes.size - offset)
            if (read <= 0) break
            offset += read
        }
        if (offset != bytes.size || !onFontUploaded(fileName, bytes)) {
            return jsonResponse(Response.Status.BAD_REQUEST, """{"error":"invalid"}""")
        }
        return jsonResponse(Response.Status.OK, """{"status":"added"}""")
    }

    private fun jsonResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    companion object {
        fun startOnAvailablePort(
            context: Context,
            onFontUploaded: (fileName: String, bytes: ByteArray) -> Boolean,
            logoProvider: () -> ByteArray?,
            startPort: Int = 8094,
            maxAttempts: Int = 10
        ): SubtitleFontUploadServer? {
            for (port in startPort until startPort + maxAttempts) {
                val server = SubtitleFontUploadServer(context, onFontUploaded, logoProvider, port)
                if (runCatching { server.start(SOCKET_READ_TIMEOUT, false) }.isSuccess) return server
            }
            return null
        }
    }
}
