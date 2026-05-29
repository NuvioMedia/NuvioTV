package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.nuvio.tv.data.local.PlayerSettings
import java.util.concurrent.atomic.AtomicBoolean
import android.os.SystemClock

/**
 * A DataSource that downloads progressive files using multiple parallel HTTP range requests.
 *
 * Each individual TCP connection may be limited to ~100 Mbps (due to CDN per-connection limits
 * or Java/Okio networking overhead). By downloading different byte ranges in parallel across
 * multiple connections, we can multiply the effective throughput (e.g., 3 connections ≈ 300 Mbps).
 *
 * Uses a buffer pool to reuse ByteArrays and avoid GC churn from large object allocations.
 *
 * Only used for progressive downloads (MKV, MP4). HLS/DASH already handle chunked parallel downloads.
 */
@UnstableApi
internal class ParallelRangeDataSource(
    private val upstreamFactory: OkHttpDataSource.Factory,
    private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
    private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB.toLong() * 1024 * 1024,
    private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
    private val onResolvedUri: (Uri?) -> Unit = {},
    private val consumeBootstrapCache: (DataSpec) -> BootstrapCacheEntry? = { null },
    private val updateBootstrapCache: (BootstrapCacheEntry?) -> Unit = {}
) : DataSource {

    companion object {
        private const val TAG = "ParallelRangeDS"
        private const val READ_BUFFER_SIZE = 512 * 1024 // 512KB read buffer for chunk downloads
        private const val BOOTSTRAP_READ_BYTES = 1L * 1024L * 1024L
    }

    /**
     * A downloaded chunk: a pooled byte array plus the actual number of bytes written.
     * The array may be larger than [size] (it's from the pool).
     */
    private class DownloadedChunk(val data: ByteArray, val size: Int)

    internal data class BootstrapCacheEntry(
        val requestUri: Uri,
        val startPosition: Long,
        val resolvedUri: Uri?,
        val openLength: Long,
        val totalFileLength: Long,
        val bootstrapData: ByteArray,
        val bootstrapSize: Int,
        val createdAtUptimeMs: Long
    )

    private var resolvedUri: Uri? = null
    private var originalDataSpec: DataSpec? = null
    private var totalFileLength: Long = C.LENGTH_UNSET.toLong()
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private val closed = AtomicBoolean(false)

    // Chunk download state
    private val chunks = ConcurrentHashMap<Long, CompletableFuture<DownloadedChunk>>()
    private var executor = Executors.newFixedThreadPool(parallelConnections)

    // Buffer pool: lock-free deque to reuse byte arrays and avoid GC churn
    private val bufferPool = ConcurrentLinkedDeque<ByteArray>()
    private val maxPoolSize = parallelConnections + 2

    // Current chunk being served to ExoPlayer
    private var currentChunk: DownloadedChunk? = null
    private var currentChunkIndex: Long = -1
    private var currentChunkReadOffset: Int = 0
    private var bootstrapPrefetchDeferred: Boolean = false
    private var bootstrapChunk: DownloadedChunk? = null
    private var bootstrapStartPosition: Long = C.TIME_UNSET
    private var continuationSource: OkHttpDataSource? = null
    private var continuationEndPositionExclusive: Long = C.TIME_UNSET

    private val transferListeners = mutableListOf<TransferListener>()

    // Fallback: if parallel mode fails, use a single upstream DataSource
    private var fallbackSource: OkHttpDataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        closed.set(false)
        originalDataSpec = dataSpec
        position = dataSpec.position
        bootstrapPrefetchDeferred = false
        bootstrapChunk = null
        bootstrapStartPosition = C.TIME_UNSET
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET

        // Cancel any in-flight chunks from a previous open (e.g., after seek)
        cancelAllChunks()

        // Recreate executor if it was shut down by a previous close()
        if (executor.isShutdown) {
            executor = Executors.newFixedThreadPool(parallelConnections)
        }

        consumeBootstrapCache(dataSpec)?.let { cached ->
            resolvedUri = cached.resolvedUri
            onResolvedUri(resolvedUri)
            totalFileLength = cached.totalFileLength
            bytesRemaining = cached.openLength
            bootstrapChunk = DownloadedChunk(cached.bootstrapData, cached.bootstrapSize)
            bootstrapStartPosition = cached.startPosition
            bootstrapPrefetchDeferred = true
            Log.d(
                TAG,
                "Reusing bootstrap window for immediate reopen at ${cached.startPosition}, " +
                    "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}"
            )
            return cached.openLength
        }

        // Open first connection to determine total length and capture the resolved (redirected) URL
        val probeSource: OkHttpDataSource = upstreamFactory.createDataSource()
        transferListeners.forEach { probeSource.addTransferListener(it) }

        val openLength: Long
        try {
            openLength = probeSource.open(dataSpec)
            resolvedUri = probeSource.uri // Final URL after redirects (CDN URL)
            onResolvedUri(resolvedUri)
        } catch (e: Exception) {
            probeSource.close()
            throw e
        }

        // Check if we can do parallel range requests
        val responseHeaders = probeSource.responseHeaders
        val acceptsRanges = responseHeaders["Accept-Ranges"]?.any { it.contains("bytes") } == true ||
                responseHeaders["Content-Range"]?.isNotEmpty() == true

        if (openLength == C.LENGTH_UNSET.toLong() || !acceptsRanges) {
            // Can't determine length or server doesn't support ranges — reuse probe as single connection
            Log.w(TAG, "Falling back to single connection (length=${openLength}, acceptsRanges=$acceptsRanges)")
            fallbackSource = probeSource
            return openLength
        }

        totalFileLength = position + openLength
        bytesRemaining = openLength

        Log.d(TAG, "Parallel mode: ${parallelConnections} connections, ${chunkSize / 1024 / 1024}MB chunks, " +
                "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}")

        // Reuse a small probe window immediately for both startup and large seek reopens.
        val firstChunkIndex = position / chunkSize
        if (openLength > 0L) {
            val bootstrapBytes = minOf(minOf(chunkSize, BOOTSTRAP_READ_BYTES), openLength).toInt()
            val chunk = readBootstrapChunk(probeSource, bootstrapBytes)
            bootstrapChunk = chunk
            bootstrapStartPosition = position
            // Avoid startup churn from immediate background fetches during repeated startup opens,
            // but do not redownload the active seek chunk from its start.
            bootstrapPrefetchDeferred = true
            if (position == 0L) {
                updateBootstrapCache(
                    BootstrapCacheEntry(
                        requestUri = dataSpec.uri,
                        startPosition = dataSpec.position,
                        resolvedUri = resolvedUri,
                        openLength = openLength,
                        totalFileLength = totalFileLength,
                        bootstrapData = chunk.data,
                        bootstrapSize = chunk.size,
                        createdAtUptimeMs = SystemClock.uptimeMillis()
                    )
                )
                probeSource.close()
            } else {
                continuationSource = probeSource
                continuationEndPositionExclusive = minOf((firstChunkIndex + 1L) * chunkSize, totalFileLength)
            }
        } else {
            probeSource.close()
        }

        return openLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // Fallback mode: delegate to single upstream
        fallbackSource?.let { return it.read(buffer, offset, length) }

        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        val chunkIndex = position / chunkSize
        val bootstrap = bootstrapChunk
        if (currentChunk == null &&
            bootstrap != null &&
            position >= bootstrapStartPosition &&
            position < bootstrapStartPosition + bootstrap.size
        ) {
            currentChunk = bootstrap
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position - bootstrapStartPosition).toInt()
        }

        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch() && currentChunk == null) {
            bootstrapPrefetchDeferred = false
            scheduleChunks()
        }

        continuationSource?.let { source ->
            if (position < continuationEndPositionExclusive &&
                bytesRemaining > 0L &&
                (bootstrap == null || position >= bootstrapStartPosition + bootstrap.size)
            ) {
                val read = source.read(buffer, offset, toRead)
                if (read > 0) {
                    position += read
                    bytesRemaining -= read
                    if (position >= continuationEndPositionExclusive) {
                        source.close()
                        continuationSource = null
                        continuationEndPositionExclusive = C.TIME_UNSET
                        scheduleChunks()
                    }
                    return read
                }
                if (read == C.RESULT_END_OF_INPUT || position >= continuationEndPositionExclusive) {
                    source.close()
                    continuationSource = null
                    continuationEndPositionExclusive = C.TIME_UNSET
                    scheduleChunks()
                }
            } else if (position >= continuationEndPositionExclusive || bytesRemaining <= 0L) {
                source.close()
                continuationSource = null
                continuationEndPositionExclusive = C.TIME_UNSET
            }
        }

        // Load the chunk for the current position
        if (currentChunkIndex != chunkIndex || currentChunk == null) {
            ensureChunkScheduled(chunkIndex)
            val future = chunks[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            try {
                currentChunk = future.get(60, TimeUnit.SECONDS)
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                throw IOException("Failed to download chunk $chunkIndex", e)
            }
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position % chunkSize).toInt()

            // Clean up old chunks (returns buffers to pool) and schedule new ones
            cleanupOldChunks(chunkIndex)
            scheduleChunks()
        }

        val chunk = currentChunk ?: return C.RESULT_END_OF_INPUT
        val available = chunk.size - currentChunkReadOffset
        if (available <= 0) {
            // Current chunk exhausted, move to next
            if (chunk === bootstrapChunk) {
                bootstrapChunk = null
                bootstrapStartPosition = C.TIME_UNSET
            }
            currentChunk = null
            return read(buffer, offset, length)
        }

        val readSize = minOf(toRead, available)
        System.arraycopy(chunk.data, currentChunkReadOffset, buffer, offset, readSize)
        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize

        return readSize
    }

    private fun scheduleChunks() {
        if (!shouldAllowBackgroundPrefetch()) return
        val currentChunkIdx =
            if (continuationSource != null && continuationEndPositionExclusive != C.TIME_UNSET && position < continuationEndPositionExclusive) {
                continuationEndPositionExclusive / chunkSize
            } else {
                position / chunkSize
            }
        val maxAhead = parallelConnections + 1

        for (i in 0 until maxAhead) {
            val ci = currentChunkIdx + i
            if (totalFileLength != C.LENGTH_UNSET.toLong() && ci * chunkSize >= totalFileLength) break
            ensureChunkScheduled(ci)
        }
    }

    private fun ensureChunkScheduled(chunkIndex: Long) {
        chunks.computeIfAbsent(chunkIndex) {
            CompletableFuture.supplyAsync({ downloadChunk(chunkIndex) }, executor)
        }
    }

    private fun downloadChunk(chunkIndex: Long): DownloadedChunk {
        var lastException: Exception? = null
        for (attempt in 0..1) {
            try {
                return downloadChunkOnce(chunkIndex)
            } catch (e: Exception) {
                if (closed.get()) throw IOException("DataSource closed")
                lastException = e
                if (attempt == 0) {
                    if (e.isTransientInterruption()) {
                        Log.d(TAG, "Chunk $chunkIndex interrupted during prefetch (attempt 1), retrying")
                        try {
                            Thread.sleep(50)
                        } catch (_: InterruptedException) {
                        }
                    } else {
                        Log.w(TAG, "Chunk $chunkIndex download failed (attempt 1), retrying: ${e.message}")
                    }
                }
            }
        }
        throw IOException("Failed to download chunk $chunkIndex after 2 attempts", lastException)
    }

    private fun downloadChunkOnce(chunkIndex: Long): DownloadedChunk {
        val start = chunkIndex * chunkSize
        val end = if (totalFileLength != C.LENGTH_UNSET.toLong()) {
            minOf(start + chunkSize, totalFileLength)
        } else {
            start + chunkSize
        }

        val ds = upstreamFactory.createDataSource()
        val uri = resolvedUri ?: originalDataSpec?.uri ?: throw IOException("No URI available")
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(start)
            .setLength(end - start)
            .build()

        ds.open(spec)
        val chunk = readIntoChunk(ds)
        ds.close()
        return chunk
    }

    private fun Exception.isTransientInterruption(): Boolean {
        if (this is InterruptedIOException || this is InterruptedException) return true
        val cause = cause
        return cause is InterruptedIOException || cause is InterruptedException
    }

    /** Read from an already-opened DataSource into a pooled chunk buffer. */
    private fun readIntoChunk(ds: DataSource): DownloadedChunk {
        val buffer = acquireBuffer()
        var totalRead = 0
        try {
            while (!closed.get()) {
                val maxRead = minOf(buffer.size - totalRead, READ_BUFFER_SIZE)
                if (maxRead <= 0) break
                val read = ds.read(buffer, totalRead, maxRead)
                if (read == C.RESULT_END_OF_INPUT) break
                totalRead += read
            }
        } catch (e: Exception) {
            releaseBuffer(buffer)
            if (closed.get()) throw IOException("DataSource closed")
            throw e
        }
        if (closed.get()) {
            releaseBuffer(buffer)
            throw IOException("DataSource closed")
        }
        return DownloadedChunk(buffer, totalRead)
    }

    /** Read only a small startup window from an already-opened DataSource. */
    private fun readBootstrapChunk(ds: DataSource, maxBytes: Int): DownloadedChunk {
        val buffer = ByteArray(maxBytes)
        var totalRead = 0
        try {
            while (!closed.get() && totalRead < buffer.size) {
                val maxRead = minOf(buffer.size - totalRead, READ_BUFFER_SIZE)
                if (maxRead <= 0) break
                val read = ds.read(buffer, totalRead, maxRead)
                if (read == C.RESULT_END_OF_INPUT) break
                totalRead += read
            }
        } catch (e: Exception) {
            if (closed.get()) throw IOException("DataSource closed")
            throw e
        }
        if (closed.get()) {
            throw IOException("DataSource closed")
        }
        return DownloadedChunk(buffer, totalRead)
    }

    private fun acquireBuffer(): ByteArray {
        return bufferPool.pollLast() ?: ByteArray(chunkSize.toInt())
    }

    /**
     *   maxPoolSize in releaseBuffer only caps how many idle/recycled buffers are kept in the pool.
     *   If the pool is full, the released buffer is GC'd instead of recycled. It does NOT
     *   determine how many buffers exist simultaneously. The actual peak concurrent buffers
     *   is driven by maxAhead.
     */
    private fun releaseBuffer(buffer: ByteArray) {
        if (bufferPool.size < maxPoolSize) {
            bufferPool.offerLast(buffer)
        }
        // else: let it be GC'd (pool is full)
    }

    private fun cleanupOldChunks(currentChunkIndex: Long) {
        val iter = chunks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key < currentChunkIndex) {
                val future = entry.value
                if (future.isDone && !future.isCancelled) {
                    try { releaseBuffer(future.get().data) } catch (_: Exception) {}
                }
                future.cancel(true)
                iter.remove()
            }
        }
    }

    /** Cancel and clean up all in-flight chunks, returning buffers to the pool. */
    private fun cancelAllChunks() {
        currentChunk?.let {
            if (it !== bootstrapChunk) {
                releaseBuffer(it.data)
            }
        }
        currentChunk = null
        currentChunkIndex = -1
        bootstrapChunk = null
        bootstrapStartPosition = C.TIME_UNSET

        chunks.values.forEach { future ->
            if (future.isDone && !future.isCancelled) {
                try { releaseBuffer(future.get().data) } catch (_: Exception) {}
            }
            future.cancel(true)
        }
        chunks.clear()
    }

    override fun close() {
        closed.set(true)
        fallbackSource?.close()
        fallbackSource = null
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET

        cancelAllChunks()
        executor.shutdownNow()

        bufferPool.clear()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun getUri(): Uri? = resolvedUri ?: fallbackSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        fallbackSource?.responseHeaders ?: emptyMap()

    /**
     * Factory for creating ParallelRangeDataSource instances.
     */
    class Factory(
        private val upstreamFactory: OkHttpDataSource.Factory,
        private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
        private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB.toLong() * 1024 * 1024,
        private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
        private val onResolvedUri: (Uri?) -> Unit = {}
    ) : DataSource.Factory {
        @Volatile
        private var startupBootstrapCache: BootstrapCacheEntry? = null

        override fun createDataSource(): DataSource {
            return ParallelRangeDataSource(
                upstreamFactory = upstreamFactory,
                parallelConnections = parallelConnections,
                chunkSize = chunkSize,
                shouldAllowBackgroundPrefetch = shouldAllowBackgroundPrefetch,
                onResolvedUri = onResolvedUri,
                consumeBootstrapCache = { dataSpec ->
                    val cached = startupBootstrapCache ?: return@ParallelRangeDataSource null
                    val isFresh = SystemClock.uptimeMillis() - cached.createdAtUptimeMs <= 15_000L
                    if (!isFresh) {
                        startupBootstrapCache = null
                        return@ParallelRangeDataSource null
                    }
                    if (cached.startPosition != 0L || dataSpec.position != 0L) return@ParallelRangeDataSource null
                    if (dataSpec.position != cached.startPosition) return@ParallelRangeDataSource null
                    if (dataSpec.uri != cached.requestUri) return@ParallelRangeDataSource null
                    cached
                },
                updateBootstrapCache = { entry ->
                    startupBootstrapCache = entry
                }
            )
        }
    }
}
