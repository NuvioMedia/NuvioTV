package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Creates and starts a server on the first free port in
 * `startPort until startPort + maxAttempts`, or returns null when every port is taken.
 * [create] builds the (not yet started) server for the given port.
 */
internal fun <T : NanoHTTPD> startServerOnAvailablePort(
    startPort: Int,
    maxAttempts: Int,
    create: (port: Int) -> T
): T? {
    for (port in startPort until startPort + maxAttempts) {
        try {
            val server = create(port)
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            return server
        } catch (_: Exception) {
            // Port in use, try next
        }
    }
    return null
}

/** 200 response carrying [html] as UTF-8 text/html. */
internal fun htmlResponse(html: String): Response =
    NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)

/** 200 response with the PNG [bytes], or 404 when no logo is available. */
internal fun pngLogoResponse(bytes: ByteArray?): Response =
    if (bytes != null) {
        NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
    } else {
        NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found")
    }

/** Reads the request body (as announced by Content-Length) and decodes it as UTF-8. */
internal fun readUtf8Body(session: NanoHTTPD.IHTTPSession): String {
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
