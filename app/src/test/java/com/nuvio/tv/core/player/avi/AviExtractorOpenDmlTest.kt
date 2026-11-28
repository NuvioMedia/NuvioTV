package com.nuvio.tv.core.player.avi

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AviExtractorOpenDmlTest {

    private fun extractor() =
        AviExtractor(AviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED)

    @Test
    fun readsEveryRiffSegmentOfAnOpenDmlFile() {
        val file = TestAvi.build(openDml = true)
        val output = RecordingOutput()
        val extractor = extractor().apply { init(output) }
        runExtractor(extractor, ArraySource(file))

        val video = output.track(C.TRACK_TYPE_VIDEO)
        val audio = output.track(C.TRACK_TYPE_AUDIO)
        assertEquals(MimeTypes.VIDEO_MP4V, video.format?.sampleMimeType)
        assertEquals((0 until TestAvi.FRAMES).toList(), video.samples.map { it.firstByte })
        assertEquals((0 until TestAvi.FRAMES).map { it * TestAvi.FRAME_US }, video.samples.map { it.timeUs })
        assertEquals(
            (0 until TestAvi.FRAMES).map { if (it % TestAvi.KEY_INTERVAL == 0) C.BUFFER_FLAG_KEY_FRAME else 0 },
            video.samples.map { it.flags },
        )
        assertEquals(TestAvi.FRAMES, audio.samples.size)
        assertEquals((0 until TestAvi.FRAMES).map { it * TestAvi.FRAME_US }, audio.samples.map { it.timeUs })

        val seekMap = output.seekMap
        assertNotNull(seekMap)
        seekMap!!
        assertTrue(seekMap.isSeekable)
        assertEquals(TestAvi.FRAMES * TestAvi.FRAME_US, seekMap.durationUs)
    }

    @Test
    fun seeksIntoALaterRiffSegment() {
        val file = TestAvi.build(openDml = true)
        val output = RecordingOutput()
        val extractor = extractor().apply { init(output) }
        runExtractor(extractor, ArraySource(file)) { output.seekMap != null }
        assertNotNull(output.seekMap)

        val targetUs = 32 * TestAvi.FRAME_US
        val point = output.seekMap!!.getSeekPoints(targetUs).first
        assertEquals(30 * TestAvi.FRAME_US, point.timeUs)
        assertTrue(point.position > TestAvi.lastSegmentStart(file))

        output.tracks.values.forEach { it.samples.clear() }
        extractor.seek(point.position, point.timeUs)
        runExtractor(extractor, ArraySource(file), start = point.position)
        val video = output.track(C.TRACK_TYPE_VIDEO).samples
        assertEquals(30, video.first().firstByte)
        assertEquals(30 * TestAvi.FRAME_US, video.first().timeUs)
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, video.first().flags)
        assertEquals(TestAvi.FRAMES - 30, video.size)
    }

    @Test
    fun stockReaderFailsOnJunkAndStopsAfterTheFirstSegment() {
        val stock = { androidx.media3.extractor.avi.AviExtractor(
            androidx.media3.extractor.avi.AviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED
        ) }
        val failed = runCatching {
            runExtractor(stock().apply { init(RecordingOutput()) }, ArraySource(TestAvi.build(openDml = true)))
        }.exceptionOrNull()
        assertTrue(failed is androidx.media3.common.ParserException)

        val output = RecordingOutput()
        runExtractor(stock().apply { init(output) }, ArraySource(TestAvi.build(openDml = true, junk = false)))
        assertEquals(10, output.track(C.TRACK_TYPE_VIDEO).samples.size)
    }

    @Test
    fun readsChunksGroupedInRecLists() {
        for (openDml in listOf(true, false)) {
            val output = RecordingOutput()
            val extractor = extractor().apply { init(output) }
            runExtractor(extractor, ArraySource(TestAvi.build(openDml = openDml, recLists = true)))
            assertEquals((0 until TestAvi.FRAMES).toList(), output.track(C.TRACK_TYPE_VIDEO).samples.map { it.firstByte })
            assertEquals(TestAvi.FRAMES, output.track(C.TRACK_TYPE_AUDIO).samples.size)
        }
    }

    @Test
    fun skipsJunkBeforeTheHeaderListOfAPlainFile() {
        val file = TestAvi.build(openDml = false)
        val output = RecordingOutput()
        val extractor = extractor().apply { init(output) }
        runExtractor(extractor, ArraySource(file))
        assertEquals((0 until TestAvi.FRAMES).toList(), output.track(C.TRACK_TYPE_VIDEO).samples.map { it.firstByte })
        assertEquals(TestAvi.FRAMES * TestAvi.FRAME_US, output.seekMap!!.durationUs)
    }

    @Test
    fun handsTheWholeIndexToTheListenerInFileOrder() {
        val file = TestAvi.build(openDml = true)
        var positions = LongArray(0)
        var ids = IntArray(0)
        val extractor = extractor().apply {
            setIndexListener { chunkIds, _, chunkPositions, _, count ->
                positions = chunkPositions.copyOf(count)
                ids = chunkIds.copyOf(count)
            }
            init(RecordingOutput())
        }
        runExtractor(extractor, ArraySource(file))
        assertEquals(2 * TestAvi.FRAMES, positions.size)
        assertTrue((1 until positions.size).all { positions[it] > positions[it - 1] })
        positions.forEachIndexed { i, position ->
            val id = String(file, position.toInt(), 4, Charsets.ISO_8859_1)
            assertEquals(if ((ids[i] and 0xFF0000) == ('d'.code shl 16)) "00dc" else "01wb", id)
        }
    }
}

