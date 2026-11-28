package com.nuvio.tv.core.player.avi

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackedBFrameUnpackerTest {

    private class Out(val data: ByteArray, val timeUs: Long, val flags: Int) {
        val tag: Int get() = Mpeg4Vops.tagOf(data)
        val vops: Int get() = Mpeg4Vops.vopCount(data)
    }

    private fun run(unpacker: PackedBFrameUnpacker, chunks: List<ByteArray>, keyAt: Set<Int> = setOf(0)): List<Out> {
        val out = ArrayList<Out>()
        chunks.forEachIndexed { i, chunk ->
            unpacker.onChunk(chunk, chunk.size, i * FRAME_US, i in keyAt) { data, offset, size, timeUs, flags ->
                out += Out(data.copyOfRange(offset, offset + size), timeUs, flags)
            }
        }
        return out
    }

    private fun assertDisplayOrder(samples: List<Out>, slots: List<Int>) {
        val times = samples.map { it.timeUs }.sorted()
        assertEquals(slots.map { it * FRAME_US }, times)
        assertTrue((1 until times.size).all { times[it] > times[it - 1] })
    }

    // Decode order I0 P3 B1 B2 P5 B4 P6, packed: I0 | P3+B1 | B2 | N | P5+B4 | N | P6
    private fun packedChunks(marker: Boolean = true): List<ByteArray> = listOf(
        Mpeg4Vops.headers(marker) + Mpeg4Vops.vop(Mpeg4Vops.I, tag = 10),
        Mpeg4Vops.vop(Mpeg4Vops.P, tag = 13) + Mpeg4Vops.vop(Mpeg4Vops.B, tag = 11),
        Mpeg4Vops.vop(Mpeg4Vops.B, tag = 12),
        Mpeg4Vops.placeholder(),
        Mpeg4Vops.vop(Mpeg4Vops.P, tag = 15) + Mpeg4Vops.vop(Mpeg4Vops.B, tag = 14),
        Mpeg4Vops.placeholder(),
        Mpeg4Vops.vop(Mpeg4Vops.P, tag = 16),
    )

    @Test
    fun splitsPackedChunksAndGivesEveryFrameItsDisplaySlot() {
        val unpacker = PackedBFrameUnpacker()
        val samples = run(unpacker, packedChunks())

        assertTrue(unpacker.isPacked)
        assertEquals(listOf(10, 13, 11, 12, 15, 14, 16), samples.map { it.tag })
        assertEquals(listOf(0, 3, 1, 2, 5, 4, 6).map { it * FRAME_US }, samples.map { it.timeUs })
        assertTrue(samples.all { it.vops == 1 })
        assertEquals(listOf(C.BUFFER_FLAG_KEY_FRAME, 0, 0, 0, 0, 0, 0), samples.map { it.flags })
        assertDisplayOrder(samples, (0..6).toList())
        assertEquals(2, unpacker.splitChunks)
        assertEquals(2, unpacker.placeholdersUsed)
        assertEquals(0, unpacker.missingPlaceholders)
    }

    @Test
    fun keepsTheBytesOfEveryVop() {
        val chunks = packedChunks()
        val samples = run(PackedBFrameUnpacker(), chunks)
        val input = ByteArrayOutputStream().apply { chunks.filter { it.size > 7 }.forEach(::write) }.size()
        assertEquals(input, samples.sumOf { it.data.size })
        assertTrue(samples[0].data.contentEquals(chunks[0]))
        assertTrue(samples[1].data.contentEquals(Mpeg4Vops.vop(Mpeg4Vops.P, tag = 13)))
        assertTrue(samples[2].data.contentEquals(Mpeg4Vops.vop(Mpeg4Vops.B, tag = 11)))
    }

    @Test
    fun findsPackingFromATwoVopChunkWithoutTheMarker() {
        val unpacker = PackedBFrameUnpacker()
        val samples = run(unpacker, packedChunks(marker = false))
        assertTrue(unpacker.isPacked)
        assertEquals(listOf(0, 3, 1, 2, 5, 4, 6).map { it * FRAME_US }, samples.map { it.timeUs })
    }

    @Test
    fun leavesAStreamWithoutPackingUntouched() {
        // Decode order I0 P2 B1 P4 B3, one VOP per chunk, plus a dropped-frame placeholder.
        val chunks = listOf(
            Mpeg4Vops.headers(marker = false) + Mpeg4Vops.vop(Mpeg4Vops.I, tag = 10),
            Mpeg4Vops.vop(Mpeg4Vops.P, tag = 12),
            Mpeg4Vops.vop(Mpeg4Vops.B, tag = 11),
            Mpeg4Vops.placeholder(),
            Mpeg4Vops.vop(Mpeg4Vops.P, tag = 14),
            Mpeg4Vops.vop(Mpeg4Vops.B, tag = 13),
        )
        val unpacker = PackedBFrameUnpacker()
        val samples = run(unpacker, chunks)
        assertFalse(unpacker.isPacked)
        assertEquals(chunks.size, samples.size)
        chunks.forEachIndexed { i, chunk -> assertTrue(samples[i].data.contentEquals(chunk)) }
        assertEquals(chunks.indices.map { it * FRAME_US }, samples.map { it.timeUs })
    }

    @Test
    fun aSmallCodedBFrameIsNotAPlaceholder() {
        val tiny = Mpeg4Vops.vop(Mpeg4Vops.B, tag = 12, payload = 1)
        assertTrue(tiny.size <= 9)
        val chunks = packedChunks().toMutableList().also { it[2] = tiny }
        val samples = run(PackedBFrameUnpacker(), chunks)
        assertEquals(listOf(10, 13, 11, 12, 15, 14, 16), samples.map { it.tag })
    }

    @Test
    fun dropsBFramesThatNeedAReferenceFromBeforeTheSeek() {
        val unpacker = PackedBFrameUnpacker()
        run(unpacker, packedChunks())
        unpacker.reset()
        // Open GOP after the seek: I9+B7 | B8 | N | P12+B10 | N | ...
        val chunks = listOf(
            Mpeg4Vops.vop(Mpeg4Vops.I, tag = 19) + Mpeg4Vops.vop(Mpeg4Vops.B, tag = 17),
            Mpeg4Vops.vop(Mpeg4Vops.B, tag = 18),
            Mpeg4Vops.placeholder(),
            Mpeg4Vops.vop(Mpeg4Vops.P, tag = 22) + Mpeg4Vops.vop(Mpeg4Vops.B, tag = 20),
            Mpeg4Vops.vop(Mpeg4Vops.B, tag = 21),
            Mpeg4Vops.placeholder(),
        )
        val samples = run(unpacker, chunks)
        assertEquals(listOf(19, 22, 20, 21), samples.map { it.tag })
        assertEquals(listOf(2, 5, 3, 4).map { it * FRAME_US }, samples.map { it.timeUs })
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, samples[0].flags)
        assertEquals(2, unpacker.leadingBFramesDropped)
    }

    @Test
    fun showsAReferenceFrameWhosePlaceholderIsMissingBeforeTheNextOne() {
        val chunks = listOf(
            Mpeg4Vops.headers(marker = true) + Mpeg4Vops.vop(Mpeg4Vops.I, tag = 10),
            Mpeg4Vops.vop(Mpeg4Vops.P, tag = 12) + Mpeg4Vops.vop(Mpeg4Vops.B, tag = 11),
            Mpeg4Vops.vop(Mpeg4Vops.P, tag = 14) + Mpeg4Vops.vop(Mpeg4Vops.B, tag = 13),
            Mpeg4Vops.placeholder(),
        )
        val unpacker = PackedBFrameUnpacker()
        val samples = run(unpacker, chunks)
        assertEquals(listOf(10, 12, 11, 14, 13), samples.map { it.tag })
        val byTag = samples.associate { it.tag to it.timeUs }
        assertTrue(byTag.getValue(11) < byTag.getValue(12) && byTag.getValue(12) < byTag.getValue(13))
        assertEquals(3 * FRAME_US, byTag.getValue(14))
        assertEquals(1, unpacker.missingPlaceholders)
    }

    @Test
    fun extractorUnpacksAPackedAviAndReadsItAgainAfterASeek() {
        val file = TestAvi.build(openDml = true, videoChunk = Mpeg4Vops::packedGopChunk)
        val output = RecordingOutput()
        val extractor = AviExtractor(AviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED)
            .apply { init(output) }
        runExtractor(extractor, ArraySource(file))

        val video = output.track(C.TRACK_TYPE_VIDEO)
        assertEquals(MimeTypes.VIDEO_MP4V, video.format?.sampleMimeType)
        val gops = TestAvi.FRAMES / TestAvi.KEY_INTERVAL
        assertEquals(TestAvi.FRAMES, video.samples.size)
        val expectedSlots = (0 until gops).flatMap { g -> listOf(0, 3, 1, 2, 4).map { g * 5 + it } }
        assertEquals(expectedSlots.map { it * TestAvi.FRAME_US }, video.samples.map { it.timeUs })
        val sorted = video.samples.map { it.timeUs }.sorted()
        assertTrue((1 until sorted.size).all { sorted[it] > sorted[it - 1] })
        assertEquals(TestAvi.FRAMES, output.track(C.TRACK_TYPE_AUDIO).samples.size)

        val point = output.seekMap!!.getSeekPoints(32 * TestAvi.FRAME_US).first
        output.tracks.values.forEach { it.samples.clear() }
        extractor.seek(point.position, point.timeUs)
        runExtractor(extractor, ArraySource(file), start = point.position)
        val after = output.track(C.TRACK_TYPE_VIDEO).samples
        assertEquals(listOf(30, 33, 31, 32, 34).map { it * TestAvi.FRAME_US }, after.take(5).map { it.timeUs })
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, after.first().flags)
    }

    private companion object {
        const val FRAME_US = 41_708L
    }
}

