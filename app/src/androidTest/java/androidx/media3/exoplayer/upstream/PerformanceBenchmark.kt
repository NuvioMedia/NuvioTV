package androidx.media3.exoplayer.upstream

import android.net.Uri
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.datasource.AesCipherDataSource
import androidx.media3.datasource.AesFlushingCipher
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.SampleDataQueueNative
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import javax.crypto.Cipher

@RunWith(AndroidJUnit4::class)
class PerformanceBenchmark {

    @Before
    fun setUp() {
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
    }

    @After
    fun tearDown() {
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
    }

    @Test
    fun runAllBenchmarks() {
        val results = StringBuilder()
        results.append("\n==================================================\n")
        results.append("         NUVIO TV PERFORMANCE BENCHMARKS          \n")
        results.append("==================================================\n\n")

        runAllocatorBenchmark(results)
        runCopyBenchmark(results)
        runAesBenchmark(results)

        results.append("==================================================\n")
        
        // Output benchmark results to instrumentation stdout stream
        println(results.toString())
    }

    private fun runAllocatorBenchmark(sb: StringBuilder) {
        val iterations = 5000
        val size = 65536 // 64 KB

        sb.append("1. ALLOCATOR BENCHMARK (size = 64 KB, iterations = $iterations)\n")

        // Warm up
        for (i in 0..500) {
            val a = ByteArray(size)
            val b = DefaultAllocatorNative.createAllocation(size)
            if (b != null) DefaultAllocatorNative.freeAllocation(b)
        }

        // JVM Heap allocation
        val heapStart = System.nanoTime()
        val heapList = arrayOfNulls<ByteArray>(iterations)
        for (i in 0 until iterations) {
            heapList[i] = ByteArray(size)
        }
        val heapEnd = System.nanoTime()
        val heapDurationMs = (heapEnd - heapStart) / 1_000_000.0

        // JNI Native Allocation
        val nativeStart = System.nanoTime()
        val nativeList = arrayOfNulls<Allocation>(iterations)
        for (i in 0 until iterations) {
            nativeList[i] = DefaultAllocatorNative.createAllocation(size)
        }
        val nativeEnd = System.nanoTime()
        val nativeDurationMs = (nativeEnd - nativeStart) / 1_000_000.0

        // Clean up native allocations immediately to avoid memory leaks
        for (i in 0 until iterations) {
            nativeList[i]?.let { DefaultAllocatorNative.freeAllocation(it) }
        }

        val heapOpsSec = (iterations / (heapDurationMs / 1000.0)).toInt()
        val nativeOpsSec = (iterations / (nativeDurationMs / 1000.0)).toInt()

        sb.append(String.format("  - JVM Heap Allocation   : %6.2f ms (%d ops/sec)\n", heapDurationMs, heapOpsSec))
        sb.append(String.format("  - JNI Native Allocation : %6.2f ms (%d ops/sec)\n", nativeDurationMs, nativeOpsSec))
        val ratio = heapDurationMs / nativeDurationMs
        sb.append(String.format("  - Native Speedup Factor : %.2fx\n\n", ratio))
    }

    private fun runCopyBenchmark(sb: StringBuilder) {
        val sizeMb = 100
        val chunkSize = 65536 // 64 KB
        val totalChunks = (sizeMb * 1024 * 1024) / chunkSize

        sb.append("2. MEMORY COPY BENCHMARK (total data = $sizeMb MB, chunk size = 64 KB)\n")

        val sourceBytes = ByteArray(chunkSize) { i -> (i % 256).toByte() }
        val targetBytes = ByteArray(chunkSize)
        val directSource = ByteBuffer.allocateDirect(chunkSize)
        directSource.put(sourceBytes)
        val directTarget = ByteBuffer.allocateDirect(chunkSize)

        val sourceAddr = SampleDataQueueNative.getDirectBufferAddress(directSource)
        val targetAddr = SampleDataQueueNative.getDirectBufferAddress(directTarget)

        // Warm up
        for (i in 0..100) {
            System.arraycopy(sourceBytes, 0, targetBytes, 0, chunkSize)
            directSource.clear()
            directTarget.clear()
            directTarget.put(directSource)
            SampleDataQueueNative.copyBetweenDirectBuffers(directSource, 0, directTarget, 0, chunkSize)
            SampleDataQueueNative.copyBetweenAddresses(directSource, 0, directTarget, 0, chunkSize)
            SampleDataQueueNative.nativeCopyAddresses(sourceAddr, 0, targetAddr, 0, chunkSize)
            
            directTarget.clear()
            directTarget.put(sourceBytes, 0, chunkSize)
            SampleDataQueueNative.copyFromArray(sourceBytes, 0, directTarget, 0, chunkSize)
            
            directSource.clear()
            directSource.get(targetBytes, 0, chunkSize)
            SampleDataQueueNative.copyToArray(directSource, 0, targetBytes, 0, chunkSize)
        }

        // 1. JVM Standard Copy (Heap to Heap)
        val javaStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            System.arraycopy(sourceBytes, 0, targetBytes, 0, chunkSize)
        }
        val javaEnd = System.nanoTime()
        val javaDurationSec = (javaEnd - javaStart) / 1_000_000_000.0
        val javaThroughput = sizeMb / javaDurationSec

