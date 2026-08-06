package com.nuvio.tv.core.player

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

// Reproduces the MP4 peek loop bug: when a server returns a 206 with fewer than 8 bytes for a
// box-header peek, the mdat walk cannot progress and must abort instead of re-requesting the
// same offset for all 12 peek budget iterations.
class FrameRateUtilsMp4PeekLoopTest {

    private val contentTotal = 10_000_000L
    private val freeBoxSize = 8_000_000L
    private val peekOffset = 32L + freeBoxSize // ftyp(32) + free payload => next header offset

    // Head served for bytes=0-*: ftyp box + header-only 'free' box declaring an 8 MB payload,
    // so the walk needs a remote peek at peekOffset to continue.
    private fun buildHeadBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeInt(32)
        dos.write("ftyp".toByteArray(Charsets.US_ASCII))
        dos.write(ByteArray(24))
        dos.writeInt(freeBoxSize.toInt())
        dos.write("free".toByteArray(Charsets.US_ASCII))
        return bos.toByteArray()
    }

    private fun respond206(out: java.io.OutputStream, startByte: Long, body: ByteArray) {
        out.write(
            (
                "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Range: bytes $startByte-${startByte + body.size - 1}/$contentTotal\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n"
                ).toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private fun serveConnection(socket: Socket, rangeStarts: MutableList<Long>) {
        socket.use {
            val reader = it.getInputStream().bufferedReader()
            val out = it.getOutputStream()
            while (true) {
                val requestLine = reader.readLine() ?: return
                if (requestLine.isBlank()) continue
                var rangeStart = 0L
                while (true) {
                    val header = reader.readLine() ?: return
                    if (header.isBlank()) break
                    if (header.startsWith("Range:", ignoreCase = true)) {
                        rangeStart = header.substringAfter("bytes=").substringBefore('-')
                            .trim().toLongOrNull() ?: 0L
                    }
                }
                rangeStarts.add(rangeStart)
                if (rangeStart == peekOffset) {
                    // Short 206: only 4 bytes although 64 were requested.
                    respond206(out, rangeStart, ByteArray(4))
                } else {
                    respond206(out, rangeStart, buildHeadBytes())
                }
            }
        }
    }

    @Test
    fun `short header peek aborts the mdat walk instead of repeating the same request`() {
        val serverSocket = ServerSocket(0)
        val rangeStarts = Collections.synchronizedList(mutableListOf<Long>())
        val acceptThread = Thread {
            runCatching {
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    Thread { runCatching { serveConnection(socket, rangeStarts) } }
                        .apply { isDaemon = true }
                        .start()
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        val cacheDir = java.nio.file.Files.createTempDirectory("afr_peek_loop_test").toFile()
        val context = mockk<Context> {
            every { getCacheDir() } returns cacheDir
        }

        try {
            val detection = FrameRateUtils.detectFrameRateWithOkHttpProbe(
                context = context,
                sourceUrl = "http://127.0.0.1:${serverSocket.localPort}/video.mp4",
                headers = emptyMap()
            )
            assertNull("Probe must fail cleanly on unprogressable peek", detection)

            val peekRequestCount = rangeStarts.count { it == peekOffset }
            assertEquals(
                "A short peek that cannot supply a full box header must abort after one attempt, " +
                    "got $peekRequestCount identical requests for offset $peekOffset",
                1,
                peekRequestCount
            )
        } finally {
            runCatching { serverSocket.close() }
            cacheDir.deleteRecursively()
        }
    }
}
