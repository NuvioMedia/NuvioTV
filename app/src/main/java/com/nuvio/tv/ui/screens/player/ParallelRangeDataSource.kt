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
import java.util.LinkedHashMap

@UnstableApi
internal class ParallelRangeDataSource(
    private val upstreamFactory: OkHttpDataSource.Factory,
    private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
    private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
    private val useNativeMemory: Boolean = false,

    private val prefetchDepthChunks: Int = parallelConnections + 1,
    private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
    private val onResolvedUri: (Uri?) -> Unit = {},
    private val consumeBootstrapCache: (DataSpec) -> BootstrapCacheEntry? = { null },
    private val updateBootstrapCache: (BootstrapCacheEntry?) -> Unit = {},

    private val allowContinuationReopen: Boolean = true
) : DataSource, androidx.media3.common.ByteBufferDataReader {

    companion object {
        private const val TAG = "ParallelRangeDS"
        private const val READ_BUFFER_SIZE = 64 * 1024

        private const val BOOTSTRAP_READ_BYTES = 256L * 1024L

        private const val IN_FLIGHT_WAIT_CAP_MS = 3_000L
        private const val IN_FLIGHT_POLL_MS = 2L

        private const val HEDGE_MIN_OPEN_MS = 1_500L
        private const val HEDGE_WINDOW_MS = 2_000L
        private const val HEDGE_STALL_RATE_BPS = 256L * 1024L
        private const val HEDGE_MAX_RESTARTS = 3

        private const val HEDGE_STALL_RATE_CDN = 256L * 1024L
        private const val HEDGE_STALL_RATE_USENET = 128L * 1024L
        private const val HEDGE_WINDOWS_CDN = 1
        private const val HEDGE_WINDOWS_USENET = 2

        private val readBufferLocal = object : ThreadLocal<ByteArray>() {
            override fun initialValue(): ByteArray = ByteArray(READ_BUFFER_SIZE)
        }

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

        private const val EVICTION_TOUCH_GUARD_MS = 2_000L

        private const val MAX_CONSECUTIVE_ZERO_READS = 3

        private const val ESCALATE_AFTER_MS = 2_000L
        private const val ESCALATE_POLL_EXTENSION_MS = 3_000L

        private const val RATE_LIMIT_MAX_BACKOFF_RETRIES = 3
        private const val RATE_LIMIT_BACKOFF_BASE_MS = 500L

        private const val RATE_LIMIT_BACKOFF_CYCLE_CAP_MS = 3_000L

        private const val RATE_LIMIT_WAIT_HARD_CAP_MS = 15_000L
        private const val RATE_LIMIT_BACKOFF_JITTER_MS = 250L
        private const val RATE_LIMIT_SLEEP_SLICE_MS = 100L

        private const val RATE_LIMIT_DEPTH_STEP_BASE_MS = 10_000L
        private const val RATE_LIMIT_DEPTH_STEP_MAX_MS = 60_000L
        private const val RATE_LIMIT_ESCALATION_MAX = 5

        @Volatile var hudClampLatched: Boolean = false
        @Volatile var hudClampTrips: Int = 0
        @Volatile var hudClampLastHitAtMs: Long = 0L

        @Volatile var hudDepthCap: Int = 0
        @Volatile var hudDepthConfigured: Int = 0
        @Volatile var hudNextStepAtMs: Long = 0L

        @Volatile var hudHedgeRestarts: Int = 0
        @Volatile var hudHedgeExhausted: Boolean = false

        private val obsAnnounced = AtomicBoolean(false)

        fun hudClampCooldownRemainingMs(nowUptimeMs: Long): Long {
            if (!hudClampLatched) return 0L
            return (hudNextStepAtMs - nowUptimeMs).coerceAtLeast(0L)
        }

        private class ChunkSession(
            val requestUri: Uri,

            @Volatile var requestHeaders: Map<String, String>,
            val chunkSize: Long,
            val chunkCap: Int,

            val prefetchWindow: Int
        ) {
            @Volatile var resolvedUri: Uri? = null
            @Volatile var totalLength: Long = -1L
            val futures = ConcurrentHashMap<Long, CompletableFuture<DownloadedChunk>>()
            val lastTouch = ConcurrentHashMap<Long, Long>()
            val abandoned = AtomicBoolean(false)

            val escalatedChunks: MutableSet<Long> = ConcurrentHashMap.newKeySet()

            val rateLimitDepthCap = AtomicInteger(Int.MAX_VALUE)
            @Volatile var lastRateLimitAtMs: Long = 0L
            @Volatile var lastDepthHalveAtMs: Long = 0L
            @Volatile var lastDepthStepAtMs: Long = 0L
            @Volatile var depthStepIntervalMs: Long = RATE_LIMIT_DEPTH_STEP_BASE_MS
            val rateLimitEscalation = AtomicInteger(0)
            val activeSources: MutableSet<DataSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()

            val inFlight = ConcurrentHashMap<Long, InFlightChunk>()
            @Volatile var lastUsedAtMs: Long = SystemClock.uptimeMillis()

            fun touch(chunkIndex: Long) {
                val now = SystemClock.uptimeMillis()
                lastTouch[chunkIndex] = now
                lastUsedAtMs = now
            }

            @Volatile var lastReadChunkIndex: Long = -1L

            fun noteRead(chunkIndex: Long) {
                touch(chunkIndex)
                lastReadChunkIndex = chunkIndex
            }

            fun noteRateLimitHit() {
                val now = SystemClock.uptimeMillis()
                lastRateLimitAtMs = now
                hudClampLastHitAtMs = now
                if (hudClampLatched) hudNextStepAtMs = now + depthStepIntervalMs
            }

            fun beginRateLimitEpisode(configuredDepth: Int): Int {
                val now = SystemClock.uptimeMillis()
                lastRateLimitAtMs = now
                hudClampLastHitAtMs = now
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
                        hudClampLatched = true
                        hudClampTrips += 1
                        hudDepthCap = halved
                        hudDepthConfigured = configuredDepth
                        hudNextStepAtMs = now + depthStepIntervalMs
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
                            hudClampLatched = false
                            hudDepthCap = 0
                            hudNextStepAtMs = 0L
                            Log.i(TAG, "Rate-limit depth cap cleared; parallel prefetch fully restored")
                        } else {
                            hudDepthCap = stepped
                            hudNextStepAtMs = now + depthStepIntervalMs
                            Log.i(TAG, "Rate-limit quiet; prefetch depth stepped to $stepped/$configuredDepth")
                        }
                    }
                }
                return rateLimitDepthCap.get().coerceAtMost(configuredDepth)
            }
        }

        private val sessionLock = Any()
        private var currentChunkSession: ChunkSession? = null

        private var pendingChunkSession: ChunkSession? = null

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
            session.inFlight.clear()
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
                    currentChunkSession = null
                }

                val pending = pendingChunkSession
                if (pending != null) {
                    val pendingFresh = SystemClock.uptimeMillis() - pending.lastUsedAtMs <= RETAINED_SESSION_TTL_MS

                    val pendingMatches = pendingFresh && !pending.abandoned.get() &&
                        pending.requestUri == requestUri && pending.chunkSize == chunkSz
                    pendingChunkSession = null
                    if (pendingMatches) {
                        Log.i(
                            TAG,
                            "PRESTART: adopted pre-started session, chunk(s) held=${pending.futures.size} " +
                                "headerRekey=${pending.requestHeaders.keys.sorted()}->${requestHeaders.keys.sorted()}"
                        )

                        pending.requestHeaders = requestHeaders
                        pending.lastUsedAtMs = SystemClock.uptimeMillis()
                        currentChunkSession = pending
                        return pending
                    }
                    Log.i(
                        TAG,
                        "PRESTART: pre-started session discarded (no match at open) " +
                            "uriMatch=${pending.requestUri == requestUri} " +
                            "chunkMatch=${pending.chunkSize == chunkSz} " +
                            "headerMatch=${pending.requestHeaders == requestHeaders} " +
                            "fresh=$pendingFresh abandoned=${pending.abandoned.get()} " +
                            "pendingChunk=${pending.chunkSize} openChunk=$chunkSz " +
                            "pendingHeaderKeys=${pending.requestHeaders.keys.sorted()} " +
                            "openHeaderKeys=${requestHeaders.keys.sorted()} " +
                            "pendingHost=${pending.requestUri.host} openHost=${requestUri.host} " +
                            "pendingScheme=${pending.requestUri.scheme} openScheme=${requestUri.scheme} " +
                            "pendingPathLen=${pending.requestUri.path?.length ?: -1} " +
                            "openPathLen=${requestUri.path?.length ?: -1} " +
                            "pendingQueryLen=${pending.requestUri.query?.length ?: -1} " +
                            "openQueryLen=${requestUri.query?.length ?: -1} " +
                            "pendingUriLen=${pending.requestUri.toString().length} " +
                            "openUriLen=${requestUri.toString().length}"
                    )
                    teardownSessionLocked(pending, poolCap)
                }

                hudClampLatched = false
                hudClampTrips = 0
                hudClampLastHitAtMs = 0L
                hudDepthCap = 0
                hudDepthConfigured = 0
                hudNextStepAtMs = 0L
                hudHedgeRestarts = 0
                hudHedgeExhausted = false
                val created = ChunkSession(requestUri, requestHeaders, chunkSz, chunkCap, prefetchWindow)
                currentChunkSession = created
                return created
            }
        }

        internal fun releaseRetainedSession() {
            synchronized(sessionLock) {
                currentChunkSession?.let { teardownSessionLocked(it, poolCap = 0) }
                currentChunkSession = null

                pendingChunkSession?.let { teardownSessionLocked(it, poolCap = 0) }
                pendingChunkSession = null
            }
        }

        private fun obtainPendingSession(
            requestUri: Uri,
            requestHeaders: Map<String, String>,
            chunkSz: Long,
            chunkCap: Int,
            poolCap: Int,
            prefetchWindow: Int
        ): ChunkSession? {
            synchronized(sessionLock) {
                val live = currentChunkSession
                if (live != null && !live.abandoned.get() && live.requestUri == requestUri &&
                    live.chunkSize == chunkSz && live.requestHeaders == requestHeaders
                ) {
                    return null
                }
                val existingPending = pendingChunkSession
                if (existingPending != null) {
                    if (!existingPending.abandoned.get() && existingPending.requestUri == requestUri &&
                        existingPending.chunkSize == chunkSz && existingPending.requestHeaders == requestHeaders
                    ) {
                        return null
                    }
                    teardownSessionLocked(existingPending, poolCap)
                }
                val created = ChunkSession(requestUri, requestHeaders, chunkSz, chunkCap, prefetchWindow)
                pendingChunkSession = created
                return created
            }
        }

        internal fun drainIdleBuffers(chunkSize: Long) {
            val pool = globalBufferPool[chunkSize] ?: return
            while (true) {
                val buf = pool.pollLast() ?: break
                if (buf.allocation != null) {
                    androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buf.allocation)
                } else if (buf.byteBuffer.isDirect) {
                    freeDirectBuffer(buf.byteBuffer)
                }
            }
        }

        private fun enforceSessionCap(session: ChunkSession, protectIndex: Long, poolCap: Int) {
            if (session.futures.size <= session.chunkCap) return
            synchronized(session) {
                while (session.futures.size > session.chunkCap) {
                    val now = SystemClock.uptimeMillis()

                    val hardOver = session.futures.size > session.chunkCap + 2

                    val readerIdx = session.lastReadChunkIndex
                    val eligible = session.futures.keys
                        .filter { it != protectIndex }
                        .filter { hardOver || now - (session.lastTouch[it] ?: 0L) >= EVICTION_TOUCH_GUARD_MS }

                    val nearAheadFloor = if (readerIdx >= 0L) readerIdx else Long.MIN_VALUE
                    val nearAheadCeil = if (readerIdx >= 0L) readerIdx + session.prefetchWindow else Long.MIN_VALUE
                    val evictable = eligible.filter { it < nearAheadFloor || it > nearAheadCeil }
                    val victim = evictable
                        .filter { readerIdx >= 0L && it < readerIdx }
                        .minByOrNull { session.lastTouch[it] ?: 0L }
                        ?: evictable.maxOrNull()
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

    private class PooledBuffer(
        val allocation: androidx.media3.exoplayer.upstream.Allocation?,
        val byteBuffer: ByteBuffer
    )

    private class DownloadedChunk(val buffer: PooledBuffer, val size: Int)

    private class InFlightChunk(buffer: PooledBuffer) {
        val lock = Any()
        var buffer: PooledBuffer? = buffer
        @Volatile var watermark: Int = 0
    }

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

    private val effectivePrefetchDepth: Int =
        prefetchDepthChunks.coerceAtLeast(parallelConnections + 1)

    private val maxPoolSize = effectivePrefetchDepth + 2

    private var currentChunk: DownloadedChunk? = null
    private var currentChunkIndex: Long = -1
    private var currentChunkReadOffset: Int = 0
    private var bootstrapPrefetchDeferred: Boolean = false
    private var bootstrapChunk: DownloadedChunk? = null
    private var bootstrapStartPosition: Long = C.TIME_UNSET
    private var continuationSource: OkHttpDataSource? = null
    private var continuationEndPositionExclusive: Long = C.TIME_UNSET

    private var pendingContinuationOpen: Boolean = false

    private val transferListeners = mutableListOf<TransferListener>()

    private var fallbackSource: OkHttpDataSource? = null

    private var session: ChunkSession? = null

    private var bytesServedThisOpen: Long = 0L
    private var inFlightServeLogged: Boolean = false

    private val sessionChunkCap: Int = effectivePrefetchDepth +
        if (com.nuvio.tv.ui.screens.settings.MemoryBudget.isLowRamTier) 2 else 4

    override fun open(dataSpec: DataSpec): Long {
        val isSubtitle = dataSpec.uri.getQueryParameter("nuvio_type") == "subtitle"
        if (isSubtitle) {
            closed.set(false)
            resetLocalReadState()

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
        pendingContinuationOpen = false

        fallbackSource?.close()
        fallbackSource = null
        totalFileLength = C.LENGTH_UNSET.toLong()
        bytesRemaining = C.LENGTH_UNSET.toLong()

        resetLocalReadState()
        bytesServedThisOpen = 0L

        val attachedSession = obtainSession(dataSpec.uri, dataSpec.httpRequestHeaders, chunkSize, sessionChunkCap, maxPoolSize, effectivePrefetchDepth)
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

            val cachedTail = PrefetchWindowStore.peekTail(dataSpec.uri, position)
            if (cachedTail != null) {
                bootstrapChunk = DownloadedChunk(
                    PooledBuffer(null, ByteBuffer.wrap(cachedTail.bootstrapData)),
                    cachedTail.bootstrapSize
                )
                bootstrapStartPosition = cachedTail.startPosition
                pendingContinuationOpen = false
            } else {

                pendingContinuationOpen = allowContinuationReopen &&
                    attachedSession.futures[position / chunkSize] == null
            }
            Log.d(
                TAG,
                "Attached to warm session for reopen at $position, " +
                    "file=${totalFileLength / 1024 / 1024}MB, held=${attachedSession.futures.size} chunk(s) (probe skipped)"
            )
            return bytesRemaining
        }

        (consumeBootstrapCache(dataSpec) ?: PrefetchWindowStore.consumeHead(dataSpec))?.let { cached ->
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

        val probeSource: OkHttpDataSource = upstreamFactory.createDataSource()
        transferListeners.forEach { probeSource.addTransferListener(it) }

        val diagOpenStartMs = SystemClock.uptimeMillis()
        var diagProbeOpenMs = -1L
        var diagBootstrapMs = -1L

        var openLength: Long
        val boundedProbeLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, BOOTSTRAP_READ_BYTES)
        } else {
            BOOTSTRAP_READ_BYTES
        }
        try {
            probeSource.open(dataSpec.buildUpon().setLength(boundedProbeLength).build())
            diagProbeOpenMs = SystemClock.uptimeMillis() - diagOpenStartMs
            resolvedUri = probeSource.uri
            onResolvedUri(resolvedUri)
            val probeTotal = parseContentRangeTotal(probeSource.responseHeaders)
            if (probeTotal != C.LENGTH_UNSET.toLong()) {
                val remaining = (probeTotal - dataSpec.position).coerceAtLeast(0L)
                openLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    minOf(dataSpec.length, remaining)
                } else {
                    remaining
                }
            } else {

                Log.w(TAG, "Bounded probe got no Content-Range; reopening unbounded")
                try { probeSource.close() } catch (_: Exception) {}
                openLength = probeSource.open(dataSpec)
                diagProbeOpenMs = SystemClock.uptimeMillis() - diagOpenStartMs
            }
        } catch (e: Exception) {
            probeSource.close()
            throw e
        }

        val responseHeaders = probeSource.responseHeaders
        val acceptRangesHeader = responseHeaders.entries.firstOrNull { it.key.equals("Accept-Ranges", ignoreCase = true) }?.value
        val contentRangeHeader = responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value
        val acceptsRanges = acceptRangesHeader?.any { it.contains("bytes") } == true ||
                contentRangeHeader?.isNotEmpty() == true

        if (openLength == C.LENGTH_UNSET.toLong() || !acceptsRanges) {

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

        val firstChunkIndex = position / chunkSize
        if (openLength > 0L) {
            val bootstrapBytes = minOf(minOf(chunkSize, BOOTSTRAP_READ_BYTES), openLength).toInt()
            val diagReadStartMs = SystemClock.uptimeMillis()
            val chunk = readBootstrapChunk(probeSource, bootstrapBytes)
            diagBootstrapMs = SystemClock.uptimeMillis() - diagReadStartMs
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
            val diagCloseStartMs = SystemClock.uptimeMillis()
            probeSource.close()
            Log.i(
                TAG,
                "OPEN_SPLIT pos=$position probeOpen=${diagProbeOpenMs}ms " +
                    "bootstrapRead=${diagBootstrapMs}ms bootstrapBytes=${chunk.size} " +
                    "close=${SystemClock.uptimeMillis() - diagCloseStartMs}ms " +
                    "total=${SystemClock.uptimeMillis() - diagOpenStartMs}ms"
            )
        } else {
            val diagCloseStartMs = SystemClock.uptimeMillis()
            probeSource.close()
            Log.i(
                TAG,
                "OPEN_SPLIT pos=$position probeOpen=${diagProbeOpenMs}ms bootstrapRead=n/a " +
                    "close=${SystemClock.uptimeMillis() - diagCloseStartMs}ms " +
                    "total=${SystemClock.uptimeMillis() - diagOpenStartMs}ms"
            )
        }

        return openLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {

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

        if (pendingContinuationOpen && currentChunk == null && continuationSource == null) {
            materialisePendingContinuation()
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

        if (currentChunkIndex != chunkIndex || currentChunk == null) {
            val activeSession = session ?: return C.RESULT_END_OF_INPUT
            ensureChunkScheduled(chunkIndex)
            val future = activeSession.futures[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            activeSession.noteRead(chunkIndex)

            if (!future.isDone) {
                val served = awaitServeFromInFlight(activeSession, chunkIndex, future, buffer, offset, toRead)
                if (served > 0) return served
            }
            try {

                val blockT0 = SystemClock.elapsedRealtime()
                val preDone = future.isDone
                currentChunk = future.get(60, TimeUnit.SECONDS)
                Log.i(
                    TAG,
                    "RS_CHUNK_WAIT site=bytearray pos=$position chunk=$chunkIndex " +
                        "waitMs=${SystemClock.elapsedRealtime() - blockT0} preDone=$preDone"
                )
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT

                if (activeSession.futures.remove(chunkIndex, future)) {
                    activeSession.lastTouch.remove(chunkIndex)
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
        session?.noteRead(chunkIndex)

        return readSize
    }

    private fun materialisePendingContinuation() {
        pendingContinuationOpen = false
        if (bytesRemaining <= 0L) return
        val activeSession = session ?: return
        val boundary = ((position / chunkSize) + 1L) * chunkSize
        val end = minOf(boundary, position + bytesRemaining)
        val length = end - position
        if (length <= 0L) return
        val source = upstreamFactory.createDataSource()
        transferListeners.forEach { source.addTransferListener(it) }
        try {
            source.open(
                DataSpec.Builder()
                    .setUri(activeSession.resolvedUri ?: activeSession.requestUri)
                    .setPosition(position)
                    .setLength(length)
                    .build()
            )
        } catch (e: Exception) {
            try { source.close() } catch (_: Exception) {}
            Log.w(TAG, "Continuation open failed at $position; using chunk path: ${e.message}")
            return
        }
        continuationSource = source
        continuationEndPositionExclusive = end

        activeSession.noteRead(position / chunkSize)
        Log.d(TAG, "Continuation open at $position, $length bytes to boundary $end")
    }

    private fun releaseInFlightBuffer(
        activeSession: ChunkSession,
        chunkIndex: Long,
        inFlight: InFlightChunk,
        buffer: PooledBuffer
    ) {
        synchronized(inFlight.lock) {
            inFlight.buffer = null
            activeSession.inFlight.remove(chunkIndex, inFlight)
            releaseBuffer(buffer)
        }
    }

    private fun escalateReaderBlockedChunk(
        activeSession: ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        waitedMs: Long,
        watermark: Int
    ) {
        if (future.isDone || future.isCancelled || activeSession.abandoned.get()) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=done")
            return
        }
        if (hudClampLatched) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=clamp")
            return
        }
        val typed = activeSession.futures[chunkIndex]
        if (typed == null || typed !== future) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=stale")
            return
        }
        if (!activeSession.escalatedChunks.add(chunkIndex)) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=already")
            return
        }
        Log.w(TAG, "RS_ESCALATE fired chunk=$chunkIndex waitMs=$waitedMs watermark=$watermark")
        val t0 = SystemClock.elapsedRealtime()
        sharedExecutor.execute {
            try {
                if (typed.isDone || typed.isCancelled || activeSession.abandoned.get()) return@execute
                val result = downloadChunkOnce(activeSession, chunkIndex, typed, allowStallRestart = false)
                if (typed.complete(result)) {
                    Log.w(TAG, "RS_ESCALATE won chunk=$chunkIndex ms=${SystemClock.elapsedRealtime() - t0}")
                } else {
                    releaseBuffer(result.buffer)
                    Log.i(TAG, "RS_ESCALATE lost chunk=$chunkIndex")
                }
            } catch (e: Exception) {

                Log.w(TAG, "RS_ESCALATE failed chunk=$chunkIndex: ${e.message}")
            } catch (e: OutOfMemoryError) {
                drainIdleBuffers(activeSession.chunkSize)
                Log.w(TAG, "RS_ESCALATE failed chunk=$chunkIndex: chunk buffer OOM (contained)")
            }
        }
    }

    private fun awaitServeFromInFlight(
        activeSession: ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        target: ByteArray,
        targetOffset: Int,
        maxLength: Int
    ): Int {
        val offsetInChunk = (position % chunkSize).toInt()
        val waitT0 = SystemClock.elapsedRealtime()

        var escalatedThisWait = false
        var baselineWatermark = Int.MIN_VALUE
        while (true) {

            if (closed.get() || future.isDone) return 0

            val inFlight = activeSession.inFlight[chunkIndex]
            if (inFlight != null) {
                val available = inFlight.watermark - offsetInChunk
                if (available > 0) {
                    val toCopy = minOf(maxLength, available)
                    synchronized(inFlight.lock) {
                        val buf = inFlight.buffer ?: return 0
                        val view = buf.byteBuffer.duplicate()
                        view.position(offsetInChunk)
                        view.get(target, targetOffset, toCopy)
                    }
                    val waitedMs = SystemClock.elapsedRealtime() - waitT0
                    if (!inFlightServeLogged) {
                        inFlightServeLogged = true
                        Log.i(
                            TAG,
                            "RS_INFLIGHT pos=$position chunk=$chunkIndex " +
                                "watermark=${inFlight.watermark} served=$toCopy waitMs=$waitedMs"
                        )
                    }
                    position += toCopy
                    bytesRemaining -= toCopy
                    bytesServedThisOpen += toCopy
                    return toCopy
                }
            }

            val wmNow = inFlight?.watermark ?: -1
            if (baselineWatermark == Int.MIN_VALUE) baselineWatermark = wmNow
            if (!escalatedThisWait &&
                wmNow == baselineWatermark &&
                SystemClock.elapsedRealtime() - waitT0 >=
                    minOf(ESCALATE_AFTER_MS, IN_FLIGHT_WAIT_CAP_MS.toLong())
            ) {
                escalateReaderBlockedChunk(
                    activeSession, chunkIndex, future,
                    SystemClock.elapsedRealtime() - waitT0, wmNow
                )
                escalatedThisWait = true
            }
            if (SystemClock.elapsedRealtime() - waitT0 >=
                IN_FLIGHT_WAIT_CAP_MS.toLong() +
                    (if (escalatedThisWait) ESCALATE_POLL_EXTENSION_MS else 0L)
            ) {
                Log.i(
                    TAG,
                    "RS_INFLIGHT_GIVEUP pos=$position chunk=$chunkIndex " +
                        "waitMs=${SystemClock.elapsedRealtime() - waitT0}"
                )
                return 0
            }
            try {
                Thread.sleep(IN_FLIGHT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return 0
            }
        }
    }

    private fun scheduleChunks() {
        if (!shouldAllowBackgroundPrefetch()) return

        if (bytesRemaining == 0L) return
        val currentChunkIdx =
            if (continuationSource != null && continuationEndPositionExclusive != C.TIME_UNSET && position < continuationEndPositionExclusive) {
                (continuationEndPositionExclusive + chunkSize - 1L) / chunkSize
            } else {
                position / chunkSize
            }

        val earnedAhead = if (bytesServedThisOpen >= EARNED_PREFETCH_BYTES) effectivePrefetchDepth else 1

        val maxAhead = session?.currentAllowedDepth(effectivePrefetchDepth)?.coerceAtMost(earnedAhead)
            ?: earnedAhead

        for (i in 0 until maxAhead) {
            val ci = currentChunkIdx + i
            if (totalFileLength != C.LENGTH_UNSET.toLong() && ci * chunkSize >= totalFileLength) break
            ensureChunkScheduled(ci)
        }
    }

    private fun ensureChunkScheduled(chunkIndex: Long) {
        val activeSession = session ?: return

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
                        }
                    } else if (future.isCancelled) {

                    } else {
                        future.completeExceptionally(IOException("Session abandoned"))
                    }
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                } catch (e: OutOfMemoryError) {

                    drainIdleBuffers(activeSession.chunkSize)
                    future.completeExceptionally(
                        IOException("Native chunk buffer allocation failed (out of memory)", e)
                    )
                }
            }
            future
        }
    }

    private fun downloadChunk(activeSession: ChunkSession, chunkIndex: Long, future: CompletableFuture<*>): DownloadedChunk {
        var lastException: Exception? = null
        for (attempt in 0..1) {

            if (future.isDone || future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future)
            } catch (e: Exception) {

                if (activeSession.abandoned.get() || future.isCancelled) throw IOException("Session abandoned or cancelled")
                lastException = e

                if (e is StalledChunkException) {
                    return downloadChunkWithStallRestart(activeSession, chunkIndex, future, e)
                }
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

    private fun downloadChunkOnce(activeSession: ChunkSession, chunkIndex: Long, future: CompletableFuture<*>, allowStallRestart: Boolean = true): DownloadedChunk {
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
            val chunk = readIntoChunk(activeSession, chunkIndex, ds, future, expectedBytes, allowStallRestart)
            Log.d(TAG, "Successfully downloaded chunk $chunkIndex, size=${chunk.size} bytes")
            return chunk
        } finally {
            activeSession.activeSources.remove(ds)
            try { ds.close() } catch (_: Exception) {}
        }
    }

    private fun downloadChunkWithStallRestart(
        activeSession: ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        firstStall: StalledChunkException
    ): DownloadedChunk {
        var lastStall = firstStall
        var attempt = 0
        while (attempt < HEDGE_MAX_RESTARTS) {
            if (future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            hudHedgeRestarts++
            Log.w(TAG, "HEDGE_RESTART chunk=$chunkIndex attempt=${attempt + 1}/$HEDGE_MAX_RESTARTS " +
                "prevRateBps=${lastStall.rateBps} atWatermark=${lastStall.watermark}")
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future)
            } catch (e: Exception) {
                if (activeSession.abandoned.get() || future.isCancelled) throw IOException("Session abandoned or cancelled")
                if (e !is StalledChunkException) throw e
                lastStall = e
                attempt++
            }
        }

        hudHedgeExhausted = true
        Log.w(TAG, "HEDGE_RESTART chunk=$chunkIndex exhausted after $HEDGE_MAX_RESTARTS; " +
            "final attempt with watchdog disabled")
        return downloadChunkOnce(activeSession, chunkIndex, future, allowStallRestart = false)
    }

    private class StalledChunkException(
        val chunkIndex: Long,
        val watermark: Int,
        val rateBps: Long
    ) : IOException("chunk $chunkIndex body stalled at ${rateBps}B/s (watermark=$watermark)")

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
        val escalation = activeSession.beginRateLimitEpisode(effectivePrefetchDepth)
        var attempt = 0
        while (attempt < RATE_LIMIT_MAX_BACKOFF_RETRIES) {
            val waitMs = rateLimitWaitMs(attempt, escalation, rl)
            Log.w(TAG, "Chunk $chunkIndex rate-limited (HTTP ${rl.responseCode}); backing off ${waitMs}ms " +
                "(attempt ${attempt + 1}/$RATE_LIMIT_MAX_BACKOFF_RETRIES, escalation $escalation)")
            if (!sleepInterruptibly(waitMs, future, activeSession)) throw IOException("Cancelled during rate-limit backoff")
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
        throw IOException("Chunk $chunkIndex still rate-limited after $RATE_LIMIT_MAX_BACKOFF_RETRIES backoffs", lastException)
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

    private fun readIntoChunk(
        activeSession: ChunkSession,
        chunkIndex: Long,
        ds: DataSource,
        future: CompletableFuture<*>,
        expectedBytes: Long,
        allowStallRestart: Boolean = true
    ): DownloadedChunk {
        val buffer = acquireBuffer()

        val inFlight = InFlightChunk(buffer)
        activeSession.inFlight[chunkIndex] = inFlight
        val tempArray = readBufferLocal.get()!!
        var totalRead = 0
        var consecutiveZeroReads = 0

        if (obsAnnounced.compareAndSet(false, true)) {
            Log.w(TAG, "OBS_ACTIVE build=hedge-3a minOpenMs=$HEDGE_MIN_OPEN_MS " +
                "windowMs=$HEDGE_WINDOW_MS stallRateBps=$HEDGE_STALL_RATE_BPS " +
                "maxRestarts=$HEDGE_MAX_RESTARTS")
        }

        val hedgeChunkT0 = SystemClock.elapsedRealtime()
        var hedgeLastCheckT0 = hedgeChunkT0
        var hedgeLastCheckBytes = 0

        val hedgeIsUsenet = activeSession.resolvedUri?.path?.contains("/usenet/") == true
        val hedgeStallRateBps = if (hedgeIsUsenet) HEDGE_STALL_RATE_USENET else HEDGE_STALL_RATE_CDN
        val hedgeWindowsRequired = if (hedgeIsUsenet) HEDGE_WINDOWS_USENET else HEDGE_WINDOWS_CDN
        var hedgeConsecutiveStalled = 0
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

                inFlight.watermark = totalRead

                val hedgeNowMs = SystemClock.elapsedRealtime()
                if (hedgeNowMs - hedgeChunkT0 >= HEDGE_MIN_OPEN_MS &&
                    hedgeNowMs - hedgeLastCheckT0 >= HEDGE_WINDOW_MS) {
                    val hedgeWindowMs = hedgeNowMs - hedgeLastCheckT0
                    val hedgeWindowBytes = totalRead - hedgeLastCheckBytes
                    val hedgeRateBps = if (hedgeWindowMs > 0L)
                        hedgeWindowBytes.toLong() * 1000L / hedgeWindowMs else Long.MAX_VALUE
                    val hedgeStalled = hedgeRateBps < hedgeStallRateBps
                    if (hedgeStalled) hedgeConsecutiveStalled++ else hedgeConsecutiveStalled = 0
                    Log.w(TAG, "HEDGE_SAMPLE chunk=$chunkIndex watermark=$totalRead " +
                        "windowBytes=$hedgeWindowBytes windowMs=$hedgeWindowMs " +
                        "rateBps=$hedgeRateBps stalled=$hedgeStalled " +
                        "consec=$hedgeConsecutiveStalled/$hedgeWindowsRequired " +
                        "usenet=$hedgeIsUsenet clamp=$hudClampLatched " +
                        "restartable=$allowStallRestart")
                    if (hedgeConsecutiveStalled >= hedgeWindowsRequired &&
                        allowStallRestart && !hudClampLatched) {

                        throw StalledChunkException(chunkIndex, totalRead, hedgeRateBps)
                    }
                    hedgeLastCheckT0 = hedgeNowMs
                    hedgeLastCheckBytes = totalRead
                }
            }

            if (expectedBytes > 0L && totalRead < expectedBytes && !activeSession.abandoned.get()) {
                throw IOException("Short chunk: read $totalRead of $expectedBytes bytes")
            }
        } catch (e: Exception) {
            releaseInFlightBuffer(activeSession, chunkIndex, inFlight, buffer)
            if (activeSession.abandoned.get()) throw IOException("Session abandoned")
            throw e
        }
        if (activeSession.abandoned.get()) {
            releaseInFlightBuffer(activeSession, chunkIndex, inFlight, buffer)
            throw IOException("Session abandoned")
        }

        activeSession.inFlight.remove(chunkIndex, inFlight)
        buffer.byteBuffer.flip()
        return DownloadedChunk(buffer, totalRead)
    }

    private fun parseContentRangeTotal(headers: Map<String, List<String>>): Long {
        val value = headers.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?: return C.LENGTH_UNSET.toLong()
        val totalPart = value.substringAfterLast('/', missingDelimiterValue = "").trim()
        if (totalPart.isEmpty() || totalPart == "*") return C.LENGTH_UNSET.toLong()
        return totalPart.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
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
        inFlightServeLogged = false
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            fallbackSource?.close()
            fallbackSource = null
            continuationSource?.close()
            continuationSource = null
            continuationEndPositionExclusive = C.TIME_UNSET
            pendingContinuationOpen = false

            resetLocalReadState()
            session = null

            val active = activeInstances.decrementAndGet()
            if (active <= 0) {
                clearGlobalPool()
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

        if (pendingContinuationOpen && currentChunk == null && continuationSource == null) {
            materialisePendingContinuation()
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
            activeSession.noteRead(chunkIndex)
            try {

                val blockT0 = SystemClock.elapsedRealtime()
                val preDone = future.isDone
                currentChunk = future.get(60, TimeUnit.SECONDS)
                Log.i(
                    TAG,
                    "RS_CHUNK_WAIT site=bytebuffer pos=$position chunk=$chunkIndex " +
                        "waitMs=${SystemClock.elapsedRealtime() - blockT0} preDone=$preDone"
                )
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT

                if (activeSession.futures.remove(chunkIndex, future)) {
                    activeSession.lastTouch.remove(chunkIndex)
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
        session?.noteRead(chunkIndex)

        return readSize
    }

    internal fun prestartChunk0(uri: Uri) {
        val pending = obtainPendingSession(
            uri, emptyMap(), chunkSize, sessionChunkCap, maxPoolSize, effectivePrefetchDepth
        ) ?: return
        session = pending
        try {
            ensureChunkScheduled(0L)
            Log.i(
                TAG,
                "PRESTART: scheduled chunk 0 ahead of player build " +
                    "chunkSize=${chunkSize / 1024L}KB host=${uri.host} " +
                    "pathLen=${uri.path?.length ?: -1} queryLen=${uri.query?.length ?: -1} " +
                    "uriLen=${uri.toString().length}"
            )
        } finally {
            session = null
        }
    }

    class Factory(
        private val upstreamFactory: OkHttpDataSource.Factory,
        private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
        private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
        private val useNativeMemory: Boolean = false,
        private val prefetchDepthChunks: Int = parallelConnections + 1,
        private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
        private val onResolvedUri: (Uri?) -> Unit = {},
        private val allowContinuationReopen: Boolean = true
    ) : DataSource.Factory {
        @Volatile
        private var startupBootstrapCache: BootstrapCacheEntry? = null

        fun prestartChunk0(uri: Uri) {
            (createDataSource() as ParallelRangeDataSource).prestartChunk0(uri)
        }

        override fun createDataSource(): DataSource {
            return ParallelRangeDataSource(
                upstreamFactory = upstreamFactory,
                parallelConnections = parallelConnections,
                chunkSize = chunkSize,
                useNativeMemory = useNativeMemory,
                prefetchDepthChunks = prefetchDepthChunks,
                shouldAllowBackgroundPrefetch = shouldAllowBackgroundPrefetch,
                onResolvedUri = onResolvedUri,
                allowContinuationReopen = allowContinuationReopen,
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

internal object PrefetchWindowStore {
    private const val TAG = "ParallelRangeDS"
    private const val TTL_MS = 300_000L
    const val TAIL_WINDOW_BYTES = 4_194_304L

    private const val STORE_CAP = 8

    private val headEntries = object : LinkedHashMap<Uri, ParallelRangeDataSource.BootstrapCacheEntry>(STORE_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, ParallelRangeDataSource.BootstrapCacheEntry>?): Boolean {
            return size > STORE_CAP
        }
    }

    private val tailEntries = object : LinkedHashMap<Uri, ParallelRangeDataSource.BootstrapCacheEntry>(STORE_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, ParallelRangeDataSource.BootstrapCacheEntry>?): Boolean {
            return size > STORE_CAP
        }
    }

    fun putHead(entry: ParallelRangeDataSource.BootstrapCacheEntry) {
        synchronized(headEntries) {
            headEntries[entry.requestUri] = entry
        }
        Log.i(
            TAG,
            "PREFETCH_WINDOW put head bytes=${entry.bootstrapSize} " +
                "total=${entry.totalFileLength} host=${entry.resolvedUri?.host}"
        )
    }

    fun putTail(entry: ParallelRangeDataSource.BootstrapCacheEntry) {
        synchronized(tailEntries) {
            tailEntries[entry.requestUri] = entry
        }
        Log.i(TAG, "PREFETCH_WINDOW put tail start=${entry.startPosition} bytes=${entry.bootstrapSize}")
    }

    fun consumeHead(dataSpec: DataSpec): ParallelRangeDataSource.BootstrapCacheEntry? {
        if (dataSpec.position != 0L) return null
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) return null
        val cached = synchronized(headEntries) {
            val entry = headEntries[dataSpec.uri] ?: return null
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                headEntries.remove(dataSpec.uri)
                return null
            }
            if (entry.startPosition != 0L) return null

            headEntries.remove(dataSpec.uri)
            entry
        }
        Log.i(TAG, "PREFETCH_WINDOW head hit bytes=${cached.bootstrapSize} total=${cached.totalFileLength}")
        return cached
    }

    fun peekTail(uri: Uri, position: Long): ParallelRangeDataSource.BootstrapCacheEntry? {
        val cached = synchronized(tailEntries) {
            val entry = tailEntries[uri] ?: return null
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                tailEntries.remove(uri)
                return null
            }
            if (position < entry.startPosition || position >= entry.startPosition + entry.bootstrapSize) return null

            entry
        }
        Log.i(TAG, "PREFETCH_WINDOW tail hit pos=$position start=${cached.startPosition}")
        return cached
    }

    fun hasFreshTail(uri: Uri): Boolean {
        return synchronized(tailEntries) {
            val entry = tailEntries[uri] ?: return false
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                tailEntries.remove(uri)
                return false
            }
            true
        }
    }

    fun peekHead(uri: Uri): ByteArray? {
        return synchronized(headEntries) {
            val entry = headEntries[uri] ?: return null
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                headEntries.remove(uri)
                return null
            }
            entry.bootstrapData
        }
    }
}
