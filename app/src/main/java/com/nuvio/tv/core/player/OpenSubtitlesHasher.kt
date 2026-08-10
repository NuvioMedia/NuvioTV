package com.nuvio.tv.core.player

import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

/**
 * Calculates the OpenSubtitles file hash for a video stream.
 * Algorithm: hash = fileSize + sum of first 64KB longs + sum of last 64KB longs (little-endian)
 * https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes
 */
object OpenSubtitlesHasher {

    private const val CHUNK_SIZE = 65536L // 64 KB
    private const val LONG_SIZE = 8

    data class Result(val hash: String, val fileSize: Long)

    suspend fun compute(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient = PlayerPlaybackNetworking.createHttpClient(headers)
    ): Result? = withContext(Dispatchers.IO) {
        try {
            val fileSize = getContentLength(url, headers, client) ?: return@withContext null
            if (fileSize < CHUNK_SIZE * 2) return@withContext null

            var hash = fileSize
            hash += readChunkSum(url, headers, offset = 0, length = CHUNK_SIZE, client = client)
            hash += readChunkSum(url, headers, offset = fileSize - CHUNK_SIZE, length = CHUNK_SIZE, client = client)

            Result(
                hash = "%016x".format(hash),
                fileSize = fileSize
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getContentLength(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient
    ): Long? {
        // Query the static probeInfoCache in PlayerMediaSourceFactory to bypass connection if possible
        try {
            val cacheInfo = PlayerMediaSourceFactory.getProbeInfo(url, headers)
            if (cacheInfo != null) {
                // If the stream doesn't accept range requests, we cannot calculate OpenSubtitles hash (which requires reading the end of the file).
                // Abort early to avoid throwing exceptions and wasting network calls.
                if (!cacheInfo.acceptsRanges) {
                    return null
                }
                if (cacheInfo.contentLength > 0L) {
                    return cacheInfo.contentLength
                }
            }
        } catch (_: Exception) {
            // Fallback to active network request if package/class is unresolved in tests or background scenarios
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .head()
        headers.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true)) {
                requestBuilder.header(k, v)
            }
        }
        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val lenStr = response.header("Content-Length")
                val len = lenStr?.toLongOrNull() ?: response.body?.contentLength()
                if (len == null) return null
                if (len > 0) len else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readChunkSum(
        url: String,
        headers: Map<String, String>,
        offset: Long,
        length: Long,
        client: OkHttpClient
    ): Long {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=$offset-${offset + length - 1}")
        headers.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true)) {
                requestBuilder.header(k, v)
            }
        }
        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        }

        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return 0L
            val stream: InputStream = response.body?.byteStream() ?: return 0L
            val buf = ByteArray(LONG_SIZE)
            var sum = 0L
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
            return sum
        }
    }

    private fun ByteArray.toLongLE(): Long {
        var v = 0L
        for (i in 0 until LONG_SIZE) v = v or ((this[i].toLong() and 0xFF) shl (i * 8))
        return v
    }
}
