package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BdavDtsHdReaderTest {

    private class Sample(val timeUs: Long, val size: Int, val flags: Int)

    private class CapturingOutput : ExtractorOutput, TrackOutput {
        val formats = mutableListOf<Format>()
        val samples = mutableListOf<Sample>()
        var pendingBytes = 0
        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
        override fun format(format: Format) { formats += format }
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

    /** Writes big-endian bit fields. */
    private class Bits {
        private val bits = mutableListOf<Boolean>()
        fun put(value: Long, count: Int) = apply { for (i in count - 1 downTo 0) bits += (value shr i) and 1L == 1L }
        fun bytes(): ByteArray = ByteArray((bits.size + 7) / 8).also { out ->
            bits.forEachIndexed { i, bit -> if (bit) out[i / 8] = (out[i / 8].toInt() or (0x80 shr (i % 8))).toByte() }
        }
    }

    /** A DTS core frame of [length] bytes: 512 samples at 48 kHz, five channels plus LFE. */
    private fun core(length: Int): ByteArray {
        val header = Bits()
            .put(0x7FFE8001, 32)
            .put(1, 1).put(31, 5).put(0, 1) // Normal frame, not short, no CRC.
            .put(15, 7) // 16 blocks of 32 samples.
            .put((length - 1).toLong(), 14)
            .put(9, 6) // Five channels.
            .put(13, 4) // 48 kHz.
            .put(15, 5) // 768 kbit/s.
            .put(0, 10)
            .put(1, 2) // LFE.
            .bytes()
        return header.copyOf(length)
    }

    /** A DTS-HD extension substream of [length] bytes with the short size fields. */
    private fun extension(length: Int): ByteArray {
        val header = Bits()
            .put(0x64582025, 32)
            .put(0, 8).put(0, 2).put(0, 1)
            .put(15, 8) // Header size.
            .put((length - 1).toLong(), 16)
            .bytes()
        return header.copyOf(length)
    }

    private fun reader(output: CapturingOutput) = BdavDtsHdReader("eng", 0).also {
        it.createTracks(output, TsPayloadReader.TrackIdGenerator(0, 1))
    }

    private fun BdavDtsHdReader.pes(timeUs: Long, vararg payload: ByteArray, end: Boolean = false) {
        packetStarted(timeUs, 0)
        payload.forEach { consume(ParsableByteArray(it)) }
        packetFinished(end)
    }

    @Test fun `the core sets the track format`() {
        val output = CapturingOutput()
        reader(output).pes(1_000_000, core(1024), end = true)
        val format = output.formats.single()
        assertEquals(MimeTypes.AUDIO_DTS, format.sampleMimeType)
        assertEquals(6, format.channelCount)
        assertEquals(48000, format.sampleRate)
        assertEquals("en", format.language) // Media3 normalizes ISO 639-2 codes.
    }

    @Test fun `a core and its extension substream in separate packets make one sample`() {
        val output = CapturingOutput()
        val reader = reader(output)
        reader.pes(1_000_000, core(1024))
        reader.pes(1_000_000, extension(68))
        reader.pes(1_010_667, core(1024))
        reader.pes(C.TIME_UNSET, extension(68), end = true)

        assertEquals(listOf(1_000_000L, 1_010_667L), output.samples.map { it.timeUs })
        assertEquals(listOf(1092, 1092), output.samples.map { it.size })
        assertTrue(output.samples.all { it.flags == C.BUFFER_FLAG_KEY_FRAME })
        assertEquals("the format never switches to the extension substream", 1, output.formats.size)
    }

    @Test fun `frames without their own timestamp follow the previous frame`() {
        val output = CapturingOutput()
        reader(output).pes(2_000_000, core(1024), extension(68), core(1024), extension(68), end = true)
        // 512 samples at 48 kHz.
        assertEquals(listOf(2_000_000L, 2_010_666L), output.samples.map { it.timeUs })
    }

    @Test fun `a block split across packets stays in its frame`() {
        val output = CapturingOutput()
        val reader = reader(output)
        val frame = core(1024) + extension(68)
        reader.pes(1_000_000, frame.copyOfRange(0, 600))
        reader.pes(C.TIME_UNSET, frame.copyOfRange(600, frame.size))
        reader.pes(1_010_667, core(1024), end = true)
        assertEquals(listOf(1092, 1024), output.samples.map { it.size })
    }

    @Test fun `data before the first core and after a seek is dropped`() {
        val output = CapturingOutput()
        val reader = reader(output)
        reader.pes(500_000, extension(68)) // An extension without its core: dropped.
        reader.pes(1_000_000, core(1024))
        reader.seek() // The open frame is dropped with the seek.
        reader.pes(5_000_000, core(1024), extension(68), end = true)
        assertEquals(listOf(5_000_000L), output.samples.map { it.timeUs })
        assertEquals(1092, output.samples.single().size)
    }

    @Test fun `blu-ray audio stream types get readers and lpcm gets none`() {
        val factory = BdavTsPayloadReaderFactory.withDefaults(
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
        )
        fun esInfo(streamType: Int) = TsPayloadReader.EsInfo(streamType, "eng", 0, null, ByteArray(0))
        // Read as MPEG-2 video, LPCM would leave a track without a format and the clip unprepared.
        assertNull(factory.createPayloadReader(0x80, esInfo(0x80)))
        assertNotNull(factory.createPayloadReader(0x85, esInfo(0x85)))
        assertNotNull(factory.createPayloadReader(0x86, esInfo(0x86)))
        assertNotNull(factory.createPayloadReader(0x83, esInfo(0x83)))
    }
}
