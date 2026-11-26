package com.nuvio.tv.core.plugin

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

internal const val MAX_FETCH_RESPONSE_BYTES = 2 * 1024 * 1024
internal const val MAX_FETCH_BODY_CHARS = 2 * 1024 * 1024

internal data class BoundedReadResult(
    val bytes: ByteArray,
    val truncated: Boolean
)

internal fun decodeBodyToSafeString(bytes: ByteArray, charset: Charset): String {
    val decoded = try {
        String(bytes, charset)
    } catch (_: Exception) {
        String(bytes, Charsets.UTF_8)
    }
    return truncateString(decoded, MAX_FETCH_BODY_CHARS)
}

internal fun readAtMostBytes(stream: InputStream, maxBytes: Int): BoundedReadResult {
    val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes

    while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }

    val truncated = remaining == 0 && stream.read() != -1
    return BoundedReadResult(out.toByteArray(), truncated)
}