/** Minimal MPEG-4 Part 2 headers and VOPs, with a tag byte after each VOP header to tell them apart. */
internal object Mpeg4Vops {
    const val I = 0
    const val P = 1
    const val B = 2
    private const val RESOLUTION = 24_000
    private const val INCREMENT_BITS = 15

    fun headers(marker: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 1, 0xB0.toByte(), 0xF5.toByte()))
        out.write(byteArrayOf(0, 0, 1, 0x00))
        out.write(byteArrayOf(0, 0, 1, 0x20))
        out.write(Bits().apply {
            bit(0); bits(1, 8); bit(1); bits(5, 4); bits(1, 3); bits(1, 4)
            bit(1); bits(1, 2); bit(0); bit(0)
            bits(0, 2); bit(1); bits(RESOLUTION, 16); bit(1); bit(0)
            repeat(40) { bit(it % 2) }
        }.toByteArray())
        if (marker) {
            out.write(byteArrayOf(0, 0, 1, 0xB2.toByte()))
            out.write("DivX503b1393p".toByteArray(Charsets.ISO_8859_1))
        }
        out.write(byteArrayOf(0, 0, 1, 0xB2.toByte()))
        out.write("XviD0069".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    fun vop(type: Int, tag: Int, payload: Int = 40): ByteArray = vopHeader(type, coded = true) +
        byteArrayOf(tag.toByte()) + ByteArray(payload) { 0x55 }

    /** The 7-byte not-coded P-VOP packed streams use as a placeholder. */
    fun placeholder(): ByteArray = vopHeader(P, coded = false)

    private fun vopHeader(type: Int, coded: Boolean): ByteArray = byteArrayOf(0, 0, 1, 0xB6.toByte()) +
        Bits().apply {
            bits(type, 2); bit(0); bit(1); bits(1001, INCREMENT_BITS); bit(1); bit(if (coded) 1 else 0)
        }.toByteArray()

    /** Five chunks per GOP of [TestAvi]: I | P3+B1 | B2 | N | P4 */
    fun packedGopChunk(frame: Int): ByteArray {
        val tag = 10 + frame % 200
        return when (frame % TestAvi.KEY_INTERVAL) {
            0 -> headers(marker = true) + vop(I, tag)
            1 -> vop(P, tag) + vop(B, tag)
            2 -> vop(B, tag)
            3 -> placeholder()
            else -> vop(P, tag)
        }
    }

    fun vopCount(data: ByteArray): Int = (0 until data.size - 3).count { isVop(data, it) }

    fun tagOf(data: ByteArray): Int {
        val at = (data.size - 4 downTo 0).first { isVop(data, it) }
        return data[at + 7].toInt() and 0xFF
    }

    private fun isVop(data: ByteArray, at: Int) =
        data[at] == 0.toByte() && data[at + 1] == 0.toByte() && data[at + 2] == 1.toByte() && data[at + 3] == 0xB6.toByte()

    private class Bits {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun bit(value: Int) {
            current = (current shl 1) or (value and 1)
            if (++count == 8) {
                out.write(current)
                current = 0
                count = 0
            }
        }

        fun bits(value: Int, width: Int) {
            for (shift in width - 1 downTo 0) bit(value shr shift)
        }

        // Stuffing as in a real VOP: a zero then ones up to the byte boundary.
        fun toByteArray(): ByteArray {
            if (count > 0) {
                bit(0)
                while (count != 0) bit(1)
            }
            return out.toByteArray()
        }
    }
}
