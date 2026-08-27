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

        fun reset() {
            bytesWritten.set(0L)
            writeTimeMs.set(0L)
            enqueueTimeMs.set(0L)
            errors.set(0L)
            blockedMs.set(0L)
        }
    }

    @Volatile
    private var failed = false

    private val queuedBytes = AtomicLong(0L)

    override fun open(dataSpec: DataSpec) {
        failed = false
        queuedBytes.set(0L)
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
        val copy = takeBuffer(length)
        System.arraycopy(buffer, offset, copy, 0, length)
        queuedBytes.addAndGet(length.toLong())
        writer.execute { drainOne(copy, length) }
        val elapsedMs = SystemClock.elapsedRealtime() - startMs
        counters.enqueueTimeMs.addAndGet(elapsedMs)
        if (blocked) counters.blockedMs.addAndGet(elapsedMs)
    }

    override fun close() {
        // The writer is ordered, so a task queued here runs after this span's writes and before
        // the delegate commits them.
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        var interrupted = Thread.interrupted()
        while (true) {
            try {
                done.await()
                break
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
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

        // Shared because a sink is closed and replaced for every cached region.
        private val bufferPool = ArrayDeque<ByteArray>()

        fun takeBuffer(length: Int): ByteArray {
            synchronized(bufferPool) {
                val iterator = bufferPool.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (candidate.size >= length) {
                        iterator.remove()
                        return candidate
                    }
                }
            }
            return ByteArray(length)
        }

        fun recycleBuffer(buffer: ByteArray) {
            synchronized(bufferPool) {
                if (bufferPool.size < POOL_CAPACITY) bufferPool.addLast(buffer)
            }
        }
    }
}