/**
 * A small AVI: 45 video frames at 25 fps (key frame every 5) and one 40 ms PCM chunk per frame. As OpenDML it has a
 * JUNK chunk before the header list, a first RIFF with 10 frames and an idx1 covering only those, and two RIFF AVIX
 * segments of 20 and 15 frames, all indexed by 'indx' super indexes and one 'ix##' per stream and segment.
 */
internal object TestAvi {
    const val FRAMES = 45
    const val FRAME_US = 40_000L
    const val KEY_INTERVAL = 5
    private val SEGMENTS = intArrayOf(10, 20, 15)
    private const val AUDIO_CHUNK = 7_680
    private const val VIDEO_CHUNK = 100

    private class Entry(val stream: Int, val dataPosition: Long, val size: Int, val key: Boolean)

    fun lastSegmentStart(file: ByteArray): Long {
        var at = 0L
        var last = 0L
        while (at + 8 <= file.size) {
            last = at
            at += 8 + u32(file, at.toInt())
        }
        return last
    }

    fun build(
        openDml: Boolean,
        junk: Boolean = true,
        recLists: Boolean = false,
        videoChunk: (Int) -> ByteArray = { frame -> ByteArray(VIDEO_CHUNK).also { it[0] = frame.toByte() } },
    ): ByteArray {
        val segments = if (openDml) SEGMENTS else intArrayOf(FRAMES)
        // The header has a fixed size, so build it once to learn where the segments start.
        var ixPositions = Array(2) { LongArray(if (openDml) segments.size else 0) }
        repeat(2) {
            val header = header(openDml, ixPositions)
            val out = Bytes()
            out.tag("RIFF"); out.u32(0); out.tag("AVI ")
            if (junk) {
                out.tag("JUNK"); out.u32(0)
            }
            out.write(header)
            val entries = ArrayList<Entry>()
            val newIx = Array(2) { LongArray(segments.size) }
            var frame = 0
            segments.forEachIndexed { s, frames ->
                val riffStart = if (s == 0) 0 else out.size().toLong()
                if (s > 0) {
                    out.tag("RIFF"); out.u32(0); out.tag("AVIX")
                }
                val moviStart = out.size()
                out.tag("LIST"); out.u32(0); out.tag("movi")
                val segmentEntries = ArrayList<Entry>()
                repeat(frames) {
                    val recStart = out.size()
                    if (recLists) {
                        out.tag("LIST"); out.u32(0); out.tag("rec ")
                    }
                    val video = videoChunk(frame)
                    out.tag("00dc"); out.u32(video.size.toLong())
                    segmentEntries += Entry(0, out.size().toLong(), video.size, frame % KEY_INTERVAL == 0)
                    out.write(video)
                    if (video.size % 2 == 1) out.write(0)
                    out.tag("01wb"); out.u32(AUDIO_CHUNK.toLong())
                    segmentEntries += Entry(1, out.size().toLong(), AUDIO_CHUNK, true)
                    out.write(ByteArray(AUDIO_CHUNK))
                    if (recLists) out.patchU32(recStart + 4, (out.size() - recStart - 8).toLong())
                    frame++
                }
                if (openDml) {
                    for (stream in 0..1) {
                        newIx[stream][s] = out.size().toLong()
                        standardIndex(out, stream, moviStart.toLong(), segmentEntries.filter { it.stream == stream })
                    }
                }
                out.patchU32(moviStart + 4, (out.size() - moviStart - 8).toLong())
                entries += segmentEntries
                if (s == 0) {
                    out.tag("idx1")
                    val indexed = segmentEntries
                    out.u32(indexed.size * 16L)
                    indexed.forEach { e ->
                        out.tag(if (e.stream == 0) "00dc" else "01wb")
                        out.u32(if (e.key) 0x10 else 0)
                        out.u32(e.dataPosition - 8 - (moviStart + 8))
                        out.u32(e.size.toLong())
                    }
                }
                out.patchU32(riffStart.toInt() + 4, (out.size() - riffStart - 8))
            }
            if (openDml && it == 0) {
                ixPositions = newIx
            } else {
                return out.toByteArray()
            }
        }
        error("unreachable")
    }

