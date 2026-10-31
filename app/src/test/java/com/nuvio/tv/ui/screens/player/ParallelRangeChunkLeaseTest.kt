package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.Allocation
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

class ParallelRangeChunkLeaseTest {

    private class FakeChunkMemory {
        val live: MutableSet<Allocation> = ConcurrentHashMap.newKeySet()
        val allocated = AtomicInteger()
        val freed = AtomicInteger()
        val badFrees = AtomicInteger()

        fun allocate(size: Int): Allocation =
            Allocation(ByteBuffer.allocate(size), 0).also { allocated.incrementAndGet(); live.add(it) }

        fun free(allocation: Allocation) {
            if (!live.remove(allocation)) {
                badFrees.incrementAndGet()
                return
            }
            val bytes = allocation.buffer!!
            for (i in 0 until bytes.capacity()) bytes.put(i, POISON)
            freed.incrementAndGet()
        }
    }

    private val memory = FakeChunkMemory()
    private val server = MockWebServer()
    private val file = Random(7).nextBytes(2 * 1024 * 1024)
    private val clockOffsetMs = AtomicLong()
    private val opened = ConcurrentHashMap.newKeySet<ParallelRangeDataSource>()
    private lateinit var allocateBefore: (Int) -> Allocation?
    private lateinit var freeBefore: (Allocation) -> Unit

