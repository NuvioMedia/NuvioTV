package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

@UnstableApi
class NativeOptimizedVideoTrackOutputTest {

    private val inactiveConfig = DolbyVisionConversionConfig(active = false)

    private fun pba(s: String) = ParsableByteArray(s.toByteArray())

    private fun videoFormat(codecs: String?, mime: String = MimeTypes.VIDEO_H264): Format =
        Format.Builder()
            .setSampleMimeType(mime)
            .setCodecs(codecs)
            .build()

    @Test
    fun buffersBytesBeforeFormatAndFlushesThemInOrder() {
        val fake = FakeTrackOutput()
        val out = NativeOptimizedVideoTrackOutput(fake, inactiveConfig, NalFormat.ANNEX_B)

        // TsExtractor's H265Reader pushes sample bytes before format().
        out.sampleData(pba("IDS"), 3, TrackOutput.SAMPLE_DATA_PART_MAIN)
        assertEquals(0, fake.data.size())

        out.format(videoFormat("avc1.64001f"))
        // Untouched track: buffered bytes are released right after format().
        assertEquals("IDS", fake.data.toString("UTF-8"))
        assertEquals(1, fake.formats.size)

        out.sampleData(pba("PPS"), 3, TrackOutput.SAMPLE_DATA_PART_MAIN)
        assertEquals("IDSPPS", fake.data.toString("UTF-8"))
    }

    @Test
    fun preFormatBufferIsCappedAndPassesThroughBeyondLimit() {
        val fake = FakeTrackOutput()
        val out = NativeOptimizedVideoTrackOutput(fake, inactiveConfig, NalFormat.ANNEX_B)

        val first = ByteArray(6 * 1024 * 1024) { 0x11 }
        val second = ByteArray(4 * 1024 * 1024) { 0x22 }
        out.sampleData(ParsableByteArray(first), first.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        assertEquals(0, fake.data.size()) // still under the 8 MB cap: buffered

        out.sampleData(ParsableByteArray(second), second.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        // Cap exceeded: pending bytes flushed, new bytes passed through directly.
        assertEquals(first.size + second.size, fake.data.size())

        out.format(videoFormat("avc1.64001f"))
        assertEquals(first.size + second.size, fake.data.size()) // nothing extra flushed
    }

    @Test
    fun resetDropsBufferedBytesOnSeek() {
        val fake = FakeTrackOutput()
        val out = NativeOptimizedVideoTrackOutput(fake, inactiveConfig, NalFormat.ANNEX_B)

        out.sampleData(pba("STALE"), 5, TrackOutput.SAMPLE_DATA_PART_MAIN)
        out.reset()
        out.format(videoFormat("avc1.64001f"))
        assertEquals(0, fake.data.size())
    }

    @Test
    fun nonHevcTrackBypassesProcessingEvenWithStripFlagsEnabled() {
        val fake = FakeTrackOutput()
        val out = NativeOptimizedVideoTrackOutput(
            fake, inactiveConfig, NalFormat.ANNEX_B,
            stripDvRpu = true, stripHdr10PlusSei = true
        )
        out.format(videoFormat("avc1.64001f", MimeTypes.VIDEO_H264))

        out.sampleData(pba("slice"), 5, TrackOutput.SAMPLE_DATA_PART_MAIN)
        // shouldProcess == false: bytes reach the delegate immediately, no buffering.
        assertEquals("slice", fake.data.toString("UTF-8"))
    }

    @Test
    fun avcBasedDolbyVisionProfile9IsNotTreatedAsHevc() {
        val fake = FakeTrackOutput()
        val out = NativeOptimizedVideoTrackOutput(
            fake, inactiveConfig, NalFormat.ANNEX_B,
            stripDvRpu = true, stripHdr10PlusSei = true
        )
        // dvav/dva1 = AVC-based DV (profile 9): VIDEO_DOLBY_VISION mime but H.264 payload.
        out.format(videoFormat("dvav.09.0", MimeTypes.VIDEO_DOLBY_VISION))

        out.sampleData(pba("slice"), 5, TrackOutput.SAMPLE_DATA_PART_MAIN)
        assertEquals("slice", fake.data.toString("UTF-8"))
    }

    @Test
    fun hevcTrackWithStripFlagBuffersUntilSampleMetadata() {
        val fake = FakeTrackOutput()
        val out = NativeOptimizedVideoTrackOutput(
            fake, inactiveConfig, NalFormat.ANNEX_B,
            stripDvRpu = false, stripHdr10PlusSei = true
        )
        out.format(videoFormat("hvc1.1.6.L93.B0", MimeTypes.VIDEO_H265))

        out.sampleData(pba("abc"), 3, TrackOutput.SAMPLE_DATA_PART_MAIN)
        // Processing path engaged: nothing reaches the delegate before metadata.
        assertEquals(0, fake.data.size())

        out.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, 3, 0, null)
        // DoviBridge is unavailable on the JVM, so the sample passes through unchanged.
        assertEquals("abc", fake.data.toString("UTF-8"))
        assertEquals(1, fake.metadata.size)
        assertEquals(3 to 0, fake.metadata[0])
    }

    @Test
    fun metadataBeforeFormatFlushesPendingBytes() {
        val fake = FakeTrackOutput()
        val out = NativeOptimizedVideoTrackOutput(fake, inactiveConfig, NalFormat.ANNEX_B)

        out.sampleData(pba("early"), 5, TrackOutput.SAMPLE_DATA_PART_MAIN)
        out.sampleMetadata(0, 0, 5, 0, null)
        assertEquals("early", fake.data.toString("UTF-8"))
        assertEquals(1, fake.metadata.size)
    }

    private class FakeTrackOutput : TrackOutput {
        val formats = mutableListOf<Format>()
        val data = ByteArrayOutputStream()
        val metadata = mutableListOf<Pair<Int, Int>>() // (size, offset)

        override fun format(format: Format) {
            formats += format
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n == C.RESULT_END_OF_INPUT) break
                read += n
            }
            if (read > 0) data.write(buf, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buf = ByteArray(length)
            data.readBytes(buf, 0, length)
            this.data.write(buf, 0, length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            metadata += size to offset
        }

        override fun durationUs(durationUs: Long) {}
    }
}
