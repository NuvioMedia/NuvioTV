package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import com.nuvio.tv.data.local.PlayerSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.os.SystemClock

import java.nio.ByteBuffer

/**
 * A DataSource that downloads progressive files using multiple parallel HTTP range requests.
 *
 * Each individual TCP connection may be limited to ~100 Mbps (due to CDN per-connection limits
 * or Java/Okio networking overhead). By downloading different byte ranges in parallel across
 * multiple connections, we can multiply the effective throughput (e.g., 3 connections ≈ 300 Mbps).
 *
 * Uses a buffer pool to reuse ByteArrays or native ByteBuffers and avoid GC churn from large object allocations.
 *
 * Only used for progressive downloads (MKV, MP4). HLS/DASH already handle chunked parallel downloads.
 */
@UnstableApi
internal class ParallelRangeDataSource(
    private val upstreamFactory: OkHttpDataSource.Factory,
    private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
    private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
    private val useNativeMemory: Boolean = false,
    private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
    private val onResolvedUri: (Uri?) -> Unit = {},
    private val consumeBootstrapCache: (DataSpec) -> BootstrapCacheEntry? = { null },
    private val updateBootstrapCache: (BootstrapCacheEntry?) -> Unit = {}
) : DataSource, androidx.media3.common.ByteBufferDataReader {

    companion object {
        private const val TAG = "ParallelRangeDS"
        private const val READ_BUFFER_SIZE = 64 * 1024 // 64KB read buffer for chunk downloads
        private const val BOOTSTRAP_READ_BYTES = 1L * 1024L * 1024L

        private val readBufferLocal = object : ThreadLocal<ByteArray>() {
            override fun initialValue(): ByteArray = ByteArray(READ_BUFFER_SIZE)
        }

        // A single, shared, lazy cached thread pool with bounded max threads to prevent OOM/pthread_create failure
        private val sharedExecutor: ExecutorService by lazy {
            val threadFactory = ThreadFactory { runnable ->
                Thread(runnable, "parallel-ds-worker").apply {
                    priority = Thread.NORM_PRIORITY
                    isDaemon = true
                }
            }
            ThreadPoolExecutor(
                32, 64, 60L, TimeUnit.SECONDS,
                java.util.concurrent.LinkedBlockingQueue<Runnable>(),
                threadFactory,
                ThreadPoolExecutor.DiscardPolicy()
            ).apply {
                allowCoreThreadTimeOut(true)
            }
        }

        private val activeInstances = java.util.concurrent.atomic.AtomicInteger(0)
        private val globalBufferPool = ConcurrentHashMap<Long, ConcurrentLinkedDeque<PooledBuffer>>()

        private fun freeDirectBuffer(buffer: ByteBuffer) {
            if (!buffer.isDirect) return
            try {
                val cleanerMethod = buffer.javaClass.getMethod("cleaner")
                cleanerMethod.isAccessible = true
                val cleaner = cleanerMethod.invoke(buffer)
                if (cleaner != null) {
                    val cleanMethod = cleaner.javaClass.getMethod("clean")
                    cleanMethod.isAccessible = true
                    cleanMethod.invoke(cleaner)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to explicitly free direct buffer: ${e.message}")
            }
        }

        private const val RETAINED_SESSION_TTL_MS = 45_000L
        private const val EARNED_PREFETCH_BYTES = 1L * 1024L * 1024L

        internal fun lookaheadDepth(
            bytesServedThisOpen: Long,
            earnedPrefetchBytes: Long,
            currentChunkComplete: Boolean,
            nextChunkComplete: Boolean,
            configuredDepth: Int,
            rateLimitDepth: Int
        ): Int {
            if (bytesServedThisOpen < earnedPrefetchBytes) return 1
            if (!currentChunkComplete || !nextChunkComplete) {
                return 2.coerceAtMost(configuredDepth).coerceAtMost(rateLimitDepth).coerceAtLeast(1)
            }
            return configuredDepth.coerceAtMost(rateLimitDepth).coerceAtLeast(1)
        }

        private const val TAIL_CHUNK_COUNT = 4L

        internal fun isTailChunk(chunkIndex: Long, totalChunks: Long): Boolean {
            return totalChunks > 0L && chunkIndex >= totalChunks - TAIL_CHUNK_COUNT
        }

        internal fun shouldMoveMainCursor(
            lastReadChunkIndex: Long,
            chunkIndex: Long,
            prefetchWindow: Int,
            sequentialOpen: Boolean,
            currentChunkComplete: Boolean,
            totalChunks: Long
        ): Boolean {
            if (isTailChunk(chunkIndex, totalChunks)) {
                if (lastReadChunkIndex < 0L) return false
                val delta = chunkIndex - lastReadChunkIndex
                val distance = if (delta >= 0L) delta else -delta
                return distance <= prefetchWindow.toLong()
            }
            if (lastReadChunkIndex < 0L) return true
            val delta = chunkIndex - lastReadChunkIndex
            val distance = if (delta >= 0L) delta else -delta
            if (distance <= prefetchWindow.toLong()) return true
            return sequentialOpen && currentChunkComplete
        }

        private const val EVICTION_TOUCH_GUARD_MS = 15_000L
        private const val PLAYHEAD_BACK_CHUNKS = 2L
        private const val MAX_PINNED_SIDE_CHUNKS = 2

        internal fun isInPlayheadWindow(
            readerIdx: Long,
            chunkIndex: Long,
            prefetchWindow: Int,
            backChunks: Long = PLAYHEAD_BACK_CHUNKS
        ): Boolean {
            if (readerIdx < 0L) return false
            val floor = (readerIdx - backChunks).coerceAtLeast(0L)
            val ceil = readerIdx + prefetchWindow.toLong()
            return chunkIndex in floor..ceil
        }
        private const val RATE_LIMIT_MAX_BACKOFF_RETRIES = 3
        private const val RATE_LIMIT_BACKOFF_BASE_MS = 500L
        private const val RATE_LIMIT_BACKOFF_CYCLE_CAP_MS = 3_000L
        private const val RATE_LIMIT_WAIT_HARD_CAP_MS = 15_000L
        private const val RATE_LIMIT_BACKOFF_JITTER_MS = 250L
        private const val RATE_LIMIT_SLEEP_SLICE_MS = 100L
        private const val RATE_LIMIT_DEPTH_STEP_BASE_MS = 10_000L
        private const val RATE_LIMIT_DEPTH_STEP_MAX_MS = 60_000L
        private const val RATE_LIMIT_ESCALATION_MAX = 5
        private const val MAX_CONSECUTIVE_ZERO_READS = 3

        private class ChunkSession(
            val requestUri: Uri,
            val requestHeaders: Map<String, String>,
            val chunkSize: Long,
            val chunkCap: Int,
            val prefetchWindow: Int
        ) {
            @Volatile var resolvedUri: Uri? = null
            @Volatile var totalLength: Long = -1L
            val futures = ConcurrentHashMap<Long, CompletableFuture<DownloadedChunk>>()
            val lastTouch = ConcurrentHashMap<Long, Long>()
            val abandoned = AtomicBoolean(false)
            val activeSources: MutableSet<DataSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()
            @Volatile var lastUsedAtMs: Long = SystemClock.uptimeMillis()

            fun touch(chunkIndex: Long) {
                val now = SystemClock.uptimeMillis()
                lastTouch[chunkIndex] = now
                lastUsedAtMs = now
            }

            @Volatile var lastReadChunkIndex: Long = -1L
            val pinnedSideChunks: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

            fun noteRead(
                chunkIndex: Long,
                sequentialOpen: Boolean,
                currentChunkComplete: Boolean,
                totalChunks: Long
            ) {
                touch(chunkIndex)
                if (shouldMoveMainCursor(
                        lastReadChunkIndex,
                        chunkIndex,
                        prefetchWindow,
                        sequentialOpen,
                        currentChunkComplete,
                        totalChunks
                    )
                ) {
                    lastReadChunkIndex = chunkIndex
                    pinnedSideChunks.remove(chunkIndex)
                } else {
                    pinSideChunk(chunkIndex)
                }
            }

            fun pinSideChunk(chunkIndex: Long) {
                pinnedSideChunks.add(chunkIndex)
                while (pinnedSideChunks.size > MAX_PINNED_SIDE_CHUNKS) {
                    val drop = pinnedSideChunks.minByOrNull { lastTouch[it] ?: 0L } ?: break
                    if (!pinnedSideChunks.remove(drop)) break
                }
            }

            val rateLimitDepthCap = AtomicInteger(Int.MAX_VALUE)
            @Volatile var lastRateLimitAtMs: Long = 0L
            @Volatile var lastDepthHalveAtMs: Long = 0L
            @Volatile var lastDepthStepAtMs: Long = 0L
            @Volatile var depthStepIntervalMs: Long = RATE_LIMIT_DEPTH_STEP_BASE_MS
            val rateLimitEscalation = AtomicInteger(0)

            fun noteRateLimitHit() {
                lastRateLimitAtMs = SystemClock.uptimeMillis()
            }

            fun beginRateLimitEpisode(configuredDepth: Int): Int {
                val now = SystemClock.uptimeMillis()
                lastRateLimitAtMs = now
                val escalation = rateLimitEscalation.getAndUpdate {
                    (it + 1).coerceAtMost(RATE_LIMIT_ESCALATION_MAX)
                }
                if (now - lastDepthHalveAtMs >= 1_000L) {
                    val alreadyCapped = rateLimitDepthCap.get() < configuredDepth
                    val effective = rateLimitDepthCap.get().coerceAtMost(configuredDepth)
                    val halved = (effective / 2).coerceAtLeast(1)
                    if (halved < effective) {
                        rateLimitDepthCap.set(halved)
                        lastDepthHalveAtMs = now
                        if (alreadyCapped) {
                            depthStepIntervalMs =
                                (depthStepIntervalMs * 2).coerceAtMost(RATE_LIMIT_DEPTH_STEP_MAX_MS)
                        }
                        Log.w(TAG, "Rate-limited; prefetch depth halved to " +
                            "$halved/$configuredDepth (probe interval ${depthStepIntervalMs}ms)")
                    }
                }
                return escalation
            }

            fun currentAllowedDepth(configuredDepth: Int): Int {
                val cap = rateLimitDepthCap.get()
                if (cap >= configuredDepth) return configuredDepth
                val now = SystemClock.uptimeMillis()
                if (now - lastRateLimitAtMs >= depthStepIntervalMs &&
                    now - lastDepthStepAtMs >= depthStepIntervalMs) {
                    val stepped = cap + 1
                    if (rateLimitDepthCap.compareAndSet(cap, stepped)) {
                        lastDepthStepAtMs = now
                        rateLimitEscalation.updateAndGet { (it - 1).coerceAtLeast(0) }
                        if (stepped >= configuredDepth) {
                            rateLimitDepthCap.set(Int.MAX_VALUE)
                            depthStepIntervalMs = RATE_LIMIT_DEPTH_STEP_BASE_MS
                            Log.i(TAG, "Rate-limit depth cap cleared; parallel prefetch fully restored")
                        } else {
                            Log.i(TAG, "Rate-limit quiet; prefetch depth stepped to $stepped/$configuredDepth")
                        }
                    }
                }
                return rateLimitDepthCap.get().coerceAtMost(configuredDepth)
            }
        }

        private val sessionLock = Any()
        private var currentChunkSession: ChunkSession? = null

        private fun releaseSessionBuffer(buffer: PooledBuffer, chunkSz: Long, poolCap: Int) {
            if (poolCap > 0) {
                val pool = globalBufferPool.computeIfAbsent(chunkSz) { ConcurrentLinkedDeque() }
                if (pool.size < poolCap) {
                    pool.offerLast(buffer)
                    return
                }
            }
            if (buffer.allocation != null) {
                androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buffer.allocation)
            } else if (buffer.byteBuffer.isDirect) {
                freeDirectBuffer(buffer.byteBuffer)
            }
        }

        private fun evictFuture(
            session: ChunkSession,
            chunkIndex: Long,
            poolCap: Int
        ) {
            val future = session.futures.remove(chunkIndex) ?: return
            session.lastTouch.remove(chunkIndex)
            session.pinnedSideChunks.remove(chunkIndex)
            if (!future.cancel(true) && future.isDone && !future.isCancelled) {
                try {
                    releaseSessionBuffer(future.get().buffer, session.chunkSize, poolCap)
                } catch (_: Exception) {
                }
            }
        }

        private fun teardownSessionLocked(session: ChunkSession, poolCap: Int) {
            session.abandoned.set(true)
            session.activeSources.forEach { ds ->
                try { ds.close() } catch (_: Exception) {}
            }
            session.activeSources.clear()
            val indices = session.futures.keys.toList()
            for (index in indices) {
                evictFuture(session, index, poolCap)
            }
            session.futures.clear()
            session.lastTouch.clear()
            session.pinnedSideChunks.clear()
        }

        private fun obtainSession(
            requestUri: Uri,
            requestHeaders: Map<String, String>,
            chunkSz: Long,
            chunkCap: Int,
            poolCap: Int,
            prefetchWindow: Int
        ): ChunkSession {
            synchronized(sessionLock) {
                val existing = currentChunkSession
                if (existing != null) {
                    val fresh = SystemClock.uptimeMillis() - existing.lastUsedAtMs <= RETAINED_SESSION_TTL_MS
                    if (fresh && !existing.abandoned.get() &&
                        existing.requestUri == requestUri && existing.chunkSize == chunkSz &&
                        existing.requestHeaders == requestHeaders
                    ) {
                        existing.lastUsedAtMs = SystemClock.uptimeMillis()
                        return existing
                    }
                    teardownSessionLocked(existing, poolCap)
                }
                val created = ChunkSession(requestUri, requestHeaders, chunkSz, chunkCap, prefetchWindow)
                currentChunkSession = created
                return created
            }
        }

        internal fun releaseRetainedSession() {
            synchronized(sessionLock) {
                currentChunkSession?.let { teardownSessionLocked(it, poolCap = 0) }
                currentChunkSession = null
            }
        }

        private fun enforceSessionCap(session: ChunkSession, protectIndex: Long, poolCap: Int) {
            if (session.futures.size <= session.chunkCap) return
            synchronized(session) {
                while (session.futures.size > session.chunkCap) {
                    val now = SystemClock.uptimeMillis()
                    val readerIdx = session.lastReadChunkIndex
                    val totalChunks = if (session.totalLength > 0L && session.chunkSize > 0L) {
                        (session.totalLength + session.chunkSize - 1L) / session.chunkSize
                    } else {
                        0L
                    }
                    val evictable = session.futures.keys
                        .filter { it != protectIndex }
                        .filter { it != readerIdx }
                        .filter { now - (session.lastTouch[it] ?: 0L) >= EVICTION_TOUCH_GUARD_MS }
                        .filter { !isInPlayheadWindow(readerIdx, it, session.prefetchWindow) }
                        .filter { !isTailChunk(it, totalChunks) }
                        .filter { !session.pinnedSideChunks.contains(it) }
                        .filter { index ->
                            val future = session.futures[index]
                            future == null || (future.isDone && !future.isCancelled)
                        }
                    val victim = evictable
                        .filter { readerIdx >= 0L && it < readerIdx }
                        .minByOrNull { session.lastTouch[it] ?: 0L }
                        ?: evictable.maxOrNull()
                        ?: if (session.futures.size > session.chunkCap + 8) {
                            session.futures.keys
                                .filter { it != protectIndex && it != readerIdx }
                                .filter { !isInPlayheadWindow(readerIdx, it, session.prefetchWindow) }
                                .filter { !isTailChunk(it, totalChunks) }
                                .filter { index ->
                                    val future = session.futures[index]
                                    future != null && future.isDone && !future.isCancelled
                                }
                                .minByOrNull { session.lastTouch[it] ?: 0L }
                        } else {
                            null
                        }
                        ?: return
                    evictFuture(session, victim, poolCap)
                }
            }
        }

        private fun clearGlobalPool() {
            globalBufferPool.values.forEach { pool ->
                while (true) {
                    val buf = pool.pollFirst() ?: break
                    if (buf.allocation != null) {
                        androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buf.allocation)
                    } else if (buf.byteBuffer.isDirect) {
                        freeDirectBuffer(buf.byteBuffer)
                    }
                }
            }
            globalBufferPool.clear()
            Log.d(TAG, "Cleared global buffer pool as all ParallelRangeDataSource instances are closed")
        }
    }

    init {
        activeInstances.incrementAndGet()
    }

    /**
     * A downloaded chunk: a pooled byte array plus the actual number of bytes written.
     * The array may be larger than [size] (it's from the pool).
     */
    private class PooledBuffer(
        val allocation: androidx.media3.exoplayer.upstream.Allocation?,
        val byteBuffer: ByteBuffer
    )

    private class DownloadedChunk(val buffer: PooledBuffer, val size: Int)

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

    // Buffer pool limit
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

    private var session: ChunkSession? = null
    private var bytesServedThisOpen: Long = 0L
    private val sessionChunkCap: Int = parallelConnections +
        if (com.nuvio.tv.ui.screens.settings.MemoryBudget.isLowRamTier) 2 else 4

    override fun open(dataSpec: DataSpec): Long {
        val isSubtitle = dataSpec.uri.getQueryParameter("nuvio_type") == "subtitle"
        if (isSubtitle) {
            closed.set(false)
            resetLocalReadState()
            
            // Clean the custom query parameter from the subtitle URL before requesting
            val cleanedUri = dataSpec.uri.buildUpon().clearQuery().let { builder ->
                dataSpec.uri.queryParameterNames.forEach { name ->
                    if (name != "nuvio_type") {
                        dataSpec.uri.getQueryParameters(name).forEach { value ->
                            builder.appendQueryParameter(name, value)
                        }
                    }
                }
                builder.build()
            }
            val cleanedDataSpec = dataSpec.withUri(cleanedUri)
            
            val probeSource = upstreamFactory.createDataSource()
            transferListeners.forEach { probeSource.addTransferListener(it) }
            fallbackSource = probeSource
            val openLength = probeSource.open(cleanedDataSpec)
            
            totalFileLength = openLength
            bytesRemaining = openLength
            position = dataSpec.position
            
            Log.d(TAG, "Subtitle request detected. Bypassing parallel mode for single-connection download: ${cleanedUri.host}")
            return openLength
        }

        val wasClosed = closed.get()
        val isReopen = !wasClosed && 
                       fallbackSource == null &&
                       originalDataSpec != null && 
                       originalDataSpec?.uri == dataSpec.uri && 
                       position == dataSpec.position &&
                       totalFileLength != C.LENGTH_UNSET.toLong()

        closed.set(false)

        if (isReopen) {
            position = dataSpec.position
            bytesRemaining = (totalFileLength - position).coerceAtLeast(0L)
            bootstrapPrefetchDeferred = true
            Log.d(TAG, "Reusing active ParallelRangeDataSource for reopen at $position, file=${totalFileLength / 1024 / 1024}MB")
            return bytesRemaining
        }

        originalDataSpec = dataSpec
        position = dataSpec.position
        bootstrapPrefetchDeferred = false
        bootstrapChunk = null
        bootstrapStartPosition = C.TIME_UNSET
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET
        fallbackSource?.close()
        fallbackSource = null
        totalFileLength = C.LENGTH_UNSET.toLong()
        bytesRemaining = C.LENGTH_UNSET.toLong()

        resetLocalReadState()
        bytesServedThisOpen = 0L

        val attachedSession = obtainSession(dataSpec.uri, dataSpec.httpRequestHeaders, chunkSize, sessionChunkCap, maxPoolSize, parallelConnections + 1)
        session = attachedSession
        val warmLength = attachedSession.totalLength
        if (warmLength > 0L && dataSpec.position in 0 until warmLength) {
            resolvedUri = attachedSession.resolvedUri
            onResolvedUri(resolvedUri)
            totalFileLength = warmLength
            val remaining = (totalFileLength - position).coerceAtLeast(0L)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, remaining)
            } else {
                remaining
            }
            bootstrapPrefetchDeferred = true
            Log.d(
                TAG,
                "Attached to warm session for reopen at $position, " +
                    "file=${totalFileLength / 1024 / 1024}MB, held=${attachedSession.futures.size} chunk(s) (probe skipped)"
            )
            return bytesRemaining
        }

        consumeBootstrapCache(dataSpec)?.let { cached ->
            resolvedUri = cached.resolvedUri
            onResolvedUri(resolvedUri)
            totalFileLength = cached.totalFileLength
            bytesRemaining = cached.openLength
            bootstrapChunk = DownloadedChunk(PooledBuffer(null, ByteBuffer.wrap(cached.bootstrapData)), cached.bootstrapSize)
            bootstrapStartPosition = cached.startPosition
            bootstrapPrefetchDeferred = true
            attachedSession.resolvedUri = resolvedUri
            attachedSession.totalLength = totalFileLength
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
        val acceptRangesHeader = responseHeaders.entries.firstOrNull { it.key.equals("Accept-Ranges", ignoreCase = true) }?.value
        val contentRangeHeader = responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value
        val acceptsRanges = acceptRangesHeader?.any { it.contains("bytes") } == true ||
                contentRangeHeader?.isNotEmpty() == true

        if (openLength == C.LENGTH_UNSET.toLong() || !acceptsRanges) {
            // Can't determine length or server doesn't support ranges — reuse probe as single connection
            Log.w(TAG, "Falling back to single connection (length=${openLength}, acceptsRanges=$acceptsRanges)")
            fallbackSource = probeSource
            totalFileLength = if (openLength != C.LENGTH_UNSET.toLong()) {
                position + openLength
            } else {
                C.LENGTH_UNSET.toLong()
            }
            bytesRemaining = openLength
            return openLength
        }

        totalFileLength = position + openLength
        bytesRemaining = openLength

        attachedSession.resolvedUri = resolvedUri
        attachedSession.totalLength = totalFileLength

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
                        bootstrapData = chunk.buffer.byteBuffer.array(),
                        bootstrapSize = chunk.size,
                        createdAtUptimeMs = SystemClock.uptimeMillis()
                    )
                )
            }
            probeSource.close()
        } else {
            probeSource.close()
        }

        return openLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // Fallback mode: delegate to single upstream
        fallbackSource?.let { source ->
            val read = source.read(buffer, offset, length)
            if (read > 0) {
                position += read
                bytesRemaining = (bytesRemaining - read).coerceAtLeast(0L)
            }
            return read
        }

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

        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
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
            val activeSession = session ?: return C.RESULT_END_OF_INPUT
            ensureChunkScheduled(chunkIndex)
            val future = activeSession.futures[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            noteSessionRead(activeSession, chunkIndex)
            try {
                currentChunk = future.get(60, TimeUnit.SECONDS)
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                if (activeSession.futures.remove(chunkIndex, future)) {
                    activeSession.lastTouch.remove(chunkIndex)
                    activeSession.pinnedSideChunks.remove(chunkIndex)
                    if (!future.cancel(true) && future.isDone && !future.isCancelled) {
                        try {
                            releaseSessionBuffer(future.get().buffer, activeSession.chunkSize, maxPoolSize)
                        } catch (_: Exception) {
                        }
                    }
                }
                throw IOException("Failed to download chunk $chunkIndex", e)
            }
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position % chunkSize).toInt()

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
        val readBuf = chunk.buffer.byteBuffer.duplicate()
        readBuf.position(currentChunkReadOffset)
        readBuf.get(buffer, offset, readSize)
        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize
        bytesServedThisOpen += readSize
        noteSessionRead(session, chunkIndex)

        return readSize
    }

    private fun noteSessionRead(activeSession: ChunkSession?, chunkIndex: Long) {
        val currentComplete = activeSession?.futures?.get(chunkIndex)?.let { future ->
            future.isDone && !future.isCancelled && !future.isCompletedExceptionally
        } == true
        val totalChunks = if (activeSession != null && activeSession.totalLength > 0L && chunkSize > 0L) {
            (activeSession.totalLength + chunkSize - 1L) / chunkSize
        } else {
            0L
        }
        activeSession?.noteRead(
            chunkIndex,
            bytesServedThisOpen >= EARNED_PREFETCH_BYTES,
            currentComplete,
            totalChunks
        )
    }

    private fun scheduleChunks() {
        if (!shouldAllowBackgroundPrefetch()) return
        val currentChunkIdx =
            if (continuationSource != null && continuationEndPositionExclusive != C.TIME_UNSET && position < continuationEndPositionExclusive) {
                continuationEndPositionExclusive / chunkSize
            } else {
                position / chunkSize
            }
        val configuredDepth = parallelConnections + 1
        fun chunkComplete(index: Long): Boolean {
            val future = session?.futures?.get(index) ?: return false
            return future.isDone && !future.isCancelled && !future.isCompletedExceptionally
        }
        val maxAhead = lookaheadDepth(
            bytesServedThisOpen = bytesServedThisOpen,
            earnedPrefetchBytes = EARNED_PREFETCH_BYTES,
            currentChunkComplete = chunkComplete(currentChunkIdx),
            nextChunkComplete = chunkComplete(currentChunkIdx + 1),
            configuredDepth = configuredDepth,
            rateLimitDepth = session?.currentAllowedDepth(configuredDepth) ?: configuredDepth
        )

        for (i in 0 until maxAhead) {
            val ci = currentChunkIdx + i
            if (totalFileLength != C.LENGTH_UNSET.toLong() && ci * chunkSize >= totalFileLength) break
            ensureChunkScheduled(ci)
        }
    }

    private fun ensureChunkScheduled(chunkIndex: Long) {
        val activeSession = session ?: return
        val totalChunks = if (activeSession.totalLength > 0L && chunkSize > 0L) {
            (activeSession.totalLength + chunkSize - 1L) / chunkSize
        } else {
            0L
        }
        if (isTailChunk(chunkIndex, totalChunks) ||
            !shouldMoveMainCursor(
                activeSession.lastReadChunkIndex,
                chunkIndex,
                activeSession.prefetchWindow,
                sequentialOpen = false,
                currentChunkComplete = false,
                totalChunks = totalChunks
            )
        ) {
            activeSession.pinSideChunk(chunkIndex)
        }
        enforceSessionCap(activeSession, protectIndex = chunkIndex, poolCap = maxPoolSize)
        activeSession.futures.computeIfAbsent(chunkIndex) {
            val future = CompletableFuture<DownloadedChunk>()
            activeSession.touch(chunkIndex)
            Log.d(TAG, "Scheduling chunk $chunkIndex")
            sharedExecutor.execute {
                try {
                    if (!future.isCancelled && !activeSession.abandoned.get()) {
                        val result = downloadChunk(activeSession, chunkIndex, future)
                        if (!future.complete(result)) {
                            releaseBuffer(result.buffer)
                        } else {
                            activeSession.touch(chunkIndex)
                        }
                    } else if (future.isCancelled) {
                        // no-op: never started
                    } else {
                        future.completeExceptionally(IOException("Session abandoned"))
                    }
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
            future
        }
    }

    private fun downloadChunk(activeSession: ChunkSession, chunkIndex: Long, future: CompletableFuture<*>): DownloadedChunk {
        var lastException: Exception? = null
        for (attempt in 0..1) {
            if (future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future)
            } catch (e: Exception) {
                if (activeSession.abandoned.get() || future.isCancelled) throw IOException("Session abandoned or cancelled")
                val rlError = e.findRateLimitException()
                if (rlError != null) {
                    return downloadChunkWithRateLimitBackoff(activeSession, chunkIndex, future, rlError)
                }
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

    private fun downloadChunkOnce(activeSession: ChunkSession, chunkIndex: Long, future: CompletableFuture<*>): DownloadedChunk {
        val sessionLength = activeSession.totalLength
        val start = chunkIndex * chunkSize
        val end = if (sessionLength > 0L) {
            minOf(start + chunkSize, sessionLength)
        } else {
            start + chunkSize
        }

        val ds = upstreamFactory.createDataSource()
        transferListeners.forEach { ds.addTransferListener(it) }
        activeSession.activeSources.add(ds)
        try {
            val uri = activeSession.resolvedUri ?: activeSession.requestUri
            val spec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(start)
                .setLength(end - start)
                .build()

            if (future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            Log.d(TAG, "Starting chunk download: idx=$chunkIndex, range=$start-$end")
            ds.open(spec)
            val expectedBytes = if (sessionLength > 0L) end - start else -1L
            val chunk = readIntoChunk(activeSession, ds, future, expectedBytes)
            Log.d(TAG, "Successfully downloaded chunk $chunkIndex, size=${chunk.size} bytes")
            return chunk
        } finally {
            activeSession.activeSources.remove(ds)
            try { ds.close() } catch (_: Exception) {}
        }
    }

    private fun Exception.isTransientInterruption(): Boolean {
        if (this is InterruptedIOException || this is InterruptedException) return true
        val cause = cause
        return cause is InterruptedIOException || cause is InterruptedException
    }

    private fun Throwable.findRateLimitException(): HttpDataSource.InvalidResponseCodeException? {
        var cause: Throwable? = this
        var depth = 0
        while (cause != null && depth < 6) {
            val c = cause
            if (c is HttpDataSource.InvalidResponseCodeException &&
                (c.responseCode == 429 || c.responseCode == 503)) {
                return c
            }
            cause = c.cause
            depth++
        }
        return null
    }

    private fun downloadChunkWithRateLimitBackoff(
        activeSession: ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        firstError: HttpDataSource.InvalidResponseCodeException
    ): DownloadedChunk {
        var rl: HttpDataSource.InvalidResponseCodeException = firstError
        var lastException: Exception = firstError
        val escalation = activeSession.beginRateLimitEpisode(parallelConnections + 1)
        var attempt = 0
        while (attempt < RATE_LIMIT_MAX_BACKOFF_RETRIES) {
            val waitMs = rateLimitWaitMs(attempt, escalation, rl)
            Log.w(TAG, "Chunk $chunkIndex rate-limited (HTTP ${rl.responseCode}); backing off ${waitMs}ms " +
                "(attempt ${attempt + 1}/$RATE_LIMIT_MAX_BACKOFF_RETRIES, escalation $escalation)")
            if (!sleepInterruptibly(waitMs, future, activeSession)) {
                throw IOException("Cancelled during rate-limit backoff")
            }
            if (future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future)
            } catch (e: Exception) {
                if (activeSession.abandoned.get() || future.isCancelled) throw IOException("Session abandoned or cancelled")
                lastException = e
                rl = e.findRateLimitException() ?: throw e
                activeSession.noteRateLimitHit()
                attempt++
            }
        }
        throw IOException(
            "Chunk $chunkIndex still rate-limited after $RATE_LIMIT_MAX_BACKOFF_RETRIES backoffs",
            lastException
        )
    }

    private fun rateLimitWaitMs(
        attempt: Int,
        escalation: Int,
        rl: HttpDataSource.InvalidResponseCodeException
    ): Long {
        val jitter = (Math.random() * RATE_LIMIT_BACKOFF_JITTER_MS).toLong()
        val header = rl.headerFields.entries
            .firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
            ?.value?.firstOrNull()?.trim()
        val headerMs = ParallelRangeRetryAfter.parseHeaderMs(header)
        if (headerMs != null) {
            return headerMs.coerceAtMost(RATE_LIMIT_WAIT_HARD_CAP_MS) + jitter
        }
        val cycleCapMs = (RATE_LIMIT_BACKOFF_CYCLE_CAP_MS shl escalation.coerceIn(0, RATE_LIMIT_ESCALATION_MAX))
            .coerceAtMost(RATE_LIMIT_WAIT_HARD_CAP_MS)
        val base = RATE_LIMIT_BACKOFF_BASE_MS shl (attempt + escalation).coerceIn(0, 6)
        return base.coerceIn(RATE_LIMIT_BACKOFF_BASE_MS, cycleCapMs) + jitter
    }

    private fun sleepInterruptibly(
        totalMs: Long,
        future: CompletableFuture<*>,
        activeSession: ChunkSession
    ): Boolean {
        var slept = 0L
        while (slept < totalMs) {
            if (future.isCancelled || activeSession.abandoned.get()) return false
            val slice = minOf(RATE_LIMIT_SLEEP_SLICE_MS, totalMs - slept)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                return false
            }
            slept += slice
        }
        return !(future.isCancelled || activeSession.abandoned.get())
    }

    /** Read from an already-opened DataSource into a pooled chunk buffer. */
    private fun readIntoChunk(
        activeSession: ChunkSession,
        ds: DataSource,
        future: CompletableFuture<*>,
        expectedBytes: Long
    ): DownloadedChunk {
        val buffer = acquireBuffer()
        val tempArray = readBufferLocal.get()!!
        var totalRead = 0
        var consecutiveZeroReads = 0
        try {
            val byteBufferReader = if (useNativeMemory && ds is androidx.media3.common.ByteBufferDataReader && ds.supportsByteBufferRead()) {
                ds
            } else {
                null
            }

            while (!activeSession.abandoned.get()) {
                if (future.isCancelled) {
                    throw IOException("Chunk download cancelled")
                }
                val maxRead = minOf(buffer.byteBuffer.capacity() - totalRead, READ_BUFFER_SIZE)
                if (maxRead <= 0) break

                val read = if (byteBufferReader != null) {
                    buffer.byteBuffer.position(totalRead)
                    byteBufferReader.read(buffer.byteBuffer, maxRead)
                } else {
                    val r = ds.read(tempArray, 0, maxRead)
                    if (r != C.RESULT_END_OF_INPUT) {
                        buffer.byteBuffer.position(totalRead)
                        buffer.byteBuffer.put(tempArray, 0, r)
                    }
                    r
                }

                if (read == C.RESULT_END_OF_INPUT) break
                if (read == 0) {
                    if (++consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                        throw IOException(
                            "No read progress after $MAX_CONSECUTIVE_ZERO_READS attempts " +
                                "(read $totalRead of $expectedBytes bytes)"
                        )
                    }
                } else {
                    consecutiveZeroReads = 0
                }
                totalRead += read
            }
            if (expectedBytes > 0L && totalRead < expectedBytes && !activeSession.abandoned.get()) {
                throw IOException("Short chunk: read $totalRead of $expectedBytes bytes")
            }
        } catch (e: Exception) {
            releaseBuffer(buffer)
            if (activeSession.abandoned.get()) throw IOException("Session abandoned")
            throw e
        }
        if (activeSession.abandoned.get()) {
            releaseBuffer(buffer)
            throw IOException("Session abandoned")
        }
        buffer.byteBuffer.flip()
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
        val wrapped = ByteBuffer.wrap(buffer, 0, totalRead)
        return DownloadedChunk(PooledBuffer(null, wrapped), totalRead)
    }

    private fun acquireBuffer(): PooledBuffer {
        val pool = globalBufferPool.computeIfAbsent(chunkSize) { ConcurrentLinkedDeque() }
        val buf = pool.pollLast()
        if (buf != null) {
            buf.byteBuffer.clear()
            return buf
        }
        return if (useNativeMemory) {
            val allocation = androidx.media3.exoplayer.upstream.DefaultAllocatorNative.createAllocation(chunkSize.toInt())
            val allocBuffer = allocation?.buffer
            if (allocation != null && allocBuffer != null) {
                PooledBuffer(allocation, allocBuffer)
            } else {
                PooledBuffer(null, ByteBuffer.allocateDirect(chunkSize.toInt()))
            }
        } else {
            PooledBuffer(null, ByteBuffer.allocate(chunkSize.toInt()))
        }
    }

    /**
     *   maxPoolSize in releaseBuffer only caps how many idle/recycled buffers are kept in the pool.
     *   If the pool is full, the released buffer is GC'd instead of recycled.
     */
    private fun releaseBuffer(buffer: PooledBuffer) {
        val pool = globalBufferPool.computeIfAbsent(chunkSize) { ConcurrentLinkedDeque() }
        if (pool.size < maxPoolSize) {
            pool.offerLast(buffer)
        } else {
            if (buffer.allocation != null) {
                androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buffer.allocation)
            } else if (buffer.byteBuffer.isDirect) {
                freeDirectBuffer(buffer.byteBuffer)
            }
        }
    }

    private fun resetLocalReadState() {
        currentChunk = null
        currentChunkIndex = -1
        currentChunkReadOffset = 0
        bootstrapChunk = null
        bootstrapStartPosition = C.TIME_UNSET
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            fallbackSource?.close()
            fallbackSource = null
            continuationSource?.close()
            continuationSource = null
            continuationEndPositionExclusive = C.TIME_UNSET

            resetLocalReadState()
            session = null

            val active = activeInstances.decrementAndGet()
            if (active <= 0) {
                val sessionLive = synchronized(sessionLock) {
                    currentChunkSession?.abandoned?.get() == false
                }
                if (!sessionLive) {
                    clearGlobalPool()
                }
            }
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun getUri(): Uri? = resolvedUri ?: fallbackSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        fallbackSource?.responseHeaders ?: emptyMap()

    override fun supportsByteBufferRead(): Boolean = true

    override fun read(buffer: ByteBuffer, length: Int): Int {
        fallbackSource?.let { source ->
            val temp = ByteArray(minOf(length, READ_BUFFER_SIZE))
            val read = source.read(temp, 0, temp.size)
            if (read > 0) {
                buffer.put(temp, 0, read)
                position += read
                bytesRemaining = (bytesRemaining - read).coerceAtLeast(0L)
            }
            return read
        }

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

        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
            bootstrapPrefetchDeferred = false
            scheduleChunks()
        }

        continuationSource?.let { source ->
            if (position < continuationEndPositionExclusive &&
                bytesRemaining > 0L &&
                (bootstrap == null || position >= bootstrapStartPosition + bootstrap.size)
            ) {
                val temp = ByteArray(minOf(toRead, READ_BUFFER_SIZE))
                val read = source.read(temp, 0, temp.size)
                if (read > 0) {
                    buffer.put(temp, 0, read)
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

        if (currentChunkIndex != chunkIndex || currentChunk == null) {
            val activeSession = session ?: return C.RESULT_END_OF_INPUT
            ensureChunkScheduled(chunkIndex)
            val future = activeSession.futures[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            noteSessionRead(activeSession, chunkIndex)
            try {
                currentChunk = future.get(60, TimeUnit.SECONDS)
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                if (activeSession.futures.remove(chunkIndex, future)) {
                    activeSession.lastTouch.remove(chunkIndex)
                    activeSession.pinnedSideChunks.remove(chunkIndex)
                    if (!future.cancel(true) && future.isDone && !future.isCancelled) {
                        try {
                            releaseSessionBuffer(future.get().buffer, activeSession.chunkSize, maxPoolSize)
                        } catch (_: Exception) {
                        }
                    }
                }
                throw IOException("Failed to download chunk $chunkIndex", e)
            }
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position % chunkSize).toInt()

            scheduleChunks()
        }

        val chunk = currentChunk ?: return C.RESULT_END_OF_INPUT
        val available = chunk.size - currentChunkReadOffset
        if (available <= 0) {
            if (chunk === bootstrapChunk) {
                bootstrapChunk = null
                bootstrapStartPosition = C.TIME_UNSET
            }
            currentChunk = null
            return read(buffer, length)
        }

        val readSize = minOf(toRead, available)
        val src = chunk.buffer.byteBuffer.duplicate()
        src.position(currentChunkReadOffset)
        src.limit(currentChunkReadOffset + readSize)
        buffer.put(src)
        
        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize
        bytesServedThisOpen += readSize
        noteSessionRead(session, chunkIndex)

        return readSize
    }

    /**
     * Factory for creating ParallelRangeDataSource instances.
     */
    class Factory(
        private val upstreamFactory: OkHttpDataSource.Factory,
        private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
        private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
        private val useNativeMemory: Boolean = false,
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
                useNativeMemory = useNativeMemory,
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
