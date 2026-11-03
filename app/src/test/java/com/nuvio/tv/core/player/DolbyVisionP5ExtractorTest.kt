package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs without the native library, so RPUs are never recognised as Profile 5 here. */
class DolbyVisionP5ExtractorTest {

    private class RecordingTrackOutput : TrackOutput {
        val events = mutableListOf<String>()
        var lastFormat: Format? = null
        val bytes = ByteArrayOutputStream()

        override fun format(format: Format) {
            events += "format"
            lastFormat = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val chunk = ByteArray(length)
            val read = input.read(chunk, 0, length)
            bytes.write(chunk, 0, read)
            events += "data"
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val chunk = ByteArray(length)
            data.readBytes(chunk, 0, length)
            bytes.write(chunk)
            events += "data"
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            events += "meta"
        }
    }

    private class FakeExtractor : Extractor {
        lateinit var output: ExtractorOutput
        override fun init(output: ExtractorOutput) {
            this.output = output
        }
        override fun sniff(input: ExtractorInput): Boolean = true
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }

    private class FakeExtractorOutput(private val track: TrackOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private val sessions = mutableListOf<DolbyVisionP5Session>()

    @After
    fun releaseSessions() = sessions.forEach(DolbyVisionP5Session::release)

    private fun videoTrack(active: Boolean, recorder: RecordingTrackOutput): TrackOutput {
        val session = DolbyVisionP5Session(
            active = active,
            blockedReason = if (active) null else DolbyVisionP5Session.BlockedReason.NO_LIBRARY,
        ).also { sessions += it }
        val inner = FakeExtractor()
        DolbyVisionP5Extractor(inner, session).init(FakeExtractorOutput(recorder))
        return inner.output.track(0, C.TRACK_TYPE_VIDEO)
    }

    private fun format(mimeType: String, codecs: String?): Format =
        Format.Builder().setSampleMimeType(mimeType).setCodecs(codecs).build()

    private val sample = byteArrayOf(0, 0, 0, 1, 0x02, 0x01, 0x11, 0x22, 0x33)

    @Test
    fun signalledProfile5_goesOutAsHdr10RightAway() {
        val recorder = RecordingTrackOutput()
        val track = videoTrack(active = true, recorder)
        track.format(format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.05.06"))
        assertEquals(listOf("format"), recorder.events)
        val out = recorder.lastFormat!!
        assertEquals(MimeTypes.VIDEO_H265, out.sampleMimeType)
        assertEquals(C.COLOR_TRANSFER_ST2084, out.colorInfo?.colorTransfer)
        assertEquals(C.COLOR_RANGE_FULL, out.colorInfo?.colorRange)
        assertEquals(C.COLOR_SPACE_BT2020, out.colorInfo?.colorSpace)
    }

    @Test
    fun unsignalledHevc_holdsItsFormatUntilTheFirstSample() {
        val recorder = RecordingTrackOutput()
        val track = videoTrack(active = true, recorder)
        val original = format(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0")
        track.format(original)
        track.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        assertTrue("nothing may reach the queue before the format", recorder.events.isEmpty())

        track.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)
        // Not Profile 5 (no native library here): the original format, then the bytes, in order.
        assertEquals(listOf("format", "data", "meta"), recorder.events)
        assertEquals(original, recorder.lastFormat)
        assertArrayEquals(sample, recorder.bytes.toByteArray())

        // Settled: later samples pass straight through.
        track.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        assertEquals("data", recorder.events.last())
    }

    @Test
    fun unsignalledHevc_supplementalDataEndsTheDeferral() {
        val recorder = RecordingTrackOutput()
        val track = videoTrack(active = true, recorder)
        track.format(format(MimeTypes.VIDEO_H265, null))
        track.sampleData(ParsableByteArray(byteArrayOf(1, 2, 3, 4)), 4, TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
        assertEquals(listOf("format", "data"), recorder.events)
    }

    @Test
    fun otherCodecs_passThrough() {
        val recorder = RecordingTrackOutput()
        val track = videoTrack(active = true, recorder)
        track.format(format(MimeTypes.VIDEO_H264, "avc1.640028"))
        track.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        assertEquals(listOf("format", "data"), recorder.events)
    }

    @Test
    fun inactiveSession_neverHoldsTheFormat() {
        val recorder = RecordingTrackOutput()
        val track = videoTrack(active = false, recorder)
        track.format(format(MimeTypes.VIDEO_H265, null))
        track.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        assertEquals(listOf("format", "data"), recorder.events)
    }
}
