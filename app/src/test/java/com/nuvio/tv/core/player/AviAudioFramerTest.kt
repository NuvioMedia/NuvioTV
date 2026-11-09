package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Ac3Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AviAudioFramerTest {

    private data class Sample(val size: Int, val timeUs: Long, val head: Int)

    private val ac3FrameBytes = 1792
    private val ac3FrameUs = 32_000L

    // 448 kbit/s at 48 kHz, bsid 8.
    private fun ac3Frame(): ByteArray = ByteArray(ac3FrameBytes) { 0x55 }.also {
        it[0] = 0x0B
        it[1] = 0x77
        it[4] = 30
        it[5] = (8 shl 3).toByte()
    }

    // Core frame, 512 samples, 1024 bytes.
    private fun dtsFrame(): ByteArray = ByteArray(1024) { 0x55 }.also {
        it[0] = 0x7F
        it[1] = 0xFE.toByte()
        it[2] = 0x80.toByte()
        it[3] = 0x01
        it[4] = 0xFC.toByte()
        it[5] = 0x3C
        it[6] = 0x3F
        it[7] = 0xF0.toByte()
    }

    private fun frames(count: Int, frame: () -> ByteArray): ByteArray =
        (0 until count).fold(ByteArray(0)) { acc, _ -> acc + frame() }

    private fun AviAudioFramer.chunk(bytes: ByteArray, timeUs: Long, out: MutableList<Sample>) {
        var at = 0
        while (at < bytes.size) {
            at += append(bytes.size - at) { target, offset, count ->
                System.arraycopy(bytes, at, target, offset, count)
                count
            }
        }
        endChunk(timeUs) { data, offset, size, frameTimeUs ->
            val head = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            out += Sample(size, frameTimeUs, head)
        }
    }

    @Test
    fun testFrame_hasTheExpectedSize() {
        assertEquals(ac3FrameBytes, Ac3Util.parseAc3SyncframeSize(ac3Frame()))
    }

    @Test
    fun chunksOfThreeFrames_areSplitIntoSingleFrames() {
        val framer = AviAudioFramer(AviAudioFramer.Codec.AC3, 48_000)
        val out = ArrayList<Sample>()
        for (i in 0 until 4) framer.chunk(frames(3, ::ac3Frame), i * 3 * ac3FrameUs, out)

        assertEquals(12, out.size)
        assertTrue(out.all { it.size == ac3FrameBytes && it.head == 0x0B77 })
        assertEquals((0 until 12).map { it * ac3FrameUs }, out.map { it.timeUs })
        assertTrue(framer.engaged)
        assertEquals(3, framer.firstChunkFrames)
        assertEquals(448_000, framer.firstFrameBitrate)
    }

    @Test
    fun framesCutAcrossChunks_comeOutWholeWithSteadyTimes() {
        val framer = AviAudioFramer(AviAudioFramer.Codec.AC3, 48_000)
        val stream = frames(20, ::ac3Frame)
        val out = ArrayList<Sample>()
        var at = 0
        while (at < stream.size) {
            val end = minOf(stream.size, at + 2500)
            framer.chunk(stream.copyOfRange(at, end), at * ac3FrameUs / ac3FrameBytes, out)
            at = end
        }

        assertEquals(20, out.size)
        assertTrue(out.all { it.size == ac3FrameBytes && it.head == 0x0B77 })
        assertEquals((0 until 20).map { it * ac3FrameUs }, out.map { it.timeUs })
        assertEquals(0L, framer.bytesDropped)
    }

    @Test
    fun chunkTimesWanderingUnderOneSecond_areIgnored() {
        val framer = AviAudioFramer(AviAudioFramer.Codec.AC3, 48_000)
        val out = ArrayList<Sample>()
        for (i in 0 until 6) {
            val wander = if (i % 2 == 1) 200_000L else -150_000L
            framer.chunk(frames(3, ::ac3Frame), maxOf(0L, i * 3 * ac3FrameUs + wander), out)
        }

        assertEquals((0 until 18).map { it * ac3FrameUs }, out.map { it.timeUs })
        assertEquals(0, framer.reanchors)
    }

    @Test
    fun jumpOverOneSecond_reanchorsToTheChunkTime() {
        val framer = AviAudioFramer(AviAudioFramer.Codec.AC3, 48_000)
        val out = ArrayList<Sample>()
        for (i in 0 until 4) framer.chunk(frames(3, ::ac3Frame), i * 3 * ac3FrameUs, out)
        val jumpedUs = 4 * 3 * ac3FrameUs + 5_000_000L
        framer.chunk(frames(3, ::ac3Frame), jumpedUs, out)

        val expectedUs = listOf(jumpedUs, jumpedUs + ac3FrameUs, jumpedUs + 2 * ac3FrameUs)
        assertEquals(expectedUs, out.takeLast(3).map { it.timeUs })
        assertEquals(1, framer.reanchors)
        assertEquals(5_000_000L, framer.lastReanchorDriftUs)
    }

    @Test
    fun seek_dropsTheHeldPartialFrameAndFollowsTheNewChunk() {
        val framer = AviAudioFramer(AviAudioFramer.Codec.AC3, 48_000)
        val out = ArrayList<Sample>()
        framer.chunk(frames(2, ::ac3Frame).copyOf(ac3FrameBytes + ac3FrameBytes / 2), 0L, out)
        assertEquals(1, out.size)

        framer.reset()
        out.clear()
        val tail = ac3Frame().copyOfRange(ac3FrameBytes - 1000, ac3FrameBytes)
        framer.chunk(tail + frames(3, ::ac3Frame), 10_000_000L, out)

        val firstUs = 10_000_000L + 1000 * ac3FrameUs / ac3FrameBytes
        assertEquals(listOf(firstUs, firstUs + ac3FrameUs, firstUs + 2 * ac3FrameUs), out.map { it.timeUs })
        assertTrue(out.all { it.size == ac3FrameBytes && it.head == 0x0B77 })
        assertEquals(1000L, framer.bytesDropped)
    }

    @Test
    fun streamWithoutSyncframes_isPassedOnChunkByChunk() {
        val framer = AviAudioFramer(AviAudioFramer.Codec.AC3, 48_000)
        val out = ArrayList<Sample>()
        for (i in 0 until 5) framer.chunk(ByteArray(20_000) { 0x55 }, i * 100_000L, out)

        assertTrue(framer.passingThrough)
        assertFalse(framer.engaged)
        assertEquals(listOf(80_000 to 0L, 20_000 to 400_000L), out.map { it.size to it.timeUs })
    }

    @Test
    fun dtsCoreFrames_areSplitAndTimedFromTheirSampleCount() {
        val framer = AviAudioFramer(AviAudioFramer.Codec.DTS, 48_000)
        val out = ArrayList<Sample>()
        framer.chunk(frames(3, ::dtsFrame), 0L, out)
        framer.chunk(frames(3, ::dtsFrame), 32_000L, out)

        assertEquals(6, out.size)
        assertTrue(out.all { it.size == 1024 && it.head == 0x7FFE })
        assertEquals((0 until 6).map { it * 512 * 1_000_000L / 48_000 }, out.map { it.timeUs })
    }

    @Test
    fun onlyAc3AndDtsTracks_areSplit() {
        assertEquals(AviAudioFramer.Codec.AC3, AviAudioFramer.codecFor(MimeTypes.AUDIO_AC3, 48_000))
        assertEquals(AviAudioFramer.Codec.DTS, AviAudioFramer.codecFor(MimeTypes.AUDIO_DTS, 48_000))
        assertNull(AviAudioFramer.codecFor(MimeTypes.AUDIO_DTS, -1))
        assertNull(AviAudioFramer.codecFor(MimeTypes.AUDIO_MPEG, 48_000))
        assertNull(AviAudioFramer.codecFor(MimeTypes.AUDIO_RAW, 48_000))
        assertNull(AviAudioFramer.codecFor(MimeTypes.AUDIO_AAC, 48_000))
        assertNull(AviAudioFramer.codecFor(null, 48_000))
    }
}
