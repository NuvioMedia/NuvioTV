package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Samples hold whole display sets, the segments as the disc wrote them: what PgsParser reads from Matroska. */
class BdavPgsReaderTest {

    private class Sample(val timeUs: Long, val data: ByteArray, val flags: Int)

    private class CapturingOutput : ExtractorOutput, TrackOutput {
        var format: Format? = null
        var trackType = C.TRACK_TYPE_UNKNOWN
        val samples = mutableListOf<Sample>()
        private var pending = byteArrayOf()
        override fun track(id: Int, type: Int): TrackOutput = also { trackType = type }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            throw UnsupportedOperationException()
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pending += bytes
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            assertEquals("sample data written whole", size, pending.size)
            samples += Sample(timeUs, pending, flags)
            pending = byteArrayOf()
        }
    }

    private fun segment(type: Int, vararg payload: Int) =
        byteArrayOf(type.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + ByteArray(payload.size) { payload[it].toByte() }

    // A display set as a disc writes it: composition, palette, a 2x1 image, end.
    private val composition = segment(
        0x16,
        0x07, 0x80, 0x04, 0x38, // 1920x1080
        0x10, 0x00, 0x01, 0x80, 0x00, 0x00, 0x01, // Frame rate, composition 1, epoch start, palette 0, one object.
        0x00, 0x00, 0x00, 0x00, // Object 0 in window 0, not cropped.
        0x00, 0x64, 0x03, 0xE8 // At (100, 1000).
    )
    private val palette = segment(0x14, 0x00, 0x00, 0x01, 0xEB, 0x80, 0x80, 0xFF) // Entry 1: opaque white.
    private val image = segment(
        0x15,
        0x00, 0x00, 0x00, 0xC0, // Object 0, version 0, first and last fragment.
        0x00, 0x00, 0x08, 0x00, 0x02, 0x00, 0x01, // 8 bytes of data: 2x1, then its run-length lines.
        0x01, 0x01, 0x00, 0x00 // Two pixels of colour 1, end of line.
    )
    private val end = segment(0x80)

    private fun reader(output: CapturingOutput, language: String? = "deu") = BdavPgsReader(language, 0).also {
        it.createTracks(output, TsPayloadReader.TrackIdGenerator(0, 1))
    }

    private fun BdavPgsReader.pes(timeUs: Long, vararg payload: ByteArray, end: Boolean = false) {
        packetStarted(timeUs, 0)
        payload.forEach { consume(ParsableByteArray(it)) }
        packetFinished(end)
    }

    @Test fun `the track is a PGS subtitle track`() {
        val output = CapturingOutput()
        reader(output)
        assertEquals(C.TRACK_TYPE_TEXT, output.trackType)
        assertEquals(MimeTypes.APPLICATION_PGS, output.format!!.sampleMimeType)
        assertEquals("de", output.format!!.language) // Media3 normalizes ISO 639-2 codes.
    }

    @Test fun `a display set over several packets is one sample at its composition time`() {
        val output = CapturingOutput()
        val reader = reader(output)
        reader.pes(2_000_000, composition)
        reader.pes(2_000_000, palette)
        reader.pes(2_010_000, image)
        reader.pes(2_020_000, end)
        val sample = output.samples.single()
        assertEquals(2_000_000, sample.timeUs)
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, sample.flags)
        assertArrayEquals(composition + palette + image + end, sample.data)
    }

    @Test fun `a segment cut between packets is joined`() {
        val output = CapturingOutput()
        val reader = reader(output)
        val set = composition + palette + image + end
        reader.pes(5_000_000, set.copyOfRange(0, 30))
        reader.pes(C.TIME_UNSET, set.copyOfRange(30, set.size))
        assertArrayEquals(set, output.samples.single().data)
        assertEquals(5_000_000, output.samples.single().timeUs)
    }

    @Test fun `an empty display set is a sample of its own, which clears the subtitle`() {
        val output = CapturingOutput()
        val reader = reader(output)
        reader.pes(1_000_000, composition, palette, image, end)
        val clear = segment(0x16, 0x07, 0x80, 0x04, 0x38, 0x10, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00) + end
        reader.pes(3_000_000, clear)
        assertEquals(listOf(1_000_000L, 3_000_000L), output.samples.map { it.timeUs })
        assertArrayEquals(clear, output.samples[1].data)
    }

    @Test fun `segments before a composition, or of a set left without its end, are dropped`() {
        val output = CapturingOutput()
        val reader = reader(output)
        reader.pes(500_000, palette, image, end) // The tail of a set missed before a seek.
        reader.pes(1_000_000, composition, palette) // Cut short by the next composition.
        reader.pes(2_000_000, composition, palette, image, end)
        assertEquals(listOf(2_000_000L), output.samples.map { it.timeUs })
        reader.seek()
        reader.pes(3_000_000, image, end)
        assertEquals(1, output.samples.size)
    }
}
