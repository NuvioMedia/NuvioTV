package com.nuvio.tv.core.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response

/**
 * Largest request body, as declared by Content-Length, that the config servers accept (8 MiB).
 * Real payloads are small JSON documents, so this is far above anything legitimate.
 */
internal const val MAX_REQUEST_BODY_BYTES = 8L * 1024 * 1024

/**
 * True when the request declares a body larger than [maxBytes].
 *
 * The check only looks at the header, so it runs before any body is read or allocated. Without it,
 * a client on the local network could send a huge Content-Length and make the app allocate that
 * much memory (an OutOfMemoryError on the server thread takes the whole app down).
 * A missing or malformed header is not rejected here; the handlers deal with those as before.
 */
internal fun isRequestBodyTooLarge(
    headers: Map<String, String>?,
    maxBytes: Long = MAX_REQUEST_BODY_BYTES
): Boolean {
    val declared = headers?.get("content-length")?.trim()?.toLongOrNull() ?: return false
    return declared > maxBytes
}

/** 413 response that also closes the connection, since the oversized body is left unread. */
internal fun payloadTooLargeResponse(): Response =
    NanoHTTPD.newFixedLengthResponse(
        Response.Status.PAYLOAD_TOO_LARGE,
        NanoHTTPD.MIME_PLAINTEXT,
        "Request body too large"
    ).also { it.closeConnection(true) }
