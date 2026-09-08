package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// CacheDataSource writes through the sink on the read path, so a slow write delays the next read;
// that cost measured at 30 percent of playback on a remux and collapsed the forward buffer.
// Nothing here may throw once the span is open, because CacheDataSource treats any sink exception
// as a cache failure and stops using the cache for the rest of the source's life.
@UnstableApi
internal class VodCacheWriteSink(
    private val delegate: DataSink,
    private val counters: Counters
) : DataSink {

    // Counters live outside so the factory can report them without holding a sink reference.
    class Counters {
        val bytesWritten = AtomicLong(0L)
        val writeTimeMs = AtomicLong(0L)
        val enqueueTimeMs = AtomicLong(0L)
        val errors = AtomicLong(0L)
        val blockedMs = AtomicLong(0L)
        // Nanoseconds because a single write runs well under a millisecond, which a millisecond
        // clock records as nothing at all. Enqueue covers both, and they point at different fixes:
        // buffer time is contention on the pool, copy time is the device being out of cpu.
        val bufferTimeNs = AtomicLong(0L)
        val copyTimeNs = AtomicLong(0L)
        val allocations = AtomicLong(0L)
        // Close runs on the read thread and cannot return until the worker has drained this span,
        // so a slow disk shows up here rather than in the enqueue or blocked counters.
        val closeWaitMs = AtomicLong(0L)
        val spans = AtomicLong(0L)

        fun reset() {
            bytesWritten.set(0L)
            writeTimeMs.set(0L)
            enqueueTimeMs.set(0L)
            errors.set(0L)
            blockedMs.set(0L)
            bufferTimeNs.set(0L)
            copyTimeNs.set(0L)
            allocations.set(0L)
            closeWaitMs.set(0L)
            spans.set(0L)
        }
    }

    @Volatile
    private var failed = false

    private val queuedBytes = AtomicLong(0L)

    // Only the loading thread touches these, and only between open and close.
    private var pending: ByteArray? = null
    private var pendingLength = 0

    override fun open(dataSpec: DataSpec) {
        failed = false
        queuedBytes.set(0L)
        pending = null
        pendingLength = 0
        delegate.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (failed) return
        val startMs = SystemClock.elapsedRealtime()
        var blocked = false
        // Dropping writes instead of waiting would leave CacheDataSink committing a range whose
        // bytes are not the ones it claims.
        val waitUntilMs = startMs + MAX_BACKPRESSURE_WAIT_MS
        while (queuedBytes.get() + length > MAX_QUEUED_BYTES &&
            SystemClock.elapsedRealtime() < waitUntilMs
        ) {
            blocked = true
            try {
                Thread.sleep(BACKPRESSURE_SLEEP_MS)
            } catch (e: InterruptedException) {
                // A seek cancels the load by interrupting this thread, so stop waiting and let the
                // caller see the interrupt.
                Thread.currentThread().interrupt()
                break
            }
        }
        if (queuedBytes.get() + length > MAX_QUEUED_BYTES) {
            // The disk has been behind for the full wait, so stop caching this span. The worker
            // runs writes in order, which makes the committed span a contiguous prefix, and the
            // delegate commits only what it was handed.
            failed = true
            counters.blockedMs.addAndGet(SystemClock.elapsedRealtime() - startMs)
            return
        }
        // The reads arrive around a kilobyte each, so handing every one to the worker meant a
        // buffer and a task per kilobyte; filling one buffer first cuts both by the buffer size.
        var consumed = 0
        while (consumed < length) {
            val beforeBufferNs = System.nanoTime()
            var target = pending
            if (target == null) {
                target = takeBuffer(counters)
                pending = target
                pendingLength = 0
            }
            val beforeCopyNs = System.nanoTime()
            counters.bufferTimeNs.addAndGet(beforeCopyNs - beforeBufferNs)
            val chunk = minOf(BUFFER_BYTES - pendingLength, length - consumed)
            System.arraycopy(buffer, offset + consumed, target, pendingLength, chunk)
            counters.copyTimeNs.addAndGet(System.nanoTime() - beforeCopyNs)
            pendingLength += chunk
            consumed += chunk
            if (pendingLength == BUFFER_BYTES) flushPending()
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        counters.enqueueTimeMs.addAndGet(elapsedMs)
        if (blocked) counters.blockedMs.addAndGet(elapsedMs)
    }

    private fun flushPending() {
        val buffer = pending ?: return
        val length = pendingLength
        pending = null
        pendingLength = 0
        if (length == 0) {
            recycleBuffer(buffer)
            return
        }
        queuedBytes.addAndGet(length.toLong())
        writer.execute { drainOne(buffer, length) }
    }

    override fun close() {
        // The tail of the span is still held back, and it has to reach the worker before the latch
        // below or the delegate would commit a span missing its last bytes.
        if (failed) {
            pending?.let(::recycleBuffer)
            pending = null
            pendingLength = 0
        } else {
            flushPending()
        }
        // The writer is ordered, so a task queued here runs after this span's writes and before
        // the delegate commits them.
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        val waitStartMs = SystemClock.elapsedRealtime()
        var interrupted = Thread.interrupted()
        while (true) {
            try {
                done.await()
                break
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        counters.closeWaitMs.addAndGet(SystemClock.elapsedRealtime() - waitStartMs)
        counters.spans.incrementAndGet()
        try {
            delegate.close()
        } catch (e: IOException) {
            counters.errors.incrementAndGet()
            Log.w(TAG, "VOD_CACHE: sink close failed", e)
            throw e
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun drainOne(data: ByteArray, length: Int) {
        if (!failed) {
            val startMs = SystemClock.elapsedRealtime()
            try {
                delegate.write(data, 0, length)
                counters.writeTimeMs.addAndGet(SystemClock.elapsedRealtime() - startMs)
                counters.bytesWritten.addAndGet(length.toLong())
            } catch (e: IOException) {
                counters.errors.incrementAndGet()
                Log.w(TAG, "VOD_CACHE: sink write failed", e)
                failed = true
            }
        }
        queuedBytes.addAndGet(-length.toLong())
        recycleBuffer(data)
    }

    private companion object {
        const val TAG = "PlayerMediaSource"
        const val MAX_QUEUED_BYTES = 24L * 1024L * 1024L
        const val BACKPRESSURE_SLEEP_MS = 2L
        const val MAX_BACKPRESSURE_WAIT_MS = 2_000L
        const val POOL_CAPACITY = 64

        // One worker for every sink: a thread per span cost thousands of threads over a playback.
        private val writer = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                // Java thread priority barely moves an Android thread; the background group is what
                // keeps disk writes from competing with the codec and the UI.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "VodCacheWrite")
        }

        // One size for every buffer is what lets the pool be taken from one end instead of searched
        // for one large enough; this matches the allocator's chunk, which is what a read hands over.
        // The allocs counter says how often a write arrives larger than this.
        const val BUFFER_BYTES = 64 * 1024

        // Shared because a sink is closed and replaced for every cached region.
        private val bufferPool = ArrayDeque<ByteArray>()

        fun takeBuffer(counters: Counters): ByteArray {
            val pooled = synchronized(bufferPool) { bufferPool.removeLastOrNull() }
            if (pooled != null) return pooled
            counters.allocations.incrementAndGet()
            return ByteArray(BUFFER_BYTES)
        }

        fun recycleBuffer(buffer: ByteArray) {
            if (buffer.size != BUFFER_BYTES) return
            synchronized(bufferPool) {
                if (bufferPool.size < POOL_CAPACITY) bufferPool.addLast(buffer)
            }
        }
    }
}
