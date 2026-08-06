package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

// Proves the AFR probe cancellation fix: blocking OkHttp downloads must stop as soon as the
// isCancelled signal fires, instead of draining the full advertised body in the background.
class FrameRateUtilsAfrCancellationTest {

    @Test
    fun `fetchHttpRangeToFile aborts mid-download when cancel signal fires`() {
        val advertisedLength = 100_000_000L // server is willing to stream 100 MB
        val serverSocket = ServerSocket(0)
        val serverThread = Thread {
            runCatching {
                serverSocket.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) break
                    }
                    val out = socket.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Range: bytes 0-${advertisedLength - 1}/$advertisedLength\r\n" +
                                "Content-Length: $advertisedLength\r\n" +
                                "Content-Type: application/octet-stream\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray()
                    )
                    val chunk = ByteArray(65_536)
                    while (true) {
                        out.write(chunk)
                    }
                }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val tempFile = java.io.File.createTempFile("afr_cancel_mid_", ".tmp")
        val cancelChecks = AtomicInteger(0)
        try {
            val startedNs = System.nanoTime()
            val result = FrameRateUtils.fetchHttpRangeToFile(
                url = "http://127.0.0.1:${serverSocket.localPort}/video.mp4",
                headers = emptyMap(),
                rangeHeader = "bytes=0-${advertisedLength - 1}",
                maxBytes = advertisedLength,
                targetFile = tempFile,
                fileOffset = 0L,
                // Read loop polls before every 8 KB read; flip to cancelled after ~64 polls (~512 KB).
                isCancelled = { cancelChecks.incrementAndGet() > 64 }
            )
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000

            assertFalse("Cancelled fetch must report failure", result.success)
            assertTrue(
                "Cancelled fetch must stop early instead of draining 100 MB, downloaded=${tempFile.length()}B",
                tempFile.length() < 4_000_000L
            )
            assertTrue(
                "Cancelled fetch must return well before the 14s call timeout, took ${elapsedMs}ms",
                elapsedMs < 10_000
            )
        } finally {
            tempFile.delete()
            runCatching { serverSocket.close() }
        }
    }

    @Test
    fun `fetchHttpRangeToFile does not open a connection when already cancelled`() {
        val serverSocket = ServerSocket(0)
        val acceptedConnections = AtomicInteger(0)
        val acceptThread = Thread {
            runCatching {
                while (true) {
                    serverSocket.accept().use { acceptedConnections.incrementAndGet() }
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        val tempFile = java.io.File.createTempFile("afr_cancel_pre_", ".tmp")
        try {
            val result = FrameRateUtils.fetchHttpRangeToFile(
                url = "http://127.0.0.1:${serverSocket.localPort}/video.mp4",
                headers = emptyMap(),
                rangeHeader = "bytes=0-1023",
                maxBytes = 1_024L,
                targetFile = tempFile,
                fileOffset = 0L,
                isCancelled = { true }
            )
            Thread.sleep(300)

            assertFalse("Pre-cancelled fetch must report failure", result.success)
            assertEquals("Pre-cancelled fetch must not touch the network", 0, acceptedConnections.get())
            assertEquals("Pre-cancelled fetch must not write any bytes", 0L, tempFile.length())
        } finally {
            tempFile.delete()
            runCatching { serverSocket.close() }
        }
    }

    @Test
    fun `fetchContentLength does not open a connection when already cancelled`() {
        val serverSocket = ServerSocket(0)
        val acceptedConnections = AtomicInteger(0)
        val acceptThread = Thread {
            runCatching {
                while (true) {
                    serverSocket.accept().use { acceptedConnections.incrementAndGet() }
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        try {
            val length = FrameRateUtils.fetchContentLength(
                url = "http://127.0.0.1:${serverSocket.localPort}/video.mp4",
                headers = emptyMap(),
                isCancelled = { true }
            )
            Thread.sleep(300)

            assertEquals("Pre-cancelled HEAD must report failure", -1L, length)
            assertEquals("Pre-cancelled HEAD must not touch the network", 0, acceptedConnections.get())
        } finally {
            runCatching { serverSocket.close() }
        }
    }

    @Test
    fun `warmHttpOriginForAfrProbe does not open a connection when already cancelled`() {
        val serverSocket = ServerSocket(0)
        val acceptedConnections = AtomicInteger(0)
        val acceptThread = Thread {
            runCatching {
                while (true) {
                    serverSocket.accept().use { acceptedConnections.incrementAndGet() }
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        try {
            val warmed = FrameRateUtils.warmHttpOriginForAfrProbe(
                url = "http://127.0.0.1:${serverSocket.localPort}/video.mp4",
                headers = emptyMap(),
                isCancelled = { true }
            )
            Thread.sleep(300)

            assertFalse("Pre-cancelled warmup must report false", warmed)
            assertEquals("Pre-cancelled warmup must not touch the network", 0, acceptedConnections.get())
        } finally {
            runCatching { serverSocket.close() }
        }
    }
}
