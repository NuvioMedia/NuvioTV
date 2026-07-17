package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.data.local.PlayerSettings
import java.io.InterruptedIOException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    /**
     * MP4 non-faststart needs head + tail (moov) island before multi-ahead.
     * MKV/WebM/other progressive: unlock on head only — no tail moov gate.
     */
    private val preferTailMetadata: Boolean = false,
    private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
    private val onResolvedUri: (Uri?) -> Unit = {},
    private val consumeBootstrapCache: (DataSpec) -> BootstrapCacheEntry? = { null },
    private val updateBootstrapCache: (BootstrapCacheEntry?) -> Unit = {}
) : DataSource, androidx.media3.common.ByteBufferDataReader {

    companion object {
        private const val TAG = "ParallelRangeDS"
        // Bigger reads keep the pipe full under multi-cursor scatter.
        private const val READ_BUFFER_SIZE = 256 * 1024
        private const val BOOTSTRAP_READ_BYTES = 1L * 1024L * 1024L

        private val readBufferLocal = object : ThreadLocal<ByteArray>() {
            override fun initialValue(): ByteArray = ByteArray(READ_BUFFER_SIZE)
        }

        // CallerRuns so we never drop chunk work under bursty seek/scatter.
        private val sharedExecutor: ExecutorService by lazy {
            val threadFactory = ThreadFactory { runnable ->
                Thread(runnable, "parallel-ds-worker").apply {
                    priority = Thread.NORM_PRIORITY + 1
                    isDaemon = true
                }
            }
            ThreadPoolExecutor(
                16, 32, 60L, TimeUnit.SECONDS,
                java.util.concurrent.LinkedBlockingQueue(64),
                threadFactory,
                ThreadPoolExecutor.CallerRunsPolicy()
            ).apply {
                allowCoreThreadTimeOut(true)
            }
        }

        private val activeInstances = java.util.concurrent.atomic.AtomicInteger(0)
        private val globalBufferPool = ConcurrentHashMap<Long, ConcurrentLinkedDeque<PooledBuffer>>()

        // Don't Cleaner.clean — a reader may still hold a duplicate().
        private fun destroyPooledBuffer(buffer: PooledBuffer) {
            if (buffer.allocation != null) {
                try {
                    androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buffer.allocation)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to free native allocation: ${e.message}")
                }
            }
        }

        // Chunk futures live on the companion; Exo opens a new DS every seek.
        private const val RETAINED_SESSION_TTL_MS = 45_000L
        // Prefetch only after we actually served this much media.
        private const val EARNED_PREFETCH_BYTES = 512L * 1024L
        // Keep recently used chunks long enough for a slow range GET.
        private const val EVICTION_TOUCH_GUARD_MS = 12_000L
        // Don't re-GET chunks right next to the real playhead.
        private const val PLAYHEAD_CORRIDOR_CHUNKS = 3L
        // Hard cap on multi-ahead even if the user sets more connections.
        private const val MAX_PLAYHEAD_PREFETCH = 4
        // Tail chunks that usually hold non-faststart moov.
        private const val METADATA_TAIL_CHUNKS = 5
        // How long side scatter waits for a free slot while playhead is hot.
        private const val SIDE_SCATTER_PERMIT_WAIT_MS = 2_000L
        // MP4 session needs room for playhead + moov islands.
        private const val SINGLE_CONN_SESSION_CAP_LOW_RAM = 6
        private const val SINGLE_CONN_SESSION_CAP_HIGH_RAM = 8
        private const val MAX_CONSECUTIVE_ZERO_READS = 3

        // 429/503: back off, then clamp prefetch if it keeps happening.
        private const val RATE_LIMIT_MAX_BACKOFF_RETRIES = 3
        private const val RATE_LIMIT_CLAMP_THRESHOLD = 3
        private const val RATE_LIMIT_WINDOW_MS = 10_000L
        private const val RATE_LIMIT_BACKOFF_BASE_MS = 500L
        private const val RATE_LIMIT_BACKOFF_MAX_MS = 3_000L
        // Allow a longer wait when the server sends Retry-After.
        private const val RATE_LIMIT_BACKOFF_MAX_WITH_HEADER_MS = 30_000L
        private const val RATE_LIMIT_BACKOFF_JITTER_MS = 250L
        private const val RATE_LIMIT_SLEEP_SLICE_MS = 100L

        // One active progressive stream. Ranges keyed by (chunkSize, index) only.
        private class ChunkSession(
            @Volatile var requestUri: Uri,
            @Volatile var requestHeaders: Map<String, String>,
            val chunkSize: Long,
            val chunkCap: Int,
            maxInFlight: Int,
            /** True for MP4-family (moov often at tail). False for MKV/WebM/etc. */
            val preferTailMetadata: Boolean
        ) {
            @Volatile var resolvedUri: Uri? = null
            @Volatile var totalLength: Long = -1L
            val lastTouch = ConcurrentHashMap<Long, Long>()
            private val pinCounts = ConcurrentHashMap<Long, AtomicInteger>()
            // Head + moov/tail stay until stream change / idle release.
            val stickyIndices: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
            val rateLimited = AtomicBoolean(false)
            val rateLimit429s = AtomicInteger(0)
            @Volatile var rateLimitWindowStartMs: Long = 0L
            val activeSources: MutableSet<DataSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()
            @Volatile var lastUsedAtMs: Long = SystemClock.uptimeMillis()
            // Shared download slots (= parallel setting). Moov yields when playhead is hot.
            val downloadPermits = java.util.concurrent.Semaphore(maxInFlight.coerceAtLeast(1))
            val maxInFlightDownloads: Int = maxInFlight.coerceAtLeast(1)
            // Side scatter / moov should yield while this is set.
            @Volatile var playheadHotUntilMs: Long = 0L
            // Last media chunk we actually read (-1 until then).
            @Volatile var primaryPlayheadIndex: Long = -1L
            // True once a head/moov/tail chunk finished (or immediately for non-MP4).
            @Volatile var metadataFoundationReady: Boolean = false
            // Multi-ahead only after foundation is ready + a real media await (MP4).
            @Volatile var prefetchUnlocked: Boolean = false
            private val metadataIslandStarted = AtomicBoolean(false)
            val bytesServedTotal = java.util.concurrent.atomic.AtomicLong(0L)

            fun markPlayheadHot() {
                if (!metadataFoundationReady) return
                playheadHotUntilMs = SystemClock.uptimeMillis() + 12_000L
            }

            fun isPlayheadHot(): Boolean =
                metadataFoundationReady && SystemClock.uptimeMillis() < playheadHotUntilMs

            /**
             * MKV/WebM: no tail moov — open multi-ahead as soon as length is known.
             * Call after [totalLength] is set on open / warm attach.
             */
            fun ensureHeadFoundationReady() {
                if (preferTailMetadata) return
                if (metadataFoundationReady) {
                    prefetchUnlocked = true
                    return
                }
                metadataFoundationReady = true
                prefetchUnlocked = true
                if (primaryPlayheadIndex >= 0L) {
                    markPlayheadHot()
                }
            }

            fun updatePlayhead(chunkIndex: Long) {
                // Tail/moov scatter is not playhead. Chunk 0 is island-sticky AND start media
                // — skipping it left primaryPlayhead=-1 after moov so corridor never filled
                // (first-frame then immediate underrun on 4K).
                if (isTailMetadataIndex(chunkIndex)) return
                primaryPlayheadIndex = chunkIndex
                if (metadataFoundationReady) {
                    markPlayheadHot()
                    prefetchUnlocked = true
                }
            }

            fun onMetadataChunkReady(chunkIndex: Long) {
                if (!isMetadataIndex(chunkIndex)) return
                stickyIndices.add(chunkIndex)
                if (metadataFoundationReady) return
                val total = totalLength
                val ready = if (!preferTailMetadata) {
                    // Progressive MKV/WebM: head alone is enough to unlock multi-ahead.
                    chunkIndex == 0L || total <= 0L
                } else if (total <= 0L) {
                    true
                } else {
                    // Moov is at the tail — don't unlock mid-file ahead on head alone.
                    val lastIdx = (total - 1L) / chunkSize
                    val tailStart = (lastIdx - (METADATA_TAIL_CHUNKS - 1L)).coerceAtLeast(0L)
                    val isTail = chunkIndex >= tailStart
                    val isSmallFile = lastIdx <= METADATA_TAIL_CHUNKS
                    isTail || (chunkIndex == 0L && isSmallFile)
                }
                if (!ready) return
                metadataFoundationReady = true
                // Head already served before moov finished — open the corridor now.
                if (primaryPlayheadIndex >= 0L || !preferTailMetadata) {
                    if (primaryPlayheadIndex >= 0L) markPlayheadHot()
                    prefetchUnlocked = true
                }
            }

            fun isNearPlayhead(chunkIndex: Long): Boolean {
                val ph = primaryPlayheadIndex
                if (ph < 0L) return false
                return kotlin.math.abs(chunkIndex - ph) <= PLAYHEAD_CORRIDOR_CHUNKS
            }

            // Before foundation: metadata first. After: playhead corridor first.
            fun isHighPriorityDownload(chunkIndex: Long): Boolean {
                if (!metadataFoundationReady) {
                    return isMetadataIndex(chunkIndex)
                }
                // MP4: demote moov island once ready so playhead owns the pipe.
                // MKV/WebM: no island demotion — corridor only.
                if (preferTailMetadata && isMetadataIndex(chunkIndex)) return false
                val ph = primaryPlayheadIndex
                if (ph < 0L) return true
                return kotlin.math.abs(chunkIndex - ph) <= PLAYHEAD_CORRIDOR_CHUNKS
            }

            fun recordServedBytes(n: Int) {
                if (n <= 0) return
                if (bytesServedTotal.addAndGet(n.toLong()) >= EARNED_PREFETCH_BYTES &&
                    metadataFoundationReady
                ) {
                    prefetchUnlocked = true
                }
            }

            // Head + last N chunks (non-faststart moov). MKV: head only, no tail island.
            fun metadataIslandIndices(): List<Long> {
                if (!preferTailMetadata) return listOf(0L)
                val total = totalLength
                if (total <= 0L) return listOf(0L)
                val lastIdx = (total - 1L) / chunkSize
                val from = (lastIdx - (METADATA_TAIL_CHUNKS - 1L)).coerceAtLeast(0L)
                val out = ArrayList<Long>((METADATA_TAIL_CHUNKS + 1).coerceAtLeast(2))
                out.add(0L)
                var i = from
                while (i <= lastIdx) {
                    if (i != 0L) out.add(i)
                    i++
                }
                return out
            }

            fun tryBeginMetadataIslandPrefetch(): Boolean {
                // MKV/WebM: no tail moov island — playhead owns all slots immediately.
                if (!preferTailMetadata) return false
                if (metadataFoundationReady) return false
                if (totalLength <= 0L) return false
                return metadataIslandStarted.compareAndSet(false, true)
            }

            fun touch(chunkIndex: Long) {
                val now = SystemClock.uptimeMillis()
                lastTouch[chunkIndex] = now
                lastUsedAtMs = now
            }

            fun pin(chunkIndex: Long) {
                pinCounts.computeIfAbsent(chunkIndex) { AtomicInteger(0) }.incrementAndGet()
                touch(chunkIndex)
            }

            fun unpin(chunkIndex: Long) {
                pinCounts.computeIfPresent(chunkIndex) { _, counter ->
                    if (counter.decrementAndGet() <= 0) null else counter
                }
            }

            fun isPinned(chunkIndex: Long): Boolean =
                (pinCounts[chunkIndex]?.get() ?: 0) > 0

            fun isSticky(chunkIndex: Long): Boolean = stickyIndices.contains(chunkIndex)

            // Island / TCP priority: head + last N when MP4; head only otherwise.
            fun isMetadataIndex(chunkIndex: Long): Boolean {
                if (chunkIndex == 0L) return true
                if (!preferTailMetadata) return false
                val total = totalLength
                if (total <= 0L) return false
                val lastIdx = (total - 1L) / chunkSize
                return chunkIndex >= (lastIdx - 4L).coerceAtLeast(0L)
            }

            // Pure moov/tail scatter only — not chunk 0 (start-of-file media).
            fun isTailMetadataIndex(chunkIndex: Long): Boolean {
                if (!preferTailMetadata) return false
                val total = totalLength
                if (total <= 0L) return false
                val lastIdx = (total - 1L) / chunkSize
                return chunkIndex >= (lastIdx - 4L).coerceAtLeast(0L)
            }

            fun markStickyIfMetadata(chunkIndex: Long) {
                if (isMetadataIndex(chunkIndex)) stickyIndices.add(chunkIndex)
            }

            fun invalidateResolvedUri() {
                resolvedUri = null
            }
        }

        private data class RangeKey(val chunkSize: Long, val index: Long)

        private val rangeFutures =
            ConcurrentHashMap<RangeKey, CompletableFuture<DownloadedChunk>>()

        private val sessionLock = Any()
        private var currentChunkSession: ChunkSession? = null
        private val idleTeardownHandler = Handler(Looper.getMainLooper())
        private var pendingIdleTeardown: Runnable? = null

        private fun cancelIdleSessionTeardown() {
            synchronized(sessionLock) {
                pendingIdleTeardown?.let { idleTeardownHandler.removeCallbacks(it) }
                pendingIdleTeardown = null
            }
        }

        private fun scheduleIdleSessionTeardown() {
            synchronized(sessionLock) {
                pendingIdleTeardown?.let { idleTeardownHandler.removeCallbacks(it) }
                val session = currentChunkSession
                val lastUsed = session?.lastUsedAtMs ?: SystemClock.uptimeMillis()
                val remaining = (RETAINED_SESSION_TTL_MS -
                    (SystemClock.uptimeMillis() - lastUsed)).coerceAtLeast(0L)
                val runnable = Runnable {
                    sharedExecutor.execute {
                        synchronized(sessionLock) {
                            if (activeInstances.get() > 0) return@synchronized
                            val s = currentChunkSession
                            if (s != null && SystemClock.uptimeMillis() - s.lastUsedAtMs < RETAINED_SESSION_TTL_MS) {
                                return@synchronized
                            }
                            Log.d(TAG, "Idle session TTL expired; releasing chunk session")
                            currentChunkSession = null
                            clearAllRangeFutures()
                            clearGlobalPool()
                            pendingIdleTeardown = null
                        }
                    }
                }
                pendingIdleTeardown = runnable
                idleTeardownHandler.postDelayed(runnable, remaining)
            }
        }

        // Drop a ref. At zero: bump epoch, then pool or free.
        private fun releaseSessionBuffer(buffer: PooledBuffer, chunkSz: Long, poolCap: Int) {
            val left = buffer.refCount.decrementAndGet()
            if (left > 0) return
            if (left < 0) {
                Log.w(TAG, "PooledBuffer refCount went negative; ignoring")
                return
            }
            buffer.epoch.incrementAndGet()
            if (poolCap > 0) {
                val pool = globalBufferPool.computeIfAbsent(chunkSz) { ConcurrentLinkedDeque() }
                if (pool.size < poolCap) {
                    buffer.refCount.set(1)
                    buffer.byteBuffer.clear()
                    pool.offerLast(buffer)
                    return
                }
            }
            destroyPooledBuffer(buffer)
        }

        private fun isUsableFuture(f: CompletableFuture<DownloadedChunk>): Boolean {
            if (!f.isDone) return true
            return !f.isCompletedExceptionally && !f.isCancelled
        }

        private fun evictRange(
            session: ChunkSession,
            chunkIndex: Long,
            poolCap: Int
        ) {
            if (session.isPinned(chunkIndex) || session.isSticky(chunkIndex)) return
            val key = RangeKey(session.chunkSize, chunkIndex)
            val future = rangeFutures[key] ?: return
            // Never drop an in-flight GET (causes multi-start of the same range).
            if (!future.isDone) return
            if (future.isCancelled || future.isCompletedExceptionally) {
                rangeFutures.remove(key, future)
                session.lastTouch.remove(chunkIndex)
                return
            }
            if (!rangeFutures.remove(key, future)) return
            session.lastTouch.remove(chunkIndex)
            try {
                releaseSessionBuffer(future.get().buffer, session.chunkSize, poolCap)
            } catch (_: Exception) {
            }
        }

        private fun clearAllRangeFutures() {
            for ((key, future) in rangeFutures.entries.toList()) {
                try {
                    if (!future.isDone) {
                        future.cancel(true)
                    } else if (!future.isCancelled && !future.isCompletedExceptionally) {
                        try {
                            releaseSessionBuffer(future.get().buffer, key.chunkSize, 0)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
            rangeFutures.clear()
        }

        // Reuse session while hot. Don't clear ranges just because URI/headers changed.
        private fun obtainSession(
            requestUri: Uri,
            requestHeaders: Map<String, String>,
            chunkSz: Long,
            chunkCap: Int,
            maxInFlightDownloads: Int,
            preferTailMetadata: Boolean
        ): ChunkSession {
            cancelIdleSessionTeardown()
            synchronized(sessionLock) {
                val existing = currentChunkSession
                val now = SystemClock.uptimeMillis()
                if (existing != null && now - existing.lastUsedAtMs <= RETAINED_SESSION_TTL_MS) {
                    if (existing.chunkSize == chunkSz &&
                        existing.preferTailMetadata == preferTailMetadata
                    ) {
                        existing.lastUsedAtMs = now
                        existing.requestUri = requestUri
                        existing.requestHeaders = requestHeaders
                        return existing
                    }
                    // Chunk size / container policy changed.
                    currentChunkSession = null
                    clearAllRangeFutures()
                } else if (existing != null) {
                    currentChunkSession = null
                    clearAllRangeFutures()
                }
                val created = ChunkSession(
                    requestUri,
                    requestHeaders,
                    chunkSz,
                    chunkCap,
                    maxInFlightDownloads.coerceAtLeast(1),
                    preferTailMetadata = preferTailMetadata
                )
                currentChunkSession = created
                return created
            }
        }

        internal fun releaseRetainedSession() {
            cancelIdleSessionTeardown()
            synchronized(sessionLock) {
                currentChunkSession = null
                clearAllRangeFutures()
                clearGlobalPool()
            }
        }

        private fun enforceSessionCap(session: ChunkSession, protectIndex: Long, poolCap: Int) {
            val matching = {
                rangeFutures.keys.count { it.chunkSize == session.chunkSize }
            }
            if (matching() <= session.chunkCap) return
            while (matching() > session.chunkCap) {
                val now = SystemClock.uptimeMillis()
                val hardOver = matching() > session.chunkCap + 2
                val ph = session.primaryPlayheadIndex
                val victim = rangeFutures.keys
                    .asSequence()
                    .filter { it.chunkSize == session.chunkSize }
                    .map { it.index }
                    .filter { idx ->
                        // Keep the active read and playhead corridor.
                        kotlin.math.abs(idx - protectIndex) > PLAYHEAD_CORRIDOR_CHUNKS &&
                            (ph < 0L || kotlin.math.abs(idx - ph) > PLAYHEAD_CORRIDOR_CHUNKS)
                    }
                    .filter { !session.isPinned(it) && !session.isSticky(it) }
                    .filter { idx ->
                        val f = rangeFutures[RangeKey(session.chunkSize, idx)]
                        f != null && f.isDone && !f.isCancelled && !f.isCompletedExceptionally
                    }
                    .filter { hardOver || now - (session.lastTouch[it] ?: 0L) >= EVICTION_TOUCH_GUARD_MS }
                    .minByOrNull { idx ->
                        val touchAge = session.lastTouch[idx] ?: 0L
                        val minDistToHot = session.lastTouch.entries
                            .asSequence()
                            .filter { now - it.value < EVICTION_TOUCH_GUARD_MS }
                            .minOfOrNull { kotlin.math.abs(it.key - idx) }
                            ?: Long.MAX_VALUE
                        (if (minDistToHot > 2L) 0L else 1L) * 1_000_000_000_000L + touchAge
                    }
                    ?: return
                evictRange(session, victim, poolCap)
            }
        }

        internal fun sessionChunkCapFor(connections: Int): Int {
            return if (connections <= 1) {
                if (com.nuvio.tv.ui.screens.settings.MemoryBudget.isLowRamTier) {
                    SINGLE_CONN_SESSION_CAP_LOW_RAM
                } else {
                    SINGLE_CONN_SESSION_CAP_HIGH_RAM
                }
            } else {
                connections + if (com.nuvio.tv.ui.screens.settings.MemoryBudget.isLowRamTier) 2 else 4
            }
        }

        private fun clearGlobalPool() {
            globalBufferPool.values.forEach { pool ->
                while (true) {
                    val buf = pool.pollFirst() ?: break
                    destroyPooledBuffer(buf)
                }
            }
            globalBufferPool.clear()
            Log.d(TAG, "Cleared global buffer pool as all ParallelRangeDataSource instances are closed")
        }
    }

    init {
        activeInstances.incrementAndGet()
        cancelIdleSessionTeardown()
    }

    // Map + each reader hold a ref. Free at 0; epoch invalidates after recycle.
    private class PooledBuffer(
        val allocation: androidx.media3.exoplayer.upstream.Allocation?,
        val byteBuffer: ByteBuffer,
        val refCount: AtomicInteger = AtomicInteger(1),
        val epoch: AtomicInteger = AtomicInteger(0)
    ) {
        // false if already freed or recycled under a newer epoch.
        fun tryRetain(expectedEpoch: Int): Boolean {
            while (true) {
                if (epoch.get() != expectedEpoch) return false
                val c = refCount.get()
                if (c <= 0) return false
                if (refCount.compareAndSet(c, c + 1)) {
                    if (epoch.get() != expectedEpoch) {
                        refCount.decrementAndGet()
                        return false
                    }
                    return true
                }
            }
        }
    }

    private class DownloadedChunk(
        val buffer: PooledBuffer,
        val size: Int,
        val epoch: Int = buffer.epoch.get()
    )

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
    private val sessionChunkCap: Int = sessionChunkCapFor(parallelConnections)
    private val maxPoolSize: Int = sessionChunkCap

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
            bytesServedThisOpen = 0L
            session?.let { s -> s.lastUsedAtMs = SystemClock.uptimeMillis() }
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
        // Don't carry fallback/length from a previous open on this instance.
        fallbackSource?.close()
        fallbackSource = null
        totalFileLength = C.LENGTH_UNSET.toLong()
        bytesRemaining = C.LENGTH_UNSET.toLong()

        resetLocalReadState()
        bytesServedThisOpen = 0L

        // Warm session skips the probe.
        val attachedSession = obtainSession(
            dataSpec.uri,
            dataSpec.httpRequestHeaders,
            chunkSize,
            sessionChunkCap,
            maxInFlightDownloads = parallelConnections.coerceAtLeast(1),
            preferTailMetadata = preferTailMetadata
        )
        session = attachedSession
        val warmLength = attachedSession.totalLength
        val warmResolved = attachedSession.resolvedUri
        if (warmLength > 0L && warmResolved != null && dataSpec.position in 0 until warmLength) {
            resolvedUri = warmResolved
            onResolvedUri(resolvedUri)
            totalFileLength = warmLength
            val remaining = (totalFileLength - position).coerceAtLeast(0L)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, remaining)
            } else {
                remaining
            }
            bootstrapPrefetchDeferred = true
            // MKV/WebM: foundation ready immediately. MP4: only hot once moov foundation is ready.
            val openIdx = position / chunkSize
            attachedSession.ensureHeadFoundationReady()
            if (attachedSession.metadataFoundationReady &&
                !attachedSession.isTailMetadataIndex(openIdx) &&
                (!attachedSession.preferTailMetadata || !attachedSession.isMetadataIndex(openIdx))
            ) {
                attachedSession.markPlayheadHot()
            }
            val held = rangeFutures.keys.count { it.chunkSize == chunkSize }
            Log.d(
                TAG,
                "Attached to warm session for reopen at $position, " +
                    "file=${totalFileLength / 1024 / 1024}MB, held=$held chunk(s) (probe skipped)"
            )
            maybeKickMetadataPrefetch(attachedSession)
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
            attachedSession.ensureHeadFoundationReady()
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
            // No ranges or unknown length — single connection.
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
        val firstChunkIndex = position / chunkSize

        Log.d(
            TAG,
            "Parallel mode: ${parallelConnections} connections, ${chunkSize / 1024 / 1024}MB chunks, " +
                "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}, " +
                "tailMeta=$preferTailMetadata"
        )

        // MP4: prefetch head+moov island. MKV/WebM: unlock multi-ahead without tail GETs.
        attachedSession.ensureHeadFoundationReady()
        maybeKickMetadataPrefetch(attachedSession)

        // Small bootstrap window for startup / large seek reopens.
        if (openLength > 0L) {
            val bootstrapBytes = minOf(minOf(chunkSize, BOOTSTRAP_READ_BYTES), openLength).toInt()
            val chunk = readBootstrapChunk(probeSource, bootstrapBytes)
            bootstrapChunk = chunk
            bootstrapStartPosition = position
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
            adoptBootstrapChunk(bootstrap, chunkIndex, (position - bootstrapStartPosition).toInt())
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
            try {
                awaitChunk(chunkIndex, position % chunkSize)
            } catch (e: IOException) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                throw e
            }
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
            releaseCurrentChunkPin()
            return read(buffer, offset, length)
        }

        val readSize = minOf(toRead, available)
        // Shared buffer — use a duplicate so we don't move position.
        val readBuf = chunk.buffer.byteBuffer.duplicate()
        readBuf.position(currentChunkReadOffset)
        readBuf.get(buffer, offset, readSize)
        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize
        recordBytesServed(readSize)
        session?.touch(chunkIndex)

        return readSize
    }

    private fun recordBytesServed(n: Int) {
        if (n <= 0) return
        bytesServedThisOpen += n
        session?.recordServedBytes(n)
    }

    private fun awaitChunk(chunkIndex: Long, readOffsetInChunk: Long) {
        val activeSession = session ?: throw IOException("No chunk session")
        if (currentChunkIndex >= 0L && currentChunkIndex != chunkIndex) {
            releaseCurrentChunkPin()
        }
        // Tail/moov scatter must not move playhead; head (0) and mid-file do.
        val isPlayheadRead = !activeSession.isTailMetadataIndex(chunkIndex)
        if (isPlayheadRead) {
            activeSession.updatePlayhead(chunkIndex)
        }
        ensureChunkScheduled(chunkIndex)
        if (isPlayheadRead &&
            activeSession.metadataFoundationReady &&
            activeSession.prefetchUnlocked &&
            shouldAllowBackgroundPrefetch()
        ) {
            scheduleChunks()
        }
        val key = RangeKey(chunkSize, chunkIndex)
        val future = rangeFutures[key]
            ?: throw IOException("Chunk $chunkIndex was not scheduled")
        activeSession.pin(chunkIndex)
        try {
            val downloaded = future.get(60, TimeUnit.SECONDS)
            activeSession.touch(chunkIndex)
            // Keep buffer alive until unpin.
            if (!downloaded.buffer.tryRetain(downloaded.epoch)) {
                synchronized(sessionLock) {
                    rangeFutures.remove(key, future)
                    activeSession.lastTouch.remove(chunkIndex)
                }
                throw IOException("Chunk $chunkIndex buffer reclaimed; retry")
            }
            currentChunk = downloaded
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = readOffsetInChunk.toInt()
        } catch (e: Exception) {
            activeSession.unpin(chunkIndex)
            currentChunk = null
            currentChunkIndex = -1
            if (closed.get()) throw IOException("DataSource closed", e)
            // Don't cancel a shared in-flight download just because this DS failed.
            if (future.isDone && (future.isCompletedExceptionally || future.isCancelled)) {
                synchronized(sessionLock) {
                    if (rangeFutures.remove(key, future)) {
                        activeSession.lastTouch.remove(chunkIndex)
                    }
                }
            } else if (!future.isDone) {
                Log.w(
                    TAG,
                    "await chunk $chunkIndex failed while download continues: ${e.message}"
                )
            }
            if (e.findAuthOrGoneException() != null) {
                Log.w(TAG, "Auth/gone on chunk $chunkIndex; invalidating resolved URI for re-probe")
                activeSession.invalidateResolvedUri()
            }
            throw IOException("Failed to download chunk $chunkIndex", e)
        }
    }

    private fun adoptBootstrapChunk(bootstrap: DownloadedChunk, chunkIndex: Long, readOffset: Int) {
        if (currentChunkIndex >= 0L && currentChunkIndex != chunkIndex) {
            releaseCurrentChunkPin()
        } else if (currentChunkIndex == chunkIndex && currentChunk != null) {
            currentChunkReadOffset = readOffset
            return
        }
        val s = session
        s?.pin(chunkIndex)
        // Bootstrap at head is real start media — drive playhead/corridor when moov is ready.
        if (s != null && !s.isTailMetadataIndex(chunkIndex)) {
            s.updatePlayhead(chunkIndex)
            if (s.metadataFoundationReady && s.prefetchUnlocked && shouldAllowBackgroundPrefetch()) {
                scheduleChunks()
            }
        }
        // Bootstrap is local heap, not session-refcounted.
        currentChunk = bootstrap
        currentChunkIndex = chunkIndex
        currentChunkReadOffset = readOffset
    }

    private fun releaseCurrentChunkPin() {
        val chunk = currentChunk
        val idx = currentChunkIndex
        val s = session
        currentChunk = null
        currentChunkIndex = -1
        currentChunkReadOffset = 0
        if (chunk != null && chunk !== bootstrapChunk) {
            releaseSessionBuffer(chunk.buffer, chunkSize, maxPoolSize)
        }
        if (idx >= 0L && s != null) {
            s.unpin(idx)
        }
    }

    // MP4 only: head + tail (moov). MKV/WebM skips via tryBeginMetadataIslandPrefetch.
    private fun maybeKickMetadataPrefetch(activeSession: ChunkSession) {
        if (!shouldAllowBackgroundPrefetch()) return
        if (!activeSession.tryBeginMetadataIslandPrefetch()) return
        val indices = activeSession.metadataIslandIndices()
        Log.d(TAG, "Prefetch metadata island: ${indices.joinToString(",")}")
        for (idx in indices) {
            ensureChunkScheduled(idx)
        }
    }

    private fun scheduleChunks() {
        if (!shouldAllowBackgroundPrefetch()) return
        val activeSession = session
        // Prefer session playhead; this open might be a side scatter cursor.
        var sessionPh = activeSession?.primaryPlayheadIndex ?: -1L
        val openChunkIdx =
            if (continuationSource != null && continuationEndPositionExclusive != C.TIME_UNSET && position < continuationEndPositionExclusive) {
                continuationEndPositionExclusive / chunkSize
            } else {
                position / chunkSize
            }
        val foundation = activeSession?.metadataFoundationReady == true
        // After moov, a head/mid open with unset playhead still needs a corridor base.
        if (activeSession != null && foundation && sessionPh < 0L &&
            !activeSession.isTailMetadataIndex(openChunkIdx)
        ) {
            activeSession.updatePlayhead(openChunkIdx)
            sessionPh = openChunkIdx
        }
        val unlocked = foundation && activeSession != null && (
            activeSession.prefetchUnlocked ||
                bytesServedThisOpen >= EARNED_PREFETCH_BYTES ||
                activeSession.bytesServedTotal.get() >= EARNED_PREFETCH_BYTES
            )
        val nearOpen = activeSession != null && sessionPh >= 0L &&
            (activeSession.isNearPlayhead(openChunkIdx) || openChunkIdx == sessionPh)
        val multiCorridor = foundation && sessionPh >= 0L && unlocked && nearOpen &&
            activeSession?.rateLimited?.get() != true &&
            parallelConnections > 1
        // MP4: cap multi-ahead while moov/session policy is conservative.
        // MKV/WebM: beta-style N+1 ahead for high-bitrate progressive (no moov gate).
        val maxAhead = if (multiCorridor) {
            if (activeSession?.preferTailMetadata == true) {
                parallelConnections.coerceIn(1, MAX_PLAYHEAD_PREFETCH)
            } else {
                (parallelConnections + 1).coerceAtLeast(2)
            }
        } else {
            1
        }
        val baseIdx = if (multiCorridor) sessionPh else openChunkIdx

        for (i in 0 until maxAhead) {
            val ci = baseIdx + i
            if (totalFileLength != C.LENGTH_UNSET.toLong() && ci * chunkSize >= totalFileLength) break
            ensureChunkScheduled(ci)
        }
    }

    private fun ensureChunkScheduled(chunkIndex: Long) {
        val activeSession = session ?: return
        val key = RangeKey(chunkSize, chunkIndex)

        // One owner per (chunkSize, index) — don't start a second GET.
        val scheduled: CompletableFuture<DownloadedChunk>? = synchronized(sessionLock) {
            val existing = rangeFutures[key]
            if (existing != null) {
                if (isUsableFuture(existing)) {
                    activeSession.touch(chunkIndex)
                    return@synchronized null
                }
                // Dead entry only.
                Log.w(
                    TAG,
                    "Replacing dead future idx=$chunkIndex " +
                        "cancelled=${existing.isCancelled} exceptional=${existing.isCompletedExceptionally}"
                )
                rangeFutures.remove(key, existing)
            }

            enforceSessionCap(activeSession, protectIndex = chunkIndex, poolCap = maxPoolSize)
            // Sticky before the GET so eviction can't drop it mid-download.
            activeSession.markStickyIfMetadata(chunkIndex)
            val future = CompletableFuture<DownloadedChunk>()
            rangeFutures[key] = future
            activeSession.pin(chunkIndex)
            activeSession.touch(chunkIndex)
            future
        }
        if (scheduled == null) return

        Log.d(TAG, "Scheduling chunk $chunkIndex")
        sharedExecutor.execute {
            val t0 = SystemClock.elapsedRealtime()
            val sessionNow = currentChunkSession ?: activeSession
            val isMeta = sessionNow.isMetadataIndex(chunkIndex)
            val highPriority = sessionNow.isHighPriorityDownload(chunkIndex)
            val waitSec = when {
                highPriority -> 60L
                isMeta && sessionNow.isPlayheadHot() -> 5L
                !highPriority && sessionNow.isPlayheadHot() ->
                    (SIDE_SCATTER_PERMIT_WAIT_MS / 1000L).coerceAtLeast(1L)
                isMeta -> 30L
                else -> 20L
            }
            var acquired = false
            try {
                if (rangeFutures[key] !== scheduled || scheduled.isCancelled) {
                    Log.d(TAG, "Skip superseded/cancelled chunk $chunkIndex before start")
                    return@execute
                }
                acquired = acquireDownloadPermit(sessionNow, highPriority, key, scheduled, waitSec)
                if (!acquired) {
                    if (rangeFutures[key] !== scheduled || scheduled.isCancelled) {
                        Log.d(TAG, "Skip superseded/cancelled chunk $chunkIndex while waiting slot")
                        return@execute
                    }
                    // Yield the slot; a later read can reschedule.
                    scheduled.completeExceptionally(IOException("Timed out waiting for download slot"))
                    synchronized(sessionLock) {
                        rangeFutures.remove(key, scheduled)
                    }
                    return@execute
                }
                if (rangeFutures[key] !== scheduled || scheduled.isCancelled) {
                    Log.d(TAG, "Skip superseded/cancelled chunk $chunkIndex after permit")
                    return@execute
                }
                val result = downloadChunk(sessionNow, chunkIndex, scheduled)
                if (sessionNow.isMetadataIndex(chunkIndex)) {
                    sessionNow.onMetadataChunkReady(chunkIndex)
                } else {
                    sessionNow.markStickyIfMetadata(chunkIndex)
                }
                val ms = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                val mbps = (result.size * 8.0 / 1_000_000.0) / (ms / 1000.0)
                Log.d(
                    TAG,
                    "Successfully downloaded chunk $chunkIndex, size=${result.size} bytes " +
                        "in ${ms}ms (${String.format(java.util.Locale.US, "%.1f", mbps)} Mbps)"
                )
                if (!scheduled.complete(result)) {
                    releaseBuffer(result.buffer)
                }
                if (sessionNow.metadataFoundationReady &&
                    sessionNow.prefetchUnlocked &&
                    shouldAllowBackgroundPrefetch()
                ) {
                    scheduleChunks()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Chunk $chunkIndex download failed: ${e.message}")
                if (!scheduled.isDone) scheduled.completeExceptionally(e)
                synchronized(sessionLock) {
                    if (rangeFutures[key] === scheduled) {
                        rangeFutures.remove(key, scheduled)
                    }
                }
            } finally {
                if (acquired) {
                    sessionNow.downloadPermits.release()
                }
                if (!sessionNow.isSticky(chunkIndex)) {
                    sessionNow.unpin(chunkIndex)
                }
            }
        }
    }

    // High-priority waits fully; moov/side scatter yields when playhead is hot.
    private fun acquireDownloadPermit(
        session: ChunkSession,
        highPriority: Boolean,
        key: RangeKey,
        scheduled: CompletableFuture<*>,
        waitSec: Long
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + waitSec * 1000L
        val yieldToPlayhead = !highPriority &&
            (session.isPlayheadHot() || session.primaryPlayheadIndex >= 0L)
        while (SystemClock.uptimeMillis() < deadline) {
            if (rangeFutures[key] !== scheduled || scheduled.isCancelled) {
                return false
            }
            if (yieldToPlayhead) {
                if (session.downloadPermits.tryAcquire()) {
                    return true
                }
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return false
                }
                continue
            }
            if (session.downloadPermits.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                return true
            }
        }
        return false
    }

    private fun downloadChunk(activeSession: ChunkSession, chunkIndex: Long, future: CompletableFuture<*>): DownloadedChunk {
        var lastException: Exception? = null
        for (attempt in 0..1) {
            if (future.isCancelled) throw IOException("Cancelled")
            try {
                val result = downloadChunkOnce(activeSession, chunkIndex, future)
                maybeClearRateLimitClamp(activeSession)
                return result
            } catch (e: Exception) {
                if (future.isCancelled) throw IOException("Cancelled")
                lastException = e
                val rlError = e.findRateLimitException()
                if (rlError != null) {
                    return downloadChunkWithRateLimitBackoff(activeSession, chunkIndex, future, rlError)
                }
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
            val specBuilder = DataSpec.Builder()
                .setUri(uri)
                .setPosition(start)
                .setLength(end - start)
            if (activeSession.requestHeaders.isNotEmpty()) {
                specBuilder.setHttpRequestHeaders(activeSession.requestHeaders)
            }
            val spec = specBuilder.build()

            if (future.isCancelled) throw IOException("Cancelled")
            // Newer schedule owns this range.
            val key = RangeKey(activeSession.chunkSize, chunkIndex)
            if (rangeFutures[key] !== future) {
                throw IOException("Superseded chunk $chunkIndex")
            }
            Log.d(TAG, "Starting chunk download: idx=$chunkIndex, range=$start-$end")
            try {
                ds.open(spec)
            } catch (e: Exception) {
                if (e.findAuthOrGoneException() != null) {
                    Log.w(
                        TAG,
                        "Auth/gone opening chunk $chunkIndex against ${uri.host}; " +
                            "invalidating resolved URI for re-probe"
                    )
                    activeSession.invalidateResolvedUri()
                }
                throw e
            }
            // Known length — short responses must fail, don't cache a partial.
            val expectedBytes = if (sessionLength > 0L) end - start else -1L
            return readIntoChunk(activeSession, ds, future, expectedBytes)
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
        return findHttpResponseException { code -> code == 429 || code == 503 }
    }

    private fun Throwable.findAuthOrGoneException(): HttpDataSource.InvalidResponseCodeException? {
        return findHttpResponseException { code -> code == 401 || code == 403 || code == 410 }
    }

    private fun Throwable.findHttpResponseException(
        match: (Int) -> Boolean
    ): HttpDataSource.InvalidResponseCodeException? {
        var cause: Throwable? = this
        var depth = 0
        while (cause != null && depth < 6) {
            val c = cause
            if (c is HttpDataSource.InvalidResponseCodeException && match(c.responseCode)) {
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
        var attempt = 0
        while (attempt < RATE_LIMIT_MAX_BACKOFF_RETRIES) {
            recordRateLimitHit(activeSession, rl.responseCode)
            val waitMs = rateLimitBackoffMs(attempt, rl)
            Log.w(TAG, "Chunk $chunkIndex rate-limited (HTTP ${rl.responseCode}); backing off ${waitMs}ms " +
                "(attempt ${attempt + 1}/$RATE_LIMIT_MAX_BACKOFF_RETRIES)")
            if (!sleepInterruptibly(waitMs, future, activeSession)) throw IOException("Cancelled during rate-limit backoff")
            if (future.isCancelled) throw IOException("Cancelled")
            try {
                val result = downloadChunkOnce(activeSession, chunkIndex, future)
                maybeClearRateLimitClamp(activeSession)
                return result
            } catch (e: Exception) {
                if (future.isCancelled) throw IOException("Cancelled")
                lastException = e
                rl = e.findRateLimitException() ?: throw e
                attempt++
            }
        }
        throw IOException("Chunk $chunkIndex still rate-limited after $RATE_LIMIT_MAX_BACKOFF_RETRIES backoffs", lastException)
    }

    private fun recordRateLimitHit(activeSession: ChunkSession, responseCode: Int) {
        val now = SystemClock.uptimeMillis()
        val hits = synchronized(activeSession) {
            if (now - activeSession.rateLimitWindowStartMs > RATE_LIMIT_WINDOW_MS) {
                activeSession.rateLimitWindowStartMs = now
                activeSession.rateLimit429s.set(1)
                1
            } else {
                activeSession.rateLimit429s.incrementAndGet()
            }
        }
        if (hits >= RATE_LIMIT_CLAMP_THRESHOLD &&
            activeSession.rateLimited.compareAndSet(false, true)
        ) {
            Log.w(
                TAG,
                "Rate-limited (HTTP $responseCode) $hits times in ${RATE_LIMIT_WINDOW_MS}ms window; " +
                    "clamping session to single connection"
            )
        }
    }

    // Clear single-conn clamp after a quiet window.
    private fun maybeClearRateLimitClamp(activeSession: ChunkSession) {
        if (!activeSession.rateLimited.get()) return
        val now = SystemClock.uptimeMillis()
        if (now - activeSession.rateLimitWindowStartMs < RATE_LIMIT_WINDOW_MS) return
        if (activeSession.rateLimited.compareAndSet(true, false)) {
            activeSession.rateLimit429s.set(0)
            Log.i(TAG, "Rate-limit quiet window elapsed; restoring multi-connection prefetch")
        }
    }

    private fun rateLimitBackoffMs(attempt: Int, rl: HttpDataSource.InvalidResponseCodeException): Long {
        val rawHeader = rl.headerFields.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()?.trim()
        val retryAfterMs = ParallelRangeRetryAfter.parseHeaderMs(rawHeader)
        val hasHeader = !rawHeader.isNullOrEmpty()
        val maxCap = if (hasHeader) RATE_LIMIT_BACKOFF_MAX_WITH_HEADER_MS else RATE_LIMIT_BACKOFF_MAX_MS
        val base = when {
            retryAfterMs != null -> retryAfterMs
            hasHeader -> RATE_LIMIT_BACKOFF_MAX_WITH_HEADER_MS
            else -> RATE_LIMIT_BACKOFF_BASE_MS shl attempt.coerceIn(0, 3)
        }
        val capped = base.coerceIn(RATE_LIMIT_BACKOFF_BASE_MS, maxCap)
        return capped + (Math.random() * RATE_LIMIT_BACKOFF_JITTER_MS).toLong()
    }

    // Short slices so cancel can abort the backoff.
    private fun sleepInterruptibly(
        totalMs: Long,
        future: CompletableFuture<*>,
        activeSession: ChunkSession
    ): Boolean {
        var slept = 0L
        while (slept < totalMs) {
            if (future.isCancelled) return false
            val slice = minOf(RATE_LIMIT_SLEEP_SLICE_MS, totalMs - slept)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                return false
            }
            slept += slice
        }
        return !future.isCancelled
    }

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

            // Only future cancel stops this — downloads outlive seek reopens.
            while (!future.isCancelled) {
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
            if (future.isCancelled) throw IOException("Chunk download cancelled")
            if (expectedBytes > 0L && totalRead < expectedBytes) {
                throw IOException("Short chunk: read $totalRead of $expectedBytes bytes")
            }
        } catch (e: Exception) {
            releaseBuffer(buffer)
            throw e
        }
        buffer.byteBuffer.flip()
        return DownloadedChunk(buffer, totalRead)
    }

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
            if (buf.refCount.get() != 1) {
                buf.refCount.set(1)
            }
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

    private fun releaseBuffer(buffer: PooledBuffer) {
        releaseSessionBuffer(buffer, chunkSize, maxPoolSize)
    }

    private fun resetLocalReadState() {
        releaseCurrentChunkPin()
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

            // Session downloads keep running; only detach this instance.
            resetLocalReadState()
            session = null

            val active = activeInstances.decrementAndGet()
            if (active <= 0) {
                scheduleIdleSessionTeardown()
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
            adoptBootstrapChunk(bootstrap, chunkIndex, (position - bootstrapStartPosition).toInt())
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
            try {
                awaitChunk(chunkIndex, position % chunkSize)
            } catch (e: IOException) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                throw e
            }
            scheduleChunks()
        }

        val chunk = currentChunk ?: return C.RESULT_END_OF_INPUT
        val available = chunk.size - currentChunkReadOffset
        if (available <= 0) {
            if (chunk === bootstrapChunk) {
                bootstrapChunk = null
                bootstrapStartPosition = C.TIME_UNSET
            }
            releaseCurrentChunkPin()
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
        recordBytesServed(readSize)
        session?.touch(chunkIndex)

        return readSize
    }

    class Factory(
        private val upstreamFactory: OkHttpDataSource.Factory,
        private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
        private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
        private val useNativeMemory: Boolean = false,
        /** MP4-family non-faststart moov island; false for MKV/WebM progressive. */
        private val preferTailMetadata: Boolean = false,
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
                preferTailMetadata = preferTailMetadata,
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
