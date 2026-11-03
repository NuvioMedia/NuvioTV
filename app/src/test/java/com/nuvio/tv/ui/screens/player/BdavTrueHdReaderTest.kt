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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BdavTrueHdReaderTest {

    private class Sample(val timeUs: Long, val size: Int, val flags: Int)

    private class CapturingOutput : ExtractorOutput, TrackOutput {
        var format: Format? = null
        val samples = mutableListOf<Sample>()
        var pendingBytes = 0
        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            throw UnsupportedOperationException()
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            pendingBytes += length
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            assertEquals("sample data written whole", size, pendingBytes)
            pendingBytes = 0
            samples += Sample(timeUs, size, flags)
        }
    }

    /** One TrueHD access unit of [length] bytes; a major sync unit carries 48 kHz with the given arrangements. */
    private fun unit(length: Int, majorSync: Boolean = false, sixChannel: Int = 0x0F, eightChannel: Int = 0x4F, rateCode: Int = 0): ByteArray {
        val b = ByteArray(length)
        b[0] = (0xF0 or ((length / 2) shr 8)).toByte()
        b[1] = (length / 2).toByte()
        if (majorSync) {
            byteArrayOf(0xF8.toByte(), 0x72, 0x6F, 0xBA.toByte()).copyInto(b, 4)
            val info = (rateCode.toLong() shl 28) or (sixChannel.toLong() shl 15) or eightChannel.toLong()
            for (i in 0..3) b[8 + i] = (info shr (24 - 8 * i)).toByte()
        }
        return b
    }

    private fun ac3Frame() = byteArrayOf(0x0B, 0x77) + ByteArray(126)

    private fun reader(output: CapturingOutput) = BdavTrueHdReader("eng", 0).also {
        it.createTracks(output, TsPayloadReader.TrackIdGenerator(0, 1))
    }

    private fun BdavTrueHdReader.pes(timeUs: Long, vararg payload: ByteArray, end: Boolean = false) {
        packetStarted(timeUs, 0)
        payload.forEach { consume(ParsableByteArray(it)) }
        packetFinished(end)
    }

    @Test fun `major sync sets the track format`() {
        val output = CapturingOutput()
        reader(output).pes(1_000_000, unit(64, majorSync = true))
        val format = output.format!!
        assertEquals(MimeTypes.AUDIO_TRUEHD, format.sampleMimeType)
        assertEquals(8, format.channelCount)
        assertEquals(48000, format.sampleRate)
        assertEquals("en", format.language) // Media3 normalizes ISO 639-2 codes.
    }

    @Test fun `units are emitted from the first major sync, sixteen per sample`() {
        val output = CapturingOutput()
        val reader = reader(output)
        reader.pes(500_000, unit(40)) // Before any major sync: dropped.
        var timeUs = 1_000_000L
        val unitUs = 40 * C.MICROS_PER_SECOND / 48000
        reader.pes(timeUs, unit(64, majorSync = true))
        repeat(31) {
            timeUs += unitUs
            reader.pes(timeUs, unit(40))
            if (it == 10) reader.pes(C.TIME_UNSET, ac3Frame()) // The AC-3 core shares the PID.
        }
        reader.pes(timeUs + unitUs, unit(40), end = true)
        assertEquals(listOf(1_000_000L, 1_000_000L + 16 * unitUs, 1_000_000L + 32 * unitUs), output.samples.map { it.timeUs })
        assertEquals(listOf(64 + 15 * 40, 16 * 40, 40), output.samples.map { it.size })
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, output.samples.first().flags)
    }

    @Test fun `units without a timestamp follow the previous unit`() {
        val output = CapturingOutput()
        val reader = reader(output)
        reader.pes(2_000_000, unit(64, majorSync = true), unit(40), unit(40))
        reader.pes(C.TIME_UNSET, unit(40), end = true)
        assertEquals(listOf(2_000_000L), output.samples.map { it.timeUs })
        assertEquals(64 + 3 * 40, output.samples.single().size)
    }

    @Test fun `a seek waits for the next major sync and drops the partial sample`() {
        val output = CapturingOutput()
        val reader = reader(output)
        reader.pes(1_000_000, unit(64, majorSync = true))
        reader.pes(1_000_833, unit(40))
        reader.seek()
        reader.pes(9_000_000, unit(40))
        reader.pes(9_000_833, unit(64, majorSync = true), end = true)
        assertEquals(listOf(9_000_833L), output.samples.map { it.timeUs })
        assertEquals(64, output.samples.single().size)
    }

    @Test fun `channel arrangements and rate codes`() {
        fun parse(six: Int, eight: Int, rate: Int = 0) = BdavTrueHdReader.parseMajorSync(unit(64, true, six, eight, rate), 0)
        assertEquals(BdavTrueHdReader.MajorSyncInfo(48000, 8), parse(0x0F, 0x4F))
        assertEquals(BdavTrueHdReader.MajorSyncInfo(48000, 6), parse(0x0F, 0))
        assertEquals(BdavTrueHdReader.MajorSyncInfo(96000, 2), parse(0x01, 0, rate = 1))
        assertEquals(BdavTrueHdReader.MajorSyncInfo(44100, 6), parse(0x0F, 0, rate = 8))
        assertNull(parse(0x0F, 0, rate = 0xF))
    }
}
