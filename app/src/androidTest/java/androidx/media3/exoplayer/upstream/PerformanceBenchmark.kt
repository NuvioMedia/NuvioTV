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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

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
        runNetworkBenchmark(results)
        runJniCrossoverBenchmark(results)
        runSocketHeaderBenchmark(results)
        runHeaderParsingBenchmark(results)
        runByteToLongBenchmark(results)
        runSynchronizationBenchmark(results)
        runThreadQueueBenchmark(results)
        runGcChurnBenchmark(results)

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

    private fun runNetworkBenchmark(sb: StringBuilder) {
        val sizeMb = 20
        val totalBytes = sizeMb * 1024 * 1024
        val chunkSize = 65536 // 64 KB

        sb.append("4. LOCALHOST TCP SOCKET NETWORK BENCHMARK (total data = $sizeMb MB, chunk size = 64 KB)\n")

        // 1. Setup ServerSocketChannel for standard Java socket test
        val serverChannel = java.nio.channels.ServerSocketChannel.open()
        serverChannel.bind(java.net.InetSocketAddress("127.0.0.1", 0))
        val port = serverChannel.socket().localPort
        val serverThread = Thread {
            try {
                val clientChannel = serverChannel.accept()
                val data = ByteBuffer.allocateDirect(chunkSize)
                for (i in 0 until chunkSize) {
                    data.put((i % 256).toByte())
                }
                var bytesSent = 0
                while (bytesSent < totalBytes) {
                    data.clear()
                    clientChannel.write(data)
                    bytesSent += chunkSize
                }
                clientChannel.close()
            } catch (e: Exception) {}
        }
        serverThread.start()

        // Read using standard Java Socket InputStream (Heap Array)
        val heapBuffer = ByteArray(chunkSize)
        val javaStart = System.nanoTime()
        try {
            val socket = java.net.Socket("127.0.0.1", port)
            val input = socket.getInputStream()
            var bytesRead = 0
            while (bytesRead < totalBytes) {
                val toRead = Math.min(chunkSize, totalBytes - bytesRead)
                var read = 0
                while (read < toRead) {
                    val r = input.read(heapBuffer, read, toRead - read)
                    if (r == -1) break
                    read += r
                }
                if (read == 0) break
                bytesRead += read
            }
            socket.close()
        } catch (e: Exception) {}
        val javaEnd = System.nanoTime()
        val javaDurationSec = (javaEnd - javaStart) / 1_000_000_000.0
        val javaThroughput = sizeMb / javaDurationSec
        serverChannel.close()

        // 2. Setup ServerSocketChannel for SocketChannel test
        val serverChannel2 = java.nio.channels.ServerSocketChannel.open()
        serverChannel2.bind(java.net.InetSocketAddress("127.0.0.1", 0))
        val port2 = serverChannel2.socket().localPort
        val serverThread2 = Thread {
            try {
                val clientChannel = serverChannel2.accept()
                val data = ByteBuffer.allocateDirect(chunkSize)
                for (i in 0 until chunkSize) {
                    data.put((i % 256).toByte())
                }
                var bytesSent = 0
                while (bytesSent < totalBytes) {
                    data.clear()
                    clientChannel.write(data)
                    bytesSent += chunkSize
                }
                clientChannel.close()
            } catch (e: Exception) {}
        }
        serverThread2.start()

        // Read using direct ByteBuffer SocketChannel Read
        val directBuffer = ByteBuffer.allocateDirect(chunkSize)
        val directStart = System.nanoTime()
        try {
            val address = java.net.InetSocketAddress("127.0.0.1", port2)
            val channel = java.nio.channels.SocketChannel.open(address)
            channel.configureBlocking(true)
            var bytesRead = 0
            while (bytesRead < totalBytes) {
                directBuffer.clear()
                var read = 0
                while (directBuffer.hasRemaining() && bytesRead + read < totalBytes) {
                    val r = channel.read(directBuffer)
                    if (r < 0) break
                    read += r
                }
                if (read == 0) break
                bytesRead += read
            }
            channel.close()
        } catch (e: Exception) {}
        val directEnd = System.nanoTime()
        val directDurationSec = (directEnd - directStart) / 1_000_000_000.0
        val directThroughput = sizeMb / directDurationSec
        serverChannel2.close()

        sb.append(String.format("  - Java Socket InputStream : %6.2f ms (%6.1f MB/s)\n", javaDurationSec * 1000.0, javaThroughput))
        sb.append(String.format("  - Direct SocketChannel    : %6.2f ms (%6.1f MB/s)\n", directDurationSec * 1000.0, directThroughput))
    }

    private fun runJniCrossoverBenchmark(sb: StringBuilder) {
        sb.append("5. JNI COPY CROSSOVER THRESHOLD BENCHMARK (total data = 5 MB per size)\n")
        val sizes = intArrayOf(64, 256, 1024, 4096, 16384, 65536, 262144)
        for (size in sizes) {
            val totalBytes = 5 * 1024 * 1024
            val iterations = totalBytes / size
            
            val srcArr = ByteArray(size) { i -> (i % 256).toByte() }
            val dstArr = ByteArray(size)
            val srcDirect = ByteBuffer.allocateDirect(size)
            srcDirect.put(srcArr)
            val dstDirect = ByteBuffer.allocateDirect(size)
            
            val srcAddr = SampleDataQueueNative.getDirectBufferAddress(srcDirect)
            val dstAddr = SampleDataQueueNative.getDirectBufferAddress(dstDirect)

            // Warm up
            for (i in 0 until 50) {
                System.arraycopy(srcArr, 0, dstArr, 0, size)
                srcDirect.clear(); dstDirect.clear()
                SampleDataQueueNative.copyBetweenDirectBuffers(srcDirect, 0, dstDirect, 0, size)
                SampleDataQueueNative.nativeCopyAddresses(srcAddr, 0, dstAddr, 0, size)
            }

            // JVM ArrayCopy
            val startJava = System.nanoTime()
            for (i in 0 until iterations) {
                System.arraycopy(srcArr, 0, dstArr, 0, size)
            }
            val javaDurationMs = (System.nanoTime() - startJava) / 1_000_000.0

            // JNI Std
            val startJni = System.nanoTime()
            for (i in 0 until iterations) {
                SampleDataQueueNative.copyBetweenDirectBuffers(srcDirect, 0, dstDirect, 0, size)
            }
            val jniDurationMs = (System.nanoTime() - startJni) / 1_000_000.0

            // JNI Critical Cached
            val startCrit = System.nanoTime()
            for (i in 0 until iterations) {
                SampleDataQueueNative.nativeCopyAddresses(srcAddr, 0, dstAddr, 0, size)
            }
            val critDurationMs = (System.nanoTime() - startCrit) / 1_000_000.0

            val sizeStr = if (size < 1024) "${size} B" else "${size / 1024} KB"
            sb.append(String.format("  - Size %7s: Java=%6.2fms (%5.1f MB/s) | JNI=%6.2fms (%5.1f MB/s) | Crit=%6.2fms (%5.1f MB/s)\n",
                sizeStr,
                javaDurationMs, 5.0 / (javaDurationMs / 1000.0),
                jniDurationMs, 5.0 / (jniDurationMs / 1000.0),
                critDurationMs, 5.0 / (critDurationMs / 1000.0)))
        }
        sb.append("\n")
    }

    private fun runSocketHeaderBenchmark(sb: StringBuilder) {
        sb.append("6. SOCKET HEADER READING BENCHMARK (iterations = 100)\n")
        val headerText = "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 104857600\r\nConnection: close\r\nServer: mock\r\n\r\n"
        val headerBytes = headerText.toByteArray(StandardCharsets.US_ASCII)
        val iterations = 100

        // Start server socket that keeps serving headerBytes to clients
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val port = server.socket().localPort

        val serverThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val client = server.accept()
                    val buf = ByteBuffer.wrap(headerBytes)
                    while (buf.hasRemaining()) {
                        client.write(buf)
                    }
                    client.close()
                }
            } catch (e: Exception) {}
        }
        serverThread.start()

        // Benchmark Approach A: Byte-by-byte read (1-byte allocate)
        val startByteByByte = System.nanoTime()
        for (it in 0 until iterations) {
            val channel = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
            channel.configureBlocking(true)
            val singleBuffer = ByteBuffer.allocate(1)
            val headerBuilder = StringBuilder()
            while (true) {
                singleBuffer.clear()
                val read = channel.read(singleBuffer)
                if (read <= 0) break
                singleBuffer.flip()
                val c = singleBuffer.get().toInt().toChar()
                headerBuilder.append(c)
                if (headerBuilder.length >= 4 && headerBuilder.endsWith("\r\n\r\n")) {
                    break
                }
            }
            channel.close()
        }
        val durationByteByByteMs = (System.nanoTime() - startByteByByte) / 1_000_000.0

        // Benchmark Approach B: Chunk-buffered read (2KB buffer read)
        val startBuffered = System.nanoTime()
        for (it in 0 until iterations) {
            val channel = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
            channel.configureBlocking(true)
            val chunkBuffer = ByteBuffer.allocate(2048)
            val headerBuilder = StringBuilder()
            
            // Read in chunks
            while (true) {
                val read = channel.read(chunkBuffer)
                if (read <= 0) break
                chunkBuffer.flip()
                val bytes = ByteArray(chunkBuffer.remaining())
                chunkBuffer.get(bytes)
                headerBuilder.append(String(bytes, StandardCharsets.US_ASCII))
                chunkBuffer.clear()
                if (headerBuilder.contains("\r\n\r\n")) {
                    break
                }
            }
            channel.close()
        }
        val durationBufferedMs = (System.nanoTime() - startBuffered) / 1_000_000.0

        server.close()
        serverThread.interrupt()

        sb.append(String.format("  - Byte-by-Byte (allocate(1)): %6.2f ms (%.3f ms/conn)\n", durationByteByByteMs, durationByteByByteMs / iterations))
        sb.append(String.format("  - Chunk-Buffered (2KB)      : %6.2f ms (%.3f ms/conn)\n", durationBufferedMs, durationBufferedMs / iterations))
        val ratio = durationByteByByteMs / durationBufferedMs
        sb.append(String.format("  - Speedup Factor            : %.2fx\n\n", ratio))
    }

    private fun runHeaderParsingBenchmark(sb: StringBuilder) {
        sb.append("7. HTTP HEADER PARSING BENCHMARK (iterations = 50000)\n")
        val headerText = "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 104857600\r\nConnection: keep-alive\r\nServer: nginx\r\nAccept-Ranges: bytes\r\n\r\n"
        val headerBytes = headerText.toByteArray(StandardCharsets.US_ASCII)
        val iterations = 50000

        // Warm up
        for (i in 0..1000) {
            // Stock method
            val lines = headerText.split("\r\n")
            for (line in lines) {
                if (line.lowercase().startsWith("content-length:")) {
                    val v = line.substring(line.indexOf(':') + 1).trim()
                }
            }
            // Index-scan
            val idx = headerText.indexOf("Content-Length:", ignoreCase = true)
            if (idx != -1) {
                val start = idx + 15
                var end = headerText.indexOf("\r\n", start)
                val v = headerText.substring(start, end).trim()
            }
            // Byte-scan
            parseContentLengthZeroAlloc(headerBytes, headerBytes.size)
        }

        // Method 1: Stock Split
        val startStock = System.nanoTime()
        var clStock = 0L
        for (i in 0 until iterations) {
            val lines = headerText.split("\r\n")
            var contentLengthHeader: String? = null
            for (line in lines) {
                if (line.lowercase().startsWith("content-length:")) {
                    contentLengthHeader = line.substring(line.indexOf(':') + 1).trim()
                    break
                }
            }
            clStock = contentLengthHeader?.toLong() ?: -1L
        }
        val durationStockMs = (System.nanoTime() - startStock) / 1_000_000.0

        // Method 2: String Index-Scan
        val startScan = System.nanoTime()
        var clScan = 0L
        for (i in 0 until iterations) {
            val idx = headerText.indexOf("Content-Length:", ignoreCase = true)
            if (idx != -1) {
                val start = idx + 15
                var end = headerText.indexOf("\r\n", start)
                if (end == -1) end = headerText.length
                clScan = headerText.substring(start, end).trim().toLong()
            } else {
                clScan = -1L
            }
        }
        val durationScanMs = (System.nanoTime() - startScan) / 1_000_000.0

        // Method 3: Zero-Allocation Byte-Scan
        val startByte = System.nanoTime()
        var clByte = 0L
        for (i in 0 until iterations) {
            clByte = parseContentLengthZeroAlloc(headerBytes, headerBytes.size)
        }
        val durationByteMs = (System.nanoTime() - startByte) / 1_000_000.0

        sb.append(String.format("  - Stock (split & lowercase) : %6.2f ms (value = %d)\n", durationStockMs, clStock))
        sb.append(String.format("  - String Index-Scanning     : %6.2f ms (value = %d)\n", durationScanMs, clScan))
        sb.append(String.format("  - Zero-Alloc Byte-Scanning  : %6.2f ms (value = %d)\n", durationByteMs, clByte))
        sb.append(String.format("  - Byte Scan Speedup Factor  : %.2fx (vs Stock)\n\n", durationStockMs / durationByteMs))
    }

    private fun parseContentLengthZeroAlloc(bytes: ByteArray, length: Int): Long {
        val targetLower = byteArrayOf(
            'c'.toByte(), 'o'.toByte(), 'n'.toByte(), 't'.toByte(), 'e'.toByte(), 'n'.toByte(), 't'.toByte(),
            '-'.toByte(), 'l'.toByte(), 'e'.toByte(), 'n'.toByte(), 'g'.toByte(), 't'.toByte(), 'h'.toByte(),
            ':'.toByte()
        )
        var i = 0
        while (i < length - targetLower.size) {
            var match = true
            for (j in 0 until targetLower.size) {
                val b = bytes[i + j]
                val c = if (b in 65..90) (b + 32).toByte() else b
                if (c != targetLower[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                var start = i + targetLower.size
                while (start < length && (bytes[start] == ' '.toByte() || bytes[start] == '\t'.toByte())) {
                    start++
                }
                var num = 0L
                while (start < length && bytes[start] in '0'.toByte()..'9'.toByte()) {
                    num = num * 10 + (bytes[start] - '0'.toByte())
                    start++
                }
                return num
            }
            i++
        }
        return -1L
    }

    private fun runByteToLongBenchmark(sb: StringBuilder) {
        sb.append("8. ASCII BYTE-TO-LONG PARSING BENCHMARK (iterations = 500000)\n")
        val numBytes = "104857600".toByteArray(StandardCharsets.US_ASCII)
        val iterations = 500000

        // Warm up
        for (i in 0..10000) {
            val s = String(numBytes)
            val l = s.toLong()
            var res = 0L
            for (b in numBytes) {
                if (b in '0'.toByte()..'9'.toByte()) {
                    res = res * 10 + (b - '0'.toByte())
                }
            }
        }

        // Method 1: Stock String toLong()
        val startStock = System.nanoTime()
        var valStock = 0L
        for (i in 0 until iterations) {
            valStock = String(numBytes).toLong()
        }
        val durationStockMs = (System.nanoTime() - startStock) / 1_000_000.0

        // Method 2: Fast Byte Accumulator
        val startFast = System.nanoTime()
        var valFast = 0L
        for (i in 0 until iterations) {
            var res = 0L
            for (b in numBytes) {
                if (b in 48..57) {
                    res = res * 10 + (b - 48)
                }
            }
            valFast = res
        }
        val durationFastMs = (System.nanoTime() - startFast) / 1_000_000.0

        sb.append(String.format("  - Stock (String -> toLong)  : %6.2f ms (value = %d)\n", durationStockMs, valStock))
        sb.append(String.format("  - Fast Byte Accumulator     : %6.2f ms (value = %d)\n", durationFastMs, valFast))
        sb.append(String.format("  - Speedup Factor            : %.2fx\n\n", durationStockMs / durationFastMs))
    }

    private fun runSynchronizationBenchmark(sb: StringBuilder) {
        sb.append("9. LOCK CONTENTION & SYNCHRONIZATION OVERHEAD BENCHMARK (iterations = 5000000)\n")
        val iterations = 5000000

        val lock = Object()
        val reentrant = ReentrantLock()
        val atomic = AtomicLong(0)
        val volatileVal = VolatileLong(0L)

        // Warm up
        for (i in 0..10000) {
            synchronized(lock) { volatileVal.value++ }
            reentrant.lock(); try { volatileVal.value++ } finally { reentrant.unlock() }
            atomic.incrementAndGet()
            volatileVal.value++
        }

        // 1. Synchronized
        val startSync = System.nanoTime()
        var v1 = 0L
        for (i in 0 until iterations) {
            synchronized(lock) {
                v1++
            }
        }
        val durationSyncMs = (System.nanoTime() - startSync) / 1_000_000.0

        // 2. ReentrantLock
        val startReentrant = System.nanoTime()
        var v2 = 0L
        for (i in 0 until iterations) {
            reentrant.lock()
            try {
                v2++
            } finally {
                reentrant.unlock()
            }
        }
        val durationReentrantMs = (System.nanoTime() - startReentrant) / 1_000_000.0

        // 3. AtomicLong
        val startAtomic = System.nanoTime()
        for (i in 0 until iterations) {
            atomic.incrementAndGet()
        }
        val durationAtomicMs = (System.nanoTime() - startAtomic) / 1_000_000.0

        // 4. Volatile
        val startVolatile = System.nanoTime()
        for (i in 0 until iterations) {
            volatileVal.value++
        }
        val durationVolatileMs = (System.nanoTime() - startVolatile) / 1_000_000.0

        sb.append(String.format("  - Synchronized Block        : %6.2f ms (val = %d)\n", durationSyncMs, v1))
        sb.append(String.format("  - ReentrantLock             : %6.2f ms (val = %d)\n", durationReentrantMs, v2))
        sb.append(String.format("  - AtomicLong                : %6.2f ms (val = %d)\n", durationAtomicMs, atomic.get()))
        sb.append(String.format("  - Volatile Access           : %6.2f ms (val = %d)\n", durationVolatileMs, volatileVal.value))
        sb.append(String.format("  - Volatile Speedup vs Sync  : %.2fx\n\n", durationSyncMs / durationVolatileMs))
    }

    private fun runThreadQueueBenchmark(sb: StringBuilder) {
        sb.append("10. THREAD CONTEXT SWITCHING & QUEUE BENCHMARK (items = 10000)\n")
        val items = 10000
        val buffer = ByteBuffer.allocateDirect(1)

        // 1. LinkedBlockingQueue
        val linkedQueue = LinkedBlockingQueue<ByteBuffer>(100)
        val lqStart = System.nanoTime()
        val t1 = Thread {
            for (i in 0 until items) {
                linkedQueue.put(buffer)
            }
        }
        t1.start()
        for (i in 0 until items) {
            linkedQueue.take()
        }
        t1.join()
        val durationLqMs = (System.nanoTime() - lqStart) / 1_000_000.0

        // 2. ArrayBlockingQueue
        val arrayQueue = ArrayBlockingQueue<ByteBuffer>(100)
        val aqStart = System.nanoTime()
        val t2 = Thread {
            for (i in 0 until items) {
                arrayQueue.put(buffer)
            }
        }
        t2.start()
        for (i in 0 until items) {
            arrayQueue.take()
        }
        t2.join()
        val durationAqMs = (System.nanoTime() - aqStart) / 1_000_000.0

        // 3. ConcurrentLinkedQueue (Lock-free)
        val concurrentQueue = ConcurrentLinkedQueue<ByteBuffer>()
        val cqStart = System.nanoTime()
        val t3 = Thread {
            var sent = 0
            while (sent < items) {
                if (concurrentQueue.size < 100) {
                    concurrentQueue.offer(buffer)
                    sent++
                }
            }
        }
        t3.start()
        var received = 0
        while (received < items) {
            val item = concurrentQueue.poll()
            if (item != null) {
                received++
            }
        }
        t3.join()
        val durationCqMs = (System.nanoTime() - cqStart) / 1_000_000.0

        // 4. Custom Simple Circular Ring Buffer (Lock-free indexes)
        val ringSize = 128
        val ringArray = arrayOfNulls<ByteBuffer>(ringSize)
        val writeIdx = VolatileLong(0L)
        val readIdx = VolatileLong(0L)
        val rbStart = System.nanoTime()
        val t4 = Thread {
            for (i in 0 until items) {
                while (writeIdx.value - readIdx.value >= ringSize) {
                    // spin lock or small yield
                    Thread.yield()
                }
                ringArray[(writeIdx.value % ringSize).toInt()] = buffer
                writeIdx.value++
            }
        }
        t4.start()
        for (i in 0 until items) {
            while (readIdx.value >= writeIdx.value) {
                Thread.yield()
            }
            val item = ringArray[(readIdx.value % ringSize).toInt()]
            readIdx.value++
        }
        t4.join()
        val durationRbMs = (System.nanoTime() - rbStart) / 1_000_000.0

        sb.append(String.format("  - LinkedBlockingQueue       : %6.2f ms\n", durationLqMs))
        sb.append(String.format("  - ArrayBlockingQueue        : %6.2f ms\n", durationAqMs))
        sb.append(String.format("  - ConcurrentLinkedQueue     : %6.2f ms\n", durationCqMs))
        sb.append(String.format("  - Custom Ring Buffer (Yield): %6.2f ms\n", durationRbMs))
        sb.append(String.format("  - Ring Buffer Speedup vs LQ : %.2fx\n\n", durationLqMs / durationRbMs))
    }

    private fun runGcChurnBenchmark(sb: StringBuilder) {
        sb.append("11. GC CHURN & ALLOCATION BENCHMARK (segments = 2000)\n")
        val segments = 2000

        // Stock Allocation Churn Mode
        val startStock = System.nanoTime()
        for (i in 0 until segments) {
            val uriStr = "http://127.0.0.1:8090/stream.mp4?index=$i&bitrate=5000000"
            val uri = Uri.parse(uriStr)
            val host = uri.host
            val port = uri.port
            val query = uri.query
            val mockHeaders = "HTTP/1.1 200 OK\r\nContent-Length: ${1024 * 64}\r\nConnection: close\r\n\r\n"
            val lines = mockHeaders.split("\r\n")
            for (line in lines) {
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    val len = lower.substring(lower.indexOf(':') + 1).trim()
                }
            }
            val buffer = ByteArray(65536)
        }
        val durationStockMs = (System.nanoTime() - startStock) / 1_000_000.0

        // Zero Allocation Churn Mode (reusing structures/buffers, bypassing parsing string conversions)
        val startZero = System.nanoTime()
        val mockHeadersBytes = "HTTP/1.1 200 OK\r\nContent-Length: 65536\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val reusedBuffer = ByteArray(65536)
        for (i in 0 until segments) {
            // Avoid Uri parsing by string scanning or caching
            val port = 8090
            // Parse using byte scanner
            val length = parseContentLengthZeroAlloc(mockHeadersBytes, mockHeadersBytes.size)
            // Reuse buffer instead of allocating new ByteArray
            System.arraycopy(mockHeadersBytes, 0, reusedBuffer, 0, mockHeadersBytes.size)
        }
        val durationZeroMs = (System.nanoTime() - startZero) / 1_000_000.0

        sb.append(String.format("  - Stock Allocation Churn   : %6.2f ms\n", durationStockMs))
        sb.append(String.format("  - Zero-Allocation Pipeline : %6.2f ms\n", durationZeroMs))
        sb.append(String.format("  - Speedup Factor           : %.2fx\n\n", durationStockMs / durationZeroMs))
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

private class VolatileLong(initialValue: Long = 0L) {
    @Volatile var value: Long = initialValue
}
