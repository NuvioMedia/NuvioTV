package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.Util
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class HlsParameterSetInitTest {

    @Test
    fun `segment opening on an IDR frame has no parameter sets`() {
        val head = HlsParameterSetInit.scanSegmentHead(segment(withParameterSets = false))!!

        assertEquals(VIDEO_PID, head.videoPid)
        assertTrue(head.reachedFirstSlice)
        assertFalse(head.hasParameterSets)
    }

    @Test
    fun `parameter sets ahead of the first slice are read without start code bytes`() {
        val head = HlsParameterSetInit.scanSegmentHead(segment(withParameterSets = true))!!

        assertTrue(head.reachedFirstSlice)
        assertArrayEquals(SPS, head.sps)
        assertArrayEquals(PPS, head.pps)
    }

    @Test
    fun `stream without H264 video is not scanned`() {
        assertNull(HlsParameterSetInit.scanSegmentHead(ByteArray(188 * 4) { 0x47 }))
        assertNull(HlsParameterSetInit.scanSegmentHead(ByteArray(4096)))
    }

    @Test
    fun `segment head is settled once the first slice or a non H264 stream shows`() {
        val segment = segment(withParameterSets = true)
        // The tables alone, then the first video packet with the SPS, PPS and the start of the IDR slice.
        assertFalse(HlsParameterSetInit.isSegmentHeadConclusive(segment.copyOf(376)))
        assertTrue(HlsParameterSetInit.isSegmentHeadConclusive(segment.copyOf(564)))

        // AAC only: settled as soon as the PMT is read, once enough packets show it is a transport stream.
        assertTrue(HlsParameterSetInit.isSegmentHeadConclusive(tables(streamType = 0x0F) + tsPacket(0x1FFF, ByteArray(0))))
        assertFalse(HlsParameterSetInit.isSegmentHeadConclusive(ByteArray(100)))
        assertTrue(HlsParameterSetInit.isSegmentHeadConclusive(ByteArray(4096)))
    }

    @Test
    fun `init segment carries the tables and parameter sets in at least five packets`() {
        val head = HlsParameterSetInit.scanSegmentHead(segment(withParameterSets = true))!!
        val init = HlsParameterSetInit.buildInitSegment(head)!!

        assertEquals(0, init.size % 188)
        assertTrue(init.size / 188 >= 5)
        assertArrayEquals(head.patPacket, init.copyOfRange(0, 188))
        assertArrayEquals(head.pmtPacket, init.copyOfRange(188, 376))
        assertTrue(init.toByteString().indexOf((START + SPS + START + PPS).toByteString()) > 376)
    }

    @Test
    fun `playlist gets an init segment only when the second segment lacks parameter sets`() {
        val playlist = mediaPlaylist()
        val first = segment(withParameterSets = true)
        val fetched = ArrayList<String>()

        val rewritten = HlsParameterSetInit.withParameterSetInit(playlist, "https://host/hls/index.m3u8?t=1") { uri ->
            fetched += uri
            if (uri.endsWith("seg_000.ts")) first else segment(withParameterSets = false)
        }!!

        assertEquals(listOf("https://host/hls/seg_001.ts", "https://host/hls/seg_000.ts"), fetched)
        val mapLine = rewritten.lines().single { it.startsWith("#EXT-X-MAP") }
        assertTrue(rewritten.indexOf(mapLine) < rewritten.indexOf("#EXTINF"))
        val init = mapLine.substringAfter("base64,").removeSuffix("\"").decodeBase64()!!.toByteArray()
        assertArrayEquals(HlsParameterSetInit.buildInitSegment(HlsParameterSetInit.scanSegmentHead(first)!!), init)

        val healthy = HlsParameterSetInit.withParameterSetInit(playlist, "https://host/hls/index.m3u8") {
            segment(withParameterSets = true)
        }
        assertNull(healthy)
    }

    @Test
    fun `only finished clear media playlists without their own init segment qualify`() {
        assertEquals(listOf("seg_000.ts", "seg_001.ts", "seg_002.ts"), HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist()))
        assertNotNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(extraHeader = "#EXT-X-DISCONTINUITY-SEQUENCE:0")))
        assertNotNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(extraHeader = "#EXT-X-KEY:METHOD=NONE")))

        assertNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(ended = false)))
        assertNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(extraHeader = "#EXT-X-MAP:URI=\"init.mp4\"")))
        assertNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(extraHeader = "#EXT-X-KEY:METHOD=AES-128,URI=\"k\"")))
        assertNull(HlsParameterSetInit.eligibleSegmentUris(mediaPlaylist(extraHeader = "#EXT-X-DISCONTINUITY")))
        assertNull(
            HlsParameterSetInit.eligibleSegmentUris(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"avc1.640028\"\nindex.m3u8\n"
            )
        )
    }

    @Test
    fun `segment check gives up at its time limit`() {
        val started = System.nanoTime()
        assertNull(resultWithin(100) { Thread.sleep(5_000); "late" })
        assertTrue(System.nanoTime() - started < 2_000_000_000L)

        assertEquals("done", resultWithin(1_000) { "done" })
        assertNull(resultWithin<String>(1_000) { throw IllegalStateException("host gone") })
    }

    private fun mediaPlaylist(ended: Boolean = true, extraHeader: String? = null): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n")
        extraHeader?.let { append(it).append('\n') }
        for (i in 0..2) append("#EXTINF:6.0,\nseg_00$i.ts\n")
        if (ended) append("#EXT-X-ENDLIST\n")
    }

    private companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100

        // Parameter sets of the 1080p stream the stall was reported on.
        val SPS = hex("67640034acd940780227e5c05a808080a00032e9e009896811e30632c0")
        val PPS = hex("68ebe72c8b")
        val START = byteArrayOf(0, 0, 0, 1)
        val AUD = START + byteArrayOf(0x09, 0xF0.toByte())

        fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        /** A segment of three frames, opening on an IDR frame with or without the SPS and PPS before it. */
        fun segment(withParameterSets: Boolean): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tables())
            val idr = AUD + (if (withParameterSets) START + SPS + START + PPS else ByteArray(0)) +
                START + byteArrayOf(0x65, 0x88.toByte()) + ByteArray(400) { 0x11 }
            val frames = listOf(idr, AUD + START + byteArrayOf(0x41, 0x9A.toByte()) + ByteArray(200) { 0x22 })
            frames.plus(frames[1]).forEachIndexed { index, frame ->
                out.write(videoPes(frame, pts = 900_000L + index * 3_000L, continuityStart = index * 4))
            }
            return out.toByteArray()
        }

        /** A PAT and a PMT listing one stream of [streamType] on [VIDEO_PID]. */
        fun tables(streamType: Int = 0x1B): ByteArray =
            tsPacket(0, section(0x00, byteArrayOf(0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0x00))) +
                tsPacket(
                    PMT_PID,
                    section(
                        0x02,
                        byteArrayOf(
                            0, 1, 0xC1.toByte(), 0, 0,
                            0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
                            streamType.toByte(), 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00
                        )
                    )
                )

        fun section(tableId: Int, body: ByteArray): ByteArray {
            val length = body.size + 4
            val header = byteArrayOf(tableId.toByte(), (0xB0 or (length shr 8)).toByte(), (length and 0xFF).toByte())
            val withoutCrc = header + body
            val crc = Util.crc32(withoutCrc, 0, withoutCrc.size, -1)
            return withoutCrc + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        }

        fun tsPacket(pid: Int, section: ByteArray): ByteArray {
            val packet = ByteArray(188) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = (0x40 or (pid shr 8)).toByte()
            packet[2] = (pid and 0xFF).toByte()
            packet[3] = 0x10
            packet[4] = 0 // pointer field
            section.copyInto(packet, 5)
            return packet
        }

        fun videoPes(es: ByteArray, pts: Long, continuityStart: Int): ByteArray {
            val ptsBytes = byteArrayOf(
                (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(),
                (pts shr 22).toByte(),
                (0x01 or ((pts shr 14) and 0xFE).toInt()).toByte(),
                (pts shr 7).toByte(),
                (0x01 or ((pts shl 1) and 0xFE).toInt()).toByte()
            )
            val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5) + ptsBytes + es
            val out = ByteArrayOutputStream()
            var offset = 0
            var continuity = continuityStart
            while (offset < pes.size) {
                val chunk = minOf(184, pes.size - offset)
                val packet = ByteArray(188)
                packet[0] = 0x47
                packet[1] = ((if (offset == 0) 0x40 else 0) or (VIDEO_PID shr 8)).toByte()
                packet[2] = (VIDEO_PID and 0xFF).toByte()
                var write = 4
                if (chunk < 184) {
                    val adaptationLength = 183 - chunk
                    packet[3] = (0x30 or (continuity and 0x0F)).toByte()
                    packet[4] = adaptationLength.toByte()
                    if (adaptationLength > 0) {
                        packet[5] = 0
                        packet.fill(0xFF.toByte(), 6, 5 + adaptationLength)
                    }
                    write = 5 + adaptationLength
                } else {
                    packet[3] = (0x10 or (continuity and 0x0F)).toByte()
                }
                pes.copyInto(packet, write, offset, offset + chunk)
                out.write(packet)
                offset += chunk
                continuity++
            }
            return out.toByteArray()
        }
    }
}
