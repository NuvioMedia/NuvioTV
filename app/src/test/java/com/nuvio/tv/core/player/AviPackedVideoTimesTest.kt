package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.avi.ArraySource
import com.nuvio.tv.core.player.avi.Mpeg4Vops
import com.nuvio.tv.core.player.avi.RecordingOutput
import com.nuvio.tv.core.player.avi.TestAvi
import com.nuvio.tv.core.player.avi.runExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AviPackedVideoTimesTest {

    private fun videoFormat(file: ByteArray): Format {
        val stock = ExtractorsFactory {
            arrayOf(AviExtractor(AviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED))
        }
        val extractor = AviTrackFormatExtractorsFactory(stock).createExtractors().single()
        val output = RecordingOutput()
        extractor.init(output)
        runExtractor(extractor, ArraySource(file))
        return output.track(C.TRACK_TYPE_VIDEO).format!!
    }

    @Test
    fun packedVideoSkipsTheDecodeOrderRepair() {
        val format = videoFormat(TestAvi.build(openDml = true, videoChunk = Mpeg4Vops::packedGopChunk))
        assertSame(PresentationOrderTimes, format.customData)
        assertNull(DecodeOrderTimestamps.maxReorderFramesFor(format))
    }

    @Test
    fun otherAviVideoKeepsTheRepair() {
        val format = videoFormat(TestAvi.build(openDml = true))
        assertNull(format.customData)
        assertEquals(1, DecodeOrderTimestamps.maxReorderFramesFor(format))
    }
}