    @Before
    fun setUp() {
        mockkStatic(Uri::class, SystemClock::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000L }
        every { SystemClock.uptimeMillis() } answers { System.nanoTime() / 1_000_000L + clockOffsetMs.get() }
        allocateBefore = ParallelRangeDataSource.allocateChunk
        freeBefore = ParallelRangeDataSource.freeChunk
        ParallelRangeDataSource.allocateChunk = memory::allocate
        ParallelRangeDataSource.freeChunk = memory::free
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        teardownSessions()
        drainPool()
        ParallelRangeDataSource.allocateChunk = allocateBefore
        ParallelRangeDataSource.freeChunk = freeBefore
        server.shutdown()
        unmockkStatic(Uri::class, SystemClock::class)
    }

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { host } returns "127.0.0.1"
        every { path } returns "/file"
        every { getQueryParameter(any()) } returns null
    }

    private fun serve() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                    ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(file))
                val parts = range.removePrefix("bytes=").split("-")
                val from = parts[0].toInt()
                val to = minOf(parts.getOrNull(1)?.toIntOrNull() ?: (file.size - 1), file.size - 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $from-$to/${file.size}")
                    .setBody(Buffer().write(file, from, to - from + 1))
            }
        }
        server.start()
    }

    private fun source(continuation: Boolean = true) = ParallelRangeDataSource(
        OkHttpDataSource.Factory(OkHttpClient()),
        parallelConnections = 2,
        chunkSize = CHUNK,
        useNativeMemory = true,
        allowContinuationReopen = continuation,
    ).also { opened.add(it) }

    private fun teardownSessions() = ParallelRangeDataSource.releaseRetainedSession()

    private fun drainPool() = ParallelRangeDataSource.drainIdleBuffers(CHUNK)

    private val fileUri by lazy { uriOf(server.url("/file").toString()) }

    private fun spec(position: Long) = DataSpec.Builder().setUri(fileUri).setPosition(position).build()

    private fun pooledBuffer(allocation: Allocation): Any =
        Class.forName("com.nuvio.tv.ui.screens.player.ParallelRangeDataSource\$PooledBuffer")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(allocation, allocation.buffer)

    private fun call(target: Any, name: String, vararg args: Any?): Any? = target.javaClass.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }
        .apply { isAccessible = true }.invoke(target, *args)

    private fun releaseSessionBuffer(buffer: Any, poolCap: Int) {
        call(ParallelRangeDataSource.Companion, "releaseSessionBuffer", buffer, CHUNK, poolCap)
    }

    private fun pooled(): Int =
        (ParallelRangeDataSource::class.java.getDeclaredField("globalBufferPool").apply { isAccessible = true }
            .get(null) as Map<*, *>).values.sumOf { (it as Collection<*>).size }

    // Reads like the player: compares every byte with the file and reopens at the same position
    // after a failed read.
    private inner class Reader(val source: ParallelRangeDataSource, val viaByteBuffer: Boolean = false) {
        var position = 0L
        var mismatches = 0
        var reopens = 0
        private var isOpen = false
        private val scratch = ByteArray(2_000)
        private val target = ByteBuffer.allocateDirect(2_000)

        fun readTo(until: Long, pace: Boolean = false, deadlineMs: Long = System.currentTimeMillis() + 60_000L) {
            while (position < until && System.currentTimeMillis() < deadlineMs) {
                if (!isOpen) {
                    try {
                        source.open(spec(position))
                        isOpen = true
                    } catch (_: IOException) {
                        source.close()
                        reopens++
                        continue
                    }
                }
                val wanted = minOf(scratch.size.toLong(), until - position).toInt()
                val read = try {
                    if (viaByteBuffer) {
                        target.clear()
                        source.read(target, wanted).also { if (it > 0) { target.flip(); target.get(scratch, 0, it) } }
                    } else {
                        source.read(scratch, 0, wanted)
                    }
                } catch (_: IOException) {
                    C.RESULT_END_OF_INPUT
                }
                if (read == C.RESULT_END_OF_INPUT || read == 0) {
                    source.close()
                    isOpen = false
                    reopens++
                    continue
                }
                for (i in 0 until read) {
                    if (scratch[i] != file[(position + i).toInt()]) {
                        mismatches++
                        break
                    }
                }
                position += read
                if (pace) LockSupport.parkNanos(20_000L)
            }
        }

        fun close() {
            source.close()
            isOpen = false
        }

        fun stats() = "mismatches=$mismatches reopens=$reopens allocated=${memory.allocated.get()} freed=${memory.freed.get()}"
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    // Leaves the reader inside chunk 1 holding the finished download, not the in-flight view.
    private fun holdChunkOne(reader: Reader) {
        reader.readTo(CHUNK + 1_000)
        val futures = field(field(reader.source, "session")!!, "futures") as Map<*, *>
        (futures[1L] as CompletableFuture<*>).get(10, TimeUnit.SECONDS)
        reader.readTo(CHUNK + 5_000)
        assertEquals(1L, field(reader.source, "currentChunkIndex"))
        assertTrue(field(reader.source, "currentChunk") != null)
        assertEquals(0, reader.mismatches)
    }

    private fun assertAllMemoryReturned() {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < end) {
            teardownSessions()
            drainPool()
            if (memory.freed.get() == memory.allocated.get()) break
            Thread.sleep(10)
        }
        assertEquals("chunk memory freed twice or never allocated here", 0, memory.badFrees.get())
        assertEquals("every chunk freed exactly once", memory.allocated.get(), memory.freed.get())
    }

    @Test
    fun `releasing a buffer nobody reads frees it once and refuses later leases`() {
        val buffer = pooledBuffer(memory.allocate(CHUNK.toInt()))
        releaseSessionBuffer(buffer, 0)
        releaseSessionBuffer(buffer, 0)
        releaseSessionBuffer(buffer, 4)
        assertEquals(1, memory.freed.get())
        assertEquals(0, memory.badFrees.get())
        assertFalse(call(buffer, "lease") as Boolean)
        assertEquals(0, pooled())
    }

    @Test
    fun `release requested during a read waits for the reader`() {
        val buffer = pooledBuffer(memory.allocate(CHUNK.toInt()))
        assertTrue(call(buffer, "lease") as Boolean)
        assertTrue(call(buffer, "lease") as Boolean)
        releaseSessionBuffer(buffer, 0)
        assertEquals(0, memory.freed.get())
        assertFalse("no new reader once release is requested", call(buffer, "lease") as Boolean)
        call(buffer, "endLease")
        assertEquals(0, memory.freed.get())
        call(buffer, "endLease")
        assertEquals(1, memory.freed.get())
        releaseSessionBuffer(buffer, 0)
        assertEquals(1, memory.freed.get())
        assertEquals(0, memory.badFrees.get())
    }

    @Test
    fun `pooled memory is not reachable through the chunk that gave it back`() {
        val buffer = pooledBuffer(memory.allocate(CHUNK.toInt()))
        releaseSessionBuffer(buffer, 4)
        assertEquals(1, pooled())
        assertFalse(call(buffer, "lease") as Boolean)
        drainPool()
        assertEquals(1, memory.freed.get())
    }

    @Test
    fun `concurrent readers and one release free the memory exactly once and never under a reader`() {
        repeat(300) { round ->
            val allocation = memory.allocate(4_096)
            val bytes = allocation.buffer!!
            for (i in 0 until bytes.capacity()) bytes.put(i, 1)
            val buffer = pooledBuffer(allocation)
            val sawPoison = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            val start = CountDownLatch(1)
            val readers = (0 until 4).map {
                Thread {
                    try {
                        start.await()
                        while (call(buffer, "lease") as Boolean) {
                            if (bytes.get(Random.nextInt(bytes.capacity())) == POISON) sawPoison.incrementAndGet()
                            call(buffer, "endLease")
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }.apply { start() }
            }
            start.countDown()
            Thread.sleep(0, Random.nextInt(200_000))
            releaseSessionBuffer(buffer, if (round % 2 == 0) 0 else 4)
            readers.forEach { it.join(5_000) }
            failure.get()?.let { throw it }
            assertEquals("reader saw freed memory in round $round", 0, sawPoison.get())
        }
        drainPool()
        assertEquals(300, memory.freed.get())
        assertEquals(0, memory.badFrees.get())
    }

    private fun sessionTornDownUnderReader(viaByteBuffer: Boolean) {
        serve()
        val reader = Reader(source(), viaByteBuffer)
        holdChunkOne(reader)
        Thread { teardownSessions() }.apply { start() }.join(5_000)
        assertTrue("chunk memory was freed", memory.freed.get() > 0)
        reader.readTo(3 * CHUNK)
        reader.close()
        assertEquals("bytes read from freed chunk memory, ${reader.stats()}", 0, reader.mismatches)
        assertEquals(3 * CHUNK, reader.position)
        assertAllMemoryReturned()
    }

    @Test
    fun `reader does not read a chunk freed by a session teardown on another thread`() {
        sessionTornDownUnderReader(viaByteBuffer = false)
    }

    @Test
    fun `byte buffer reader does not read a chunk freed by a session teardown on another thread`() {
        sessionTornDownUnderReader(viaByteBuffer = true)
    }

    @Test
    fun `chunk evicted by a second reader is not reused under the first reader`() {
        serve()
        val reader = Reader(source())
        holdChunkOne(reader)
        // Every earlier touch is now older than the eviction guard, so only the lease protects chunk 1.
        clockOffsetMs.addAndGet(10_000L)
        val cursor = Reader(source(continuation = false))
        for (chunk in 10L..25L) {
            cursor.position = chunk * CHUNK
            cursor.readTo(chunk * CHUNK + 1_000)
            cursor.close()
        }
        reader.readTo(3 * CHUNK)
        reader.close()
        assertEquals("first reader got bytes of another chunk, ${reader.stats()}", 0, reader.mismatches)
        assertEquals("second reader, ${cursor.stats()}", 0, cursor.mismatches)
        assertAllMemoryReturned()
    }

    @Test
    fun `paced reader stays correct while sessions are torn down repeatedly`() {
        serve()
        val done = AtomicBoolean(false)
        val chaos = Thread {
            while (!done.get()) {
                Thread.sleep(Random.nextLong(5, 30))
                teardownSessions()
                drainPool()
            }
        }
        chaos.start()
        val reader = Reader(source(), viaByteBuffer = Random.nextBoolean())
        try {
            reader.readTo(file.size.toLong(), pace = true)
        } finally {
            done.set(true)
            chaos.join(5_000)
            reader.close()
        }
        assertEquals("bytes read from freed or reused chunk memory, ${reader.stats()}", 0, reader.mismatches)
        assertEquals(file.size.toLong(), reader.position)
        assertAllMemoryReturned()
    }

    private companion object {
        const val CHUNK = 64L * 1024L
        const val POISON: Byte = 0x5A
    }
}
