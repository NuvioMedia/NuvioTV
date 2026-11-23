package com.nuvio.tv.ui.screens.player.autosync

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedSubtitleTimelineLoaderTest {
    @Test
    fun concurrentIndexLoadsShareOneDownload(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setHeadersDelay(300L, TimeUnit.MILLISECONDS),
            )
            val url = server.url("/shared.mkv").toString()

            val prefetch = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }
            val autoSync = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }

            withTimeout(3_000L) {
                assertNull(prefetch.await())
                assertNull(autoSync.await())
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun joinedIndexLoadRestartsWhenOwningLoadIsCancelled(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val url = server.url("/handoff.mkv").toString()

            val prefetch = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }
            assertNotNull(
                withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) },
            )
            val autoSync = async { EmbeddedSubtitleTimelineLoader.load(url, emptyMap()) }
            yield()

            server.enqueue(MockResponse().setResponseCode(404))
            withTimeout(1_000L) { prefetch.cancelAndJoin() }

            assertNull(withTimeout(3_000L) { autoSync.await() })
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun failedTracksFetchReturnsNullInsteadOfFalseNoSubtitleTracks(): Unit = runBlocking {
        val mkv = buildAssMkv(
            prefixVoidBytes = 600 * 1024, // Push Tracks past the 512 KiB initial probe.
            codecPrivateSize = 2_000,
            cueStartsMs = (0 until 20).map { 10_000L + it * 5_000L },
        )
        MockWebServer().use { server ->
            server.dispatcher = rangeDispatcher(
                data = mkv,
                failStartsIn = 512_000L..Long.MAX_VALUE,
                failCode = 500,
            )
            server.start()

            val loaded = EmbeddedSubtitleTimelineLoader.load(server.url("/tracks-fail.mkv").toString(), emptyMap())
            assertNull(loaded)
        }
    }

    @Test
    fun chunkCapped206ServerLoadsLargeTracksAndCues(): Unit = runBlocking {
        val starts = (0 until 40).map { 5_000L + it * 3_000L }
        val mkv = buildAssMkv(
            prefixVoidBytes = 600 * 1024, // Tracks outside 512 KiB initial probe
            subtitleTrackCount = 8, // Tracks > 16 KiB metadata probe
            codecPrivateSize = 3_500,
            cueStartsMs = starts,
            padCuesToBytes = 180 * 1024, // Cues > 128 KiB cues probe
            trailingAttachmentsBytes = 2_500 * 1024, // remainingToEof > 2 MiB direct cap
        )
        MockWebServer().use { server ->
            server.dispatcher = rangeDispatcher(
                data = mkv,
                maxChunkBytes = 32 * 1024,
            )
            server.start()

            val loaded = EmbeddedSubtitleTimelineLoader.load(server.url("/chunked.mkv").toString(), emptyMap())
            assertNotNull(loaded)
            assertEquals("matroska-cues", loaded!!.source)
            assertFalse(loaded.noSubtitleTracks)
            assertEquals(starts.size, loaded.tracks.first().cues.size)
        }
    }

    @Test
    fun zeroByteEbmlCueTimeIsDecodedAsZeroMs(): Unit = runBlocking {
        val starts = listOf(0L) + (1 until 15).map { it * 5_000L }
        val mkv = buildAssMkv(
            cueStartsMs = starts,
            encodeZeroTimeAsEmptyUint = true,
        )
        MockWebServer().use { server ->
            server.dispatcher = rangeDispatcher(data = mkv)
            server.start()

            val loaded = EmbeddedSubtitleTimelineLoader.load(server.url("/zero-cuetime.mkv").toString(), emptyMap())
            assertNotNull(loaded)
            val cues = loaded!!.tracks.single().cues
            assertEquals(starts.size, cues.size)
            assertEquals(0L, cues.first().startTimeMs)
        }
    }

    private fun rangeDispatcher(
        data: ByteArray,
        maxChunkBytes: Int? = null,
        failStartsIn: LongRange? = null,
        failCode: Int = 500,
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            if (range != null && range.startsWith("bytes=")) {
                val bounds = range.removePrefix("bytes=").split("-")
                val start = bounds[0].toLong()
                if (failStartsIn != null && start in failStartsIn) {
                    return MockResponse().setResponseCode(failCode)
                }
                var end = bounds[1].toLong().coerceAtMost(data.size - 1L)
                if (maxChunkBytes != null) {
                    end = minOf(end, start + maxChunkBytes - 1L)
                }
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                    .setBody(Buffer().write(data.copyOfRange(start.toInt(), end.toInt() + 1)))
            }
            return MockResponse().setResponseCode(200).setBody(Buffer().write(data))
        }
    }

    private fun buildAssMkv(
        prefixVoidBytes: Int = 0,
        subtitleTrackCount: Int = 1,
        codecPrivateSize: Int = 64,
        cueStartsMs: List<Long>,
        encodeZeroTimeAsEmptyUint: Boolean = false,
        padCuesToBytes: Int = 0,
        trailingAttachmentsBytes: Int = 0,
    ): ByteArray {
        val info = ebmlElem(0x1549A966L, ebmlUint(0x2AD7B1L, 1_000_000L))
        val trackEntries = mutableListOf<ByteArray>()
        trackEntries += ebmlElem(
            0xAEL,
            concat(ebmlUint(0xD7L, 1L), ebmlUint(0x83L, 1L), ebmlStr(0x86L, "V_MPEG4/ISO/AVC")),
        )
        val cpBytes = ("[Script Info]\n" + "A".repeat(codecPrivateSize)).toByteArray(Charsets.UTF_8)
        for (i in 0 until subtitleTrackCount) {
            val trackNum = 2 + i
            trackEntries += ebmlElem(
                0xAEL,
                concat(
                    ebmlUint(0xD7L, trackNum.toLong()),
                    ebmlUint(0x83L, 17L),
                    ebmlStr(0x86L, "S_TEXT/ASS"),
                    ebmlStr(0x22B59CL, "eng"),
                    ebmlElem(0x63A2L, cpBytes),
                ),
            )
        }
        val tracks = ebmlElem(0x1654AE6BL, concat(*trackEntries.toTypedArray()))

        val cuePoints = mutableListOf<ByteArray>()
        for (startMs in cueStartsMs) {
            val cueTime = if (startMs == 0L && encodeZeroTimeAsEmptyUint) {
                ebmlElem(0xB3L, ByteArray(0))
            } else {
                ebmlUint(0xB3L, startMs)
            }
            val cueTp = ebmlElem(0xB7L, concat(ebmlUint(0xF7L, 2L), ebmlUint(0xF1L, 1024L), ebmlUint(0xB2L, 2_000L)))
            cuePoints += ebmlElem(0xBBL, concat(cueTime, cueTp))
        }
        var cuesSize = cuePoints.sumOf { it.size }
        var videoT = 0L
        while (cuesSize < padCuesToBytes) {
            val vp = ebmlElem(
                0xBBL,
                concat(ebmlUint(0xB3L, videoT), ebmlElem(0xB7L, concat(ebmlUint(0xF7L, 1L), ebmlUint(0xF1L, 1024L)))),
            )
            cuePoints += vp
            cuesSize += vp.size
            videoT += 200L
        }
        val cues = ebmlElem(0x1C53BB6BL, concat(*cuePoints.toTypedArray()))
        val prefixVoid = if (prefixVoidBytes > 0) ebmlElem(0xECL, ByteArray(prefixVoidBytes)) else ByteArray(0)
        val cluster = ebmlElem(0x1F43B675L, concat(ebmlUint(0xE7L, 0L), ByteArray(16 * 1024)))
        val trailing = if (trailingAttachmentsBytes > 0) ebmlElem(0x1941A469L, ByteArray(trailingAttachmentsBytes)) else ByteArray(0)

        val seekHeadSlotSize = 300
        val infoPos = seekHeadSlotSize.toLong() + prefixVoid.size
        val tracksPos = infoPos + info.size
        val cuesPos = tracksPos + tracks.size + cluster.size

        val seekHeadRaw = ebmlElem(
            0x114D9B74L,
            concat(
                ebmlSeek(0x1549A966L, infoPos),
                ebmlSeek(0x1654AE6BL, tracksPos),
                ebmlSeek(0x1C53BB6BL, cuesPos),
            ),
        )
        val padTotal = seekHeadSlotSize - seekHeadRaw.size
        val padPayloadLen = padTotal - 3
        val padVoid = ByteArray(padTotal).apply {
            this[0] = 0xEC.toByte()
            this[1] = (0x40 or ((padPayloadLen ushr 8) and 0x3F)).toByte()
            this[2] = (padPayloadLen and 0xFF).toByte()
        }

        val ebmlHeader = ebmlElem(0x1A45DFA3L, concat(ebmlUint(0x4286L, 1L), ebmlStr(0x4282L, "matroska")))
        val segment = ebmlElem(0x18538067L, concat(seekHeadRaw, padVoid, prefixVoid, info, tracks, cluster, cues, trailing))
        return concat(ebmlHeader, segment)
    }

    private fun ebmlSeek(id: Long, relPos: Long): ByteArray =
        ebmlElem(0x4DBBL, concat(ebmlElem(0x53ABL, ebmlId(id)), ebmlUint(0x53ACL, relPos)))

    private fun ebmlId(id: Long): ByteArray {
        var len = 1
        while (len < 8 && id >= (1L shl (len * 8))) len++
        return ByteArray(len) { i -> ((id shr ((len - 1 - i) * 8)) and 0xFF).toByte() }
    }

    private fun ebmlVintSize(size: Long): ByteArray {
        for (len in 1..8) {
            if (size <= (1L shl (7 * len)) - 2L) {
                val out = ByteArray(len)
                var v = size
                for (i in len - 1 downTo 0) {
                    out[i] = (v and 0xFF).toByte()
                    v = v ushr 8
                }
                out[0] = (out[0].toInt() or (1 shl (8 - len))).toByte()
                return out
            }
        }
        error("EBML size too large: $size")
    }

    private fun ebmlElem(id: Long, payload: ByteArray): ByteArray =
        concat(ebmlId(id), ebmlVintSize(payload.size.toLong()), payload)

    private fun ebmlUint(id: Long, value: Long): ByteArray {
        if (value == 0L) return ebmlElem(id, byteArrayOf(0))
        var len = 1
        while (len < 8 && value >= (1L shl (len * 8))) len++
        return ebmlElem(id, ByteArray(len) { i -> ((value shr ((len - 1 - i) * 8)) and 0xFF).toByte() })
    }

    private fun ebmlStr(id: Long, text: String): ByteArray =
        ebmlElem(id, text.toByteArray(Charsets.UTF_8))

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }
}