        // 2. Direct-to-Direct Copies
        val javaDirectStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            directSource.clear()
            directTarget.clear()
            directTarget.put(directSource)
        }
        val javaDirectEnd = System.nanoTime()
        val javaDirectDurationSec = (javaDirectEnd - javaDirectStart) / 1_000_000_000.0
        val javaDirectThroughput = sizeMb / javaDirectDurationSec

        val jniStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            SampleDataQueueNative.copyBetweenDirectBuffers(directSource, 0, directTarget, 0, chunkSize)
        }
        val jniEnd = System.nanoTime()
        val jniDurationSec = (jniEnd - jniStart) / 1_000_000_000.0
        val jniThroughput = sizeMb / jniDurationSec

        val jniCriticalStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            SampleDataQueueNative.copyBetweenAddresses(directSource, 0, directTarget, 0, chunkSize)
        }
        val jniCriticalEnd = System.nanoTime()
        val jniCriticalDurationSec = (jniCriticalEnd - jniCriticalStart) / 1_000_000_000.0
        val jniCriticalThroughput = sizeMb / jniCriticalDurationSec

        val jniCriticalCachedStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            SampleDataQueueNative.nativeCopyAddresses(sourceAddr, 0, targetAddr, 0, chunkSize)
        }
        val jniCriticalCachedEnd = System.nanoTime()
        val jniCriticalCachedDurationSec = (jniCriticalCachedEnd - jniCriticalCachedStart) / 1_000_000_000.0
        val jniCriticalCachedThroughput = sizeMb / jniCriticalCachedDurationSec

        // 3. Array-to-Direct Copies
        val javaArrayToDirectStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            directTarget.clear()
            directTarget.put(sourceBytes, 0, chunkSize)
        }
        val javaArrayToDirectEnd = System.nanoTime()
        val javaArrayToDirectDurationSec = (javaArrayToDirectEnd - javaArrayToDirectStart) / 1_000_000_000.0
        val javaArrayToDirectThroughput = sizeMb / javaArrayToDirectDurationSec

        val jniArrayToDirectStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            SampleDataQueueNative.copyFromArray(sourceBytes, 0, directTarget, 0, chunkSize)
        }
        val jniArrayToDirectEnd = System.nanoTime()
        val jniArrayToDirectDurationSec = (jniArrayToDirectEnd - jniArrayToDirectStart) / 1_000_000_000.0
        val jniArrayToDirectThroughput = sizeMb / jniArrayToDirectDurationSec

        // 4. Direct-to-Array Copies
        val javaDirectToArrayStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            directSource.clear()
            directSource.get(targetBytes, 0, chunkSize)
        }
        val javaDirectToArrayEnd = System.nanoTime()
        val javaDirectToArrayDurationSec = (javaDirectToArrayEnd - javaDirectToArrayStart) / 1_000_000_000.0
        val javaDirectToArrayThroughput = sizeMb / javaDirectToArrayDurationSec

        val jniDirectToArrayStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            SampleDataQueueNative.copyToArray(directSource, 0, targetBytes, 0, chunkSize)
        }
        val jniDirectToArrayEnd = System.nanoTime()
        val jniDirectToArrayDurationSec = (jniDirectToArrayEnd - jniDirectToArrayStart) / 1_000_000_000.0
        val jniDirectToArrayThroughput = sizeMb / jniDirectToArrayDurationSec

        sb.append(String.format("  - JVM System.arraycopy     : %6.2f ms (%6.1f MB/s)\n", javaDurationSec * 1000.0, javaThroughput))
        sb.append(String.format("  - Java Direct-to-Direct    : %6.2f ms (%6.1f MB/s)\n", javaDirectDurationSec * 1000.0, javaDirectThroughput))
        sb.append(String.format("  - JNI Direct-to-Direct     : %6.2f ms (%6.1f MB/s)\n", jniDurationSec * 1000.0, jniThroughput))
        sb.append(String.format("  - CritNative D-to-D (Refl) : %6.2f ms (%6.1f MB/s)\n", jniCriticalDurationSec * 1000.0, jniCriticalThroughput))
        sb.append(String.format("  - CritNative D-to-D (Cache): %6.2f ms (%6.1f MB/s)\n", jniCriticalCachedDurationSec * 1000.0, jniCriticalCachedThroughput))
        sb.append(String.format("  - Java Array-to-Direct     : %6.2f ms (%6.1f MB/s)\n", javaArrayToDirectDurationSec * 1000.0, javaArrayToDirectThroughput))
        sb.append(String.format("  - JNI Array-to-Direct      : %6.2f ms (%6.1f MB/s)\n", jniArrayToDirectDurationSec * 1000.0, jniArrayToDirectThroughput))
        sb.append(String.format("  - Java Direct-to-Array     : %6.2f ms (%6.1f MB/s)\n", javaDirectToArrayDurationSec * 1000.0, javaDirectToArrayThroughput))
        sb.append(String.format("  - JNI Direct-to-Array      : %6.2f ms (%6.1f MB/s)\n", jniDirectToArrayDurationSec * 1000.0, jniDirectToArrayThroughput))
        
        sb.append(String.format("  - Direct-to-Direct Ratio (JNI vs Java)   : %.2fx\n", javaDirectDurationSec / jniDurationSec))
        sb.append(String.format("  - Direct-to-Direct Ratio (CritR vs Java) : %.2fx\n", javaDirectDurationSec / jniCriticalDurationSec))
        sb.append(String.format("  - Direct-to-Direct Ratio (CritC vs Java) : %.2fx\n", javaDirectDurationSec / jniCriticalCachedDurationSec))
        sb.append(String.format("  - Direct-to-Direct Ratio (CritC vs JNI)  : %.2fx\n", jniDurationSec / jniCriticalCachedDurationSec))
        sb.append(String.format("  - Array-to-Direct Ratio (JNI vs Java)    : %.2fx\n", javaArrayToDirectDurationSec / jniArrayToDirectDurationSec))
        sb.append(String.format("  - Direct-to-Array Ratio (JNI vs Java)    : %.2fx\n\n", javaDirectToArrayDurationSec / jniDirectToArrayDurationSec))
    }


    private fun runAesBenchmark(sb: StringBuilder) {
        val sizeMb = 10
        val chunkSize = 65536 // 64 KB
        val totalChunks = (sizeMb * 1024 * 1024) / chunkSize

        sb.append("3. AES DECRYPTION PATH BENCHMARK (total data = $sizeMb MB, chunk size = 64 KB)\n")

        val secretKey = ByteArray(16) { i -> (i + 1).toByte() }
        val plaintext = ByteArray(chunkSize) { i -> (i % 256).toByte() }
        val nonce = "perf-nonce-key"

        val encryptCipher = AesFlushingCipher(Cipher.ENCRYPT_MODE, secretKey, nonce, 0L)
        val ciphertext = plaintext.clone()
        encryptCipher.updateInPlace(ciphertext, 0, ciphertext.size)

        val realUri = Uri.parse("http://test.com/video")
        val dataSpec = DataSpec.Builder().setUri(realUri).setKey(nonce).build()

        // 1. Heap-based AES path
        val heapDataSource = ByteArrayDataSource(ciphertext)
        val heapAesDataSource = AesCipherDataSource(secretKey, heapDataSource)
        
        val heapTarget = ByteArray(chunkSize)
        val heapStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            heapAesDataSource.open(dataSpec)
            var read = 0
            while (read < chunkSize) {
                val r = heapAesDataSource.read(heapTarget, read, chunkSize - read)
                if (r == -1) break
                read += r
            }
            heapAesDataSource.close()
        }
        val heapEnd = System.nanoTime()
        val heapDurationSec = (heapEnd - heapStart) / 1_000_000_000.0
        val heapThroughput = sizeMb / heapDurationSec

        // 2. Zero-Copy direct ByteBuffer AES path
        val directDataSource = FakeByteBufferDataSource(ciphertext)
        val directAesDataSource = AesCipherDataSource(secretKey, directDataSource)

        val directTarget = ByteBuffer.allocateDirect(chunkSize)
        val directStart = System.nanoTime()
        for (i in 0 until totalChunks) {
            directAesDataSource.open(dataSpec)
            directDataSource.resetPosition()
            directTarget.clear()
            directAesDataSource.read(directTarget, chunkSize)
            directAesDataSource.close()
        }
        val directEnd = System.nanoTime()
        val directDurationSec = (directEnd - directStart) / 1_000_000_000.0
        val directThroughput = sizeMb / directDurationSec

        sb.append(String.format("  - Heap AesCipherDataSource : %6.2f ms (%6.1f MB/s)\n", heapDurationSec * 1000.0, heapThroughput))
        sb.append(String.format("  - Direct Zero-Copy AES Path : %6.2f ms (%6.1f MB/s)\n", directDurationSec * 1000.0, directThroughput))
        val ratio = heapDurationSec / directDurationSec
        sb.append(String.format("  - Direct Speedup Factor     : %.2fx\n\n", ratio))
    }

    private class FakeByteBufferDataSource(private val data: ByteArray) : DataSource, androidx.media3.common.ByteBufferDataReader {
        private var position = 0

        fun resetPosition() {
            position = 0
        }

        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long = data.size.toLong()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return -1
            val bytesToRead = Math.min(length, data.size - position)
            System.arraycopy(data, position, buffer, offset, bytesToRead)
            position += bytesToRead
            return bytesToRead
        }
        override fun supportsByteBufferRead(): Boolean = true
        override fun read(buffer: ByteBuffer, length: Int): Int {
            if (position >= data.size) return -1
            val bytesToRead = Math.min(length, data.size - position)
            buffer.put(data, position, bytesToRead)
            position += bytesToRead
            return bytesToRead
        }
        override fun getUri(): Uri? = null
        override fun close() {}
    }
}
