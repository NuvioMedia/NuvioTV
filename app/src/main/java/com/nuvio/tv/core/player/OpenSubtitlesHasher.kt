package com.nuvio.tv.core.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calculates the OpenSubtitles file hash for a video stream.
 * Algorithm: hash = fileSize + sum of first 64KB longs + sum of last 64KB longs (little-endian)
 * https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes
 */
object OpenSubtitlesHasher {

    private const val CHUNK_SIZE = 65536L // 64 KB
    private const val LONG_SIZE = 8

    data class Result(val hash: String, val fileSize: Long)

    suspend fun compute(url: String, headers: Map<String, String>): Result? =
        withContext(Dispatchers.IO) {
            try {
                val fileSize = getContentLength(url, headers) ?: return@withContext null
                if (fileSize < CHUNK_SIZE * 2) return@withContext null

                var hash = fileSize
                hash += readChunkSum(url, headers, offset = 0, length = CHUNK_SIZE)
                hash += readChunkSum(url, headers, offset = fileSize - CHUNK_SIZE, length = CHUNK_SIZE)

                Result(
                    hash = "%016x".format(hash),
                    fileSize = fileSize
                )
            } catch (_: Exception) {
                null
            }
        }

    private fun getContentLength(url: String, headers: Map<String, String>): Long? {
        val conn = openConnection(url, headers, method = "HEAD")
        return try {
            conn.connect()
            conn.contentLengthLong.takeIf { it > 0 }
        } finally {
            conn.disconnect()
        }
    }

    private fun readChunkSum(url: String, headers: Map<String, String>, offset: Long, length: Long): Long {
        val conn = openConnection(url, headers, method = "GET")
        conn.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
        var sum = 0L
        try {
            conn.connect()
            val stream: InputStream = conn.inputStream
            val buf = ByteArray(LONG_SIZE)
            var remaining = length
            while (remaining >= LONG_SIZE) {
                var read = 0
                while (read < LONG_SIZE) {
                    val n = stream.read(buf, read, LONG_SIZE - read)
                    if (n < 0) break
                    read += n
                }
                if (read < LONG_SIZE) break
                sum += buf.toLongLE()
                remaining -= LONG_SIZE
            }
            stream.close()
        } finally {
            conn.disconnect()
        }
        return sum
    }

    private fun openConnection(url: String, headers: Map<String, String>, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun ByteArray.toLongLE(): Long {
        var v = 0L
        for (i in 0 until LONG_SIZE) v = v or ((this[i].toLong() and 0xFF) shl (i * 8))
        return v
    }
}
