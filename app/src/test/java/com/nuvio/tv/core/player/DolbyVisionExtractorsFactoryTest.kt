package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.TsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

@UnstableApi
class DolbyVisionExtractorsFactoryTest {

    private val factory = DolbyVisionExtractorsFactory(
        delegate = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> = emptyArray()
            override fun createExtractors(
                uri: Uri,
                responseHeaders: Map<String, List<String>>
            ): Array<Extractor> = emptyArray()
        },
        config = DolbyVisionConversionConfig(active = false),
        stripHdr10PlusSei = true
    )

    @Test
    fun mp4AndTsExtractorsDeliverAnnexB() {
        // Media3's MP4 extractors rewrite NAL length prefixes into 4-byte start codes
        // before calling TrackOutput; TS carries Annex-B PES payloads.
        assertEquals(NalFormat.ANNEX_B, factory.nalFormatFor(Mp4Extractor()))
        assertEquals(NalFormat.ANNEX_B, factory.nalFormatFor(FragmentedMp4Extractor()))
        assertEquals(NalFormat.ANNEX_B, factory.nalFormatFor(TsExtractor()))
    }

    @Test
    fun unrelatedExtractorIsNotWrapped() {
        assertNull(factory.nalFormatFor(StubExtractor()))
    }

    private class StubExtractor : Extractor {
        override fun init(output: ExtractorOutput) {}
        override fun sniff(input: ExtractorInput): Boolean = false
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }
}
