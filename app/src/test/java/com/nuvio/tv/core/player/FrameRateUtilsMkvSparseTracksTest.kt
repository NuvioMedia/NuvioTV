package com.nuvio.tv.core.player

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket

// Reproduces the MKV Pass 2 gap: after the sparse Tracks fetch the probe file must be
// terminated with a stub Cluster (and a patched Segment size), exactly like Pass 1 does,
// otherwise demuxers discard the freshly fetched Tracks because the file has no Cluster.
class FrameRateUtilsMkvSparseTracksTest {

    private val tracksRel = 5_000_000L
    private val tracksPayload = ByteArray(120) { 0x11 }

    private fun buildHead(): Pair<ByteArray, ByteArray> {
        val ebml = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_EBML,
            byteArrayOf(0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01)
        )
        val seekId = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_SEEK_ID,
            MatroskaAfrProbe.elementIdBytes(MatroskaAfrProbe.ID_TRACKS)
        )
        val seekPos = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_SEEK_POSITION,
            byteArrayOf(
                ((tracksRel shr 24) and 0xFF).toByte(),
                ((tracksRel shr 16) and 0xFF).toByte(),
                ((tracksRel shr 8) and 0xFF).toByte(),
                (tracksRel and 0xFF).toByte()
            )
        )
        val seek = MatroskaAfrProbe.buildElement(MatroskaAfrProbe.ID_SEEK, seekId + seekPos)
        val seekHead = MatroskaAfrProbe.buildElement(MatroskaAfrProbe.ID_SEEK_HEAD, seek)
        // Torn Attachments so the head ends mid-element and Tracks stays out of the prefix.
        val tornAttachments = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_ATTACHMENTS,
            ByteArray(4096) { 0x22 }
        ).copyOf(48)
        val segmentSize = MatroskaAfrProbe.encodeSizeVintFixedWidth(10_000_000L, 8)!!
        val segment = MatroskaAfrProbe.elementIdBytes(MatroskaAfrProbe.ID_SEGMENT) +
            segmentSize + seekHead + tornAttachments
        return ebml to (ebml + segment)
    }

    private fun serveTracksSlices(socket: Socket, tracksAbs: Long, tracksElement: ByteArray) {
        socket.use {
            val reader = it.getInputStream().bufferedReader()
            val out = it.getOutputStream()
            while (true) {
                val requestLine = reader.readLine() ?: return
                if (requestLine.isBlank()) continue
                var start = 0L
                var end = 0L
                while (true) {
                    val header = reader.readLine() ?: return
                    if (header.isBlank()) break
                    if (header.startsWith("Range:", ignoreCase = true)) {
                        val spec = header.substringAfter("bytes=")
                        start = spec.substringBefore('-').trim().toLongOrNull() ?: 0L
                        end = spec.substringAfter('-').trim().toLongOrNull() ?: 0L
                    }
                }
                val sliceFrom = (start - tracksAbs).toInt().coerceAtLeast(0)
                val sliceTo = ((end - tracksAbs).toInt() + 1).coerceAtMost(tracksElement.size)
                val body = tracksElement.copyOfRange(sliceFrom, sliceTo)
                out.write(
                    (
                        "HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Range: bytes $start-${start + body.size - 1}/${tracksAbs + tracksElement.size}\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Content-Type: application/octet-stream\r\n\r\n"
                        ).toByteArray()
                )
                out.write(body)
                out.flush()
            }
        }
    }

    @Test
    fun `sparse Tracks fetch terminates the probe file with a stub Cluster`() {
        val (ebml, head) = buildHead()
        val layoutBefore = MatroskaAfrProbe.analyzeHeadBytes(head)
        assertNotNull(layoutBefore)
        val tracksAbs = layoutBefore!!.tracksAbsoluteOffset
        assertNotNull("Test setup: SeekHead must resolve Tracks offset", tracksAbs)
        val tracksElement = MatroskaAfrProbe.buildElement(MatroskaAfrProbe.ID_TRACKS, tracksPayload)

        val serverSocket = ServerSocket(0)
        val acceptThread = Thread {
            runCatching {
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    Thread { runCatching { serveTracksSlices(socket, tracksAbs!!, tracksElement) } }
                        .apply { isDaemon = true }
                        .start()
                }
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        val tempDir = java.nio.file.Files.createTempDirectory("afr_mkv_sparse_test").toFile()
        val tempFile = File(tempDir, "mkv_head.tmp").apply { writeBytes(head) }
        val context = mockk<Context> {
            every { getCacheDir() } returns tempDir
        }

        try {
            // Detection itself returns null on the JVM (no native demuxers); the contract under
            // test is the on-disk file layout the demuxer would see.
            FrameRateUtils.probeMkvFromHeadAndSparseTracks(
                context = context,
                targetUrl = "http://127.0.0.1:${serverSocket.localPort}/video.mkv",
                headers = emptyMap(),
                tempFile = tempFile,
                rangeSatisfied = true
            )

            val bytes = tempFile.readBytes()
            val stub = MatroskaAfrProbe.buildElement(MatroskaAfrProbe.ID_CLUSTER, ByteArray(0))
            assertArrayEquals(
                "Probe file must end with a stub Cluster after the sparse Tracks fetch",
                stub,
                bytes.copyOfRange(bytes.size - stub.size, bytes.size)
            )

            // Segment size must be rewritten so the Segment ends exactly at EOF.
            val segment = MatroskaAfrProbe.readElement(bytes, ebml.size.toLong())
            assertNotNull(segment)
            assertEquals(MatroskaAfrProbe.ID_SEGMENT, segment!!.id)
            assertEquals(bytes.size.toLong(), segment.endExclusiveOrNull())
        } finally {
            runCatching { serverSocket.close() }
            tempDir.deleteRecursively()
        }
    }
}