    private fun header(openDml: Boolean, ixPositions: Array<LongArray>): ByteArray {
        val hdrl = Bytes()
        hdrl.chunk("avih") {
            u32(FRAME_US); u32(0); u32(0); u32(0x10); u32(if (openDml) SEGMENTS[0].toLong() else FRAMES.toLong())
            u32(0); u32(2); u32(0); u32(64); u32(48); repeat(4) { u32(0) }
        }
        hdrl.list("strl") {
            chunk("strh") {
                tag("vids"); tag("XVID"); u32(0); u32(0); u32(0); u32(1); u32(25); u32(0); u32(FRAMES.toLong())
                u32(0); u32(0xFFFFFFFF); u32(0); u32(0); u32(0)
            }
            chunk("strf") {
                u32(40); u32(64); u32(48); u16(1); u16(24); tag("XVID"); repeat(5) { u32(0) }
            }
            if (openDml) superIndex("00dc", ixPositions[0])
        }
        hdrl.list("strl") {
            chunk("strh") {
                tag("auds"); u32(0); u32(0); u32(0); u32(0); u32(4); u32(192_000); u32(0)
                u32(FRAMES * AUDIO_CHUNK / 4L); u32(0); u32(0xFFFFFFFF); u32(4); u32(0); u32(0)
            }
            chunk("strf") { u16(1); u16(2); u32(48_000); u32(192_000); u16(4); u16(16); u16(0) }
            if (openDml) superIndex("01wb", ixPositions[1])
        }
        if (openDml) {
            hdrl.list("odml") { chunk("dmlh") { u32(FRAMES.toLong()); write(ByteArray(244)) } }
        }
        return Bytes().apply { list("hdrl") { write(hdrl.toByteArray()) } }.toByteArray()
    }

    private fun Bytes.superIndex(chunkId: String, positions: LongArray) = chunk("indx") {
        u16(4); write(0); write(0); u32(positions.size.toLong()); tag(chunkId); repeat(3) { u32(0) }
        positions.forEach { u64(it); u32(0); u32(0) }
    }

    private fun standardIndex(out: Bytes, stream: Int, base: Long, entries: List<Entry>) = out.chunk("ix0$stream") {
        u16(2); write(0); write(1); u32(entries.size.toLong()); tag(if (stream == 0) "00dc" else "01wb")
        u64(base); u32(0)
        entries.forEach { e ->
            u32(e.dataPosition - base)
            u32(e.size.toLong() or if (e.key) 0L else 0x80000000L)
        }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at + 4].toLong() and 0xFF) or ((b[at + 5].toLong() and 0xFF) shl 8) or
            ((b[at + 6].toLong() and 0xFF) shl 16) or ((b[at + 7].toLong() and 0xFF) shl 24)

    private class Bytes : ByteArrayOutputStream() {
        fun tag(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
        fun u16(v: Int) {
            write(v and 0xFF); write((v shr 8) and 0xFF)
        }
        fun u32(v: Long) {
            for (shift in 0 until 32 step 8) write(((v shr shift) and 0xFF).toInt())
        }
        fun u64(v: Long) {
            u32(v and 0xFFFFFFFFL); u32(v ushr 32)
        }
        fun patchU32(at: Int, v: Long) {
            for (i in 0 until 4) buf[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
        fun chunk(id: String, body: Bytes.() -> Unit) {
            val inner = Bytes().apply(body)
            tag(id); u32(inner.size().toLong()); write(inner.toByteArray())
            if (inner.size() % 2 == 1) write(0)
        }
        fun list(type: String, body: Bytes.() -> Unit) {
            val inner = Bytes().apply(body)
            tag("LIST"); u32(inner.size() + 4L); tag(type); write(inner.toByteArray())
        }
    }
}
