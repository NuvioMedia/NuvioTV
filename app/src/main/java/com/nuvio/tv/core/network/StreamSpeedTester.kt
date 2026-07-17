package com.nuvio.tv.core.network

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Network speed diagnostics for the last-played stream.
 *
 * Intentionally does **not** use [com.nuvio.tv.ui.screens.player.ParallelRangeDataSource]:
 * the playback engine is stricter (short-chunk, moov session, etc.) and would zero out
 * measurements on CDNs that cap range responses. This matches origin/dev behaviour:
 * count every byte successfully read for a fixed window.
 */
@UnstableApi
object StreamSpeedTester {

    private const val TEST_DURATION_MS = 8_000L
    private const val PARALLEL_WORKERS = 3
    private const val READ_BUF = 64 * 1024

    // 1. Measures single connection baseline speed (standard OkHttp)
    suspend fun runBaselineTest(
        url: String,
        headers: Map<String, String>
    ): Double = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var totalBytes = 0L
        val tStart = System.currentTimeMillis()
        val tDeadline = tStart + TEST_DURATION_MS

        try {
            val request = buildRequest(url, headers, range = null)
            PlayerPlaybackNetworking.playbackHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0.0
                val inputStream = response.body?.byteStream() ?: return@withContext 0.0
                val buffer = ByteArray(READ_BUF)
                while (System.currentTimeMillis() < tDeadline) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0.0
        }

        val elapsed = System.currentTimeMillis() - tStart
        if (elapsed > 0) (totalBytes * 8.0) / (elapsed * 1000.0) else 0.0
    }

    // 2. Measures parallel range-GET speed at a specific chunk size (origin/dev-style).
    // Accepts short/partial CDN responses — throughput is still valid for diagnostics.
    suspend fun runParallelChunkTest(
        url: String,
        headers: Map<String, String>,
        chunkSizeBytes: Long
    ): Double = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val chunkSize = chunkSizeBytes.coerceAtLeast(64L * 1024L)
        val contentLength = probeContentLength(url, headers)
        val totalBytes = AtomicLong(0L)
        val tStart = System.currentTimeMillis()
        val tDeadline = tStart + TEST_DURATION_MS
        val stop = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(PARALLEL_WORKERS)

        try {
            repeat(PARALLEL_WORKERS) { worker ->
                executor.execute {
                    var stripe = 0L
                    val buffer = ByteArray(READ_BUF)
                    while (!stop.get() && System.currentTimeMillis() < tDeadline) {
                        val start = (worker + stripe * PARALLEL_WORKERS) * chunkSize
                        if (contentLength > 0L && start >= contentLength) {
                            // Wrap around so the full window stays busy.
                            stripe = 0L
                            continue
                        }
                        val endExclusive = if (contentLength > 0L) {
                            min(start + chunkSize, contentLength)
                        } else {
                            start + chunkSize
                        }
                        if (endExclusive <= start) break
                        val endInclusive = endExclusive - 1L

                        try {
                            val request = buildRequest(
                                url = url,
                                headers = headers,
                                range = "bytes=$start-$endInclusive"
                            )
                            PlayerPlaybackNetworking.playbackHttpClient.newCall(request).execute().use { response ->
                                // 200 or 206 — accept partial bodies (CDN short-range caps).
                                if (!response.isSuccessful && response.code != 206) return@use
                                val inputStream = response.body?.byteStream() ?: return@use
                                while (!stop.get() && System.currentTimeMillis() < tDeadline) {
                                    val read = inputStream.read(buffer)
                                    if (read <= 0) break
                                    totalBytes.addAndGet(read.toLong())
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore per-range failures; other workers keep measuring.
                        }
                        stripe++
                    }
                }
            }
            // Run for the full test window, then stop workers.
            val remaining = (tDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remaining > 0L) {
                try {
                    Thread.sleep(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0.0
        } finally {
            stop.set(true)
            executor.shutdownNow()
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val elapsed = System.currentTimeMillis() - tStart
        if (elapsed > 0) (totalBytes.get() * 8.0) / (elapsed * 1000.0) else 0.0
    }

    suspend fun getStreamContentLength(
        url: String,
        headers: Map<String, String>
    ): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        probeContentLength(url, headers)
    }

    private fun probeContentLength(url: String, headers: Map<String, String>): Long {
        try {
            val head = buildRequest(url, headers, range = null).newBuilder().head().build()
            PlayerPlaybackNetworking.playbackHttpClient.newCall(head).execute().use { response ->
                if (response.isSuccessful) {
                    val len = response.headers["Content-Length"]?.toLongOrNull()
                    if (len != null && len > 0) return len
                }
            }

            // Some CDNs only expose size on a ranged GET.
            val ranged = buildRequest(url, headers, range = "bytes=0-0")
            PlayerPlaybackNetworking.playbackHttpClient.newCall(ranged).execute().use { response ->
                val cr = response.header("Content-Range")
                // bytes 0-0/TOTAL
                if (cr != null) {
                    val total = cr.substringAfter('/', missingDelimiterValue = "")
                        .toLongOrNull()
                    if (total != null && total > 0L) return total
                }
                if (response.isSuccessful) {
                    val len = response.body?.contentLength() ?: -1L
                    if (len > 0L) return len
                }
            }

            val getRequest = buildRequest(url, headers, range = null)
            PlayerPlaybackNetworking.playbackHttpClient.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val len = response.body?.contentLength() ?: -1L
                    if (len > 0L) return len
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return 0L
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        range: String?
    ): Request {
        val builder = Request.Builder().url(url)
        var hasUa = false
        headers.forEach { (k, v) ->
            if (k.equals("Range", ignoreCase = true)) return@forEach
            if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
            builder.header(k, v)
        }
        if (!hasUa) {
            builder.header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        }
        if (range != null) {
            builder.header("Range", range)
        }
        return builder.build()
    }
}
