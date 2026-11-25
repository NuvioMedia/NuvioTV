package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

@UnstableApi
class DolbyVisionLevel5SanitizationTest {

    @Test
    fun matroskaTransformer_shouldTransform_targetsOnlyHevcDolbyVisionWhenZeroL5Enabled() {
        val transformer = DolbyVisionMatroskaTransformer(
            config = DolbyVisionConversionConfig(active = false),
            zeroDoviLevel5 = true
        )

        assertTrue(transformer.shouldTransform("dvhe.08.06", null))
        assertTrue(transformer.shouldTransform("dvh1.05.06", null))
        assertTrue(transformer.shouldTransform("dvhe.07.06", null))
        assertTrue(transformer.shouldTransform(null, byteArrayOf(1, 0, 0x10, 0x10)))

        assertFalse(transformer.shouldTransform("hvc1.2.4.L153.B0", null))
        assertFalse(transformer.shouldTransform("avc1.640028", null))
        assertFalse(transformer.shouldTransform("dav1.10.09", byteArrayOf(1, 0, 0x14, 0x10)))
        assertFalse(transformer.shouldTransform("dvav.09.01", null))
    }

    @Test
    fun matroskaTransformer_zeroL5_rewritesOnlyNonZeroRpuAndPreservesElNals() {
        val fakeOutBuffer = ByteArray(64)
        val rewrittenRpu = byteArrayOf(0x7C.toByte(), 0x01, 0xAA.toByte(), 0xBB.toByte())
        val transformer = DolbyVisionMatroskaTransformer(
            config = DolbyVisionConversionConfig(active = false),
            zeroDoviLevel5 = true,
            zeroLevel5Rpu = { _, _, _ ->
                System.arraycopy(rewrittenRpu, 0, fakeOutBuffer, 0, rewrittenRpu.size)
                rewrittenRpu.size
            },
            rpuOutputBuffer = { fakeOutBuffer }
        )

        // Build length-delimited sample: [IDR slice (type 19)] + [EL NAL (type 63)] + [RPU NAL (type 62)]
        val idrNal = byteArrayOf(0x26, 0x01, 0x11, 0x22, 0x33)
        val elNal = byteArrayOf(0x7E.toByte(), 0x01, 0x44, 0x55)
        val rpuNal = byteArrayOf(0x7C.toByte(), 0x01, 0x19, 0x08, 0x09, 0x0A, 0x0B)
        val sample = buildLengthDelimitedSample(idrNal, elNal, rpuNal)

        val result = transformer.transformHevcSample(
            sampleLengthDelimitedData = sample,
            sampleLength = sample.size,
            nalUnitLengthFieldLength = 4,
            blockAdditionalData = null,
            dolbyVisionConfigBytes = null
        )

        val expected = buildLengthDelimitedSample(idrNal, elNal, rewrittenRpu)
        assertNotSame(sample, result)
        assertEquals(expected.size, transformer.lastTransformedSampleLength())
        assertArrayEquals(expected, result!!.copyOf(transformer.lastTransformedSampleLength()))
    }

    @Test
    fun matroskaTransformer_zeroL5_returnsOriginalSampleUntouchedWhenL5AlreadyZero() {
        val transformer = DolbyVisionMatroskaTransformer(
            config = DolbyVisionConversionConfig(active = false),
            zeroDoviLevel5 = true,
            zeroLevel5Rpu = { _, _, _ -> 0 }
        )

        val idrNal = byteArrayOf(0x26, 0x01, 0x11, 0x22)
        val rpuNal = byteArrayOf(0x7C.toByte(), 0x01, 0x19, 0x08)
        val sample = buildLengthDelimitedSample(idrNal, rpuNal)

        val result = transformer.transformHevcSample(
            sampleLengthDelimitedData = sample,
            sampleLength = sample.size,
            nalUnitLengthFieldLength = 4,
            blockAdditionalData = null,
            dolbyVisionConfigBytes = null
        )

        assertSame(sample, result)
        assertEquals(sample.size, transformer.lastTransformedSampleLength())
    }

    @Test
    fun matroskaTransformer_blockAdditional_sanitizesOnlyWhenL5Rewritten() {
        val fakeOutBuffer = ByteArray(64)
        val rewrittenRpu = byteArrayOf(0x7C.toByte(), 0x01, 0xCC.toByte())
        var rewrite = true
        val transformer = DolbyVisionMatroskaTransformer(
            config = DolbyVisionConversionConfig(active = false),
            zeroDoviLevel5 = true,
            zeroLevel5Rpu = { _, _, _ ->
                if (!rewrite) 0 else {
                    System.arraycopy(rewrittenRpu, 0, fakeOutBuffer, 0, rewrittenRpu.size)
                    rewrittenRpu.size
                }
            },
            rpuOutputBuffer = { fakeOutBuffer }
        )

        val rawRpu = byteArrayOf(0x7C.toByte(), 0x01, 0x10, 0x20)
        assertArrayEquals(rewrittenRpu, transformer.onDolbyVisionBlockAdditionalData(rawRpu, 0x64766343, null))

        rewrite = false
        assertNull(transformer.onDolbyVisionBlockAdditionalData(rawRpu, 0x64766343, null))
    }

    @Test
    fun extractorsFactory_wrapsMp4WhenZeroL5EnabledAndKeepsDv81FormatIntact() {
        val mockExtractor = mockk<Mp4Extractor>(relaxed = true)
        val delegateFactory = ExtractorsFactory { arrayOf(mockExtractor) }

        val factory = DolbyVisionExtractorsFactory(
            delegate = delegateFactory,
            config = DolbyVisionConversionConfig(active = false),
            zeroDoviLevel5 = true
        )

        val extractors = factory.createExtractors()
        assertEquals(1, extractors.size)
        val wrappedExtractor = extractors[0]
        assertNotSame(mockExtractor, wrappedExtractor)

        val mockExtractorOutput = mockk<ExtractorOutput>(relaxed = true)
        val mockTrackOutput = mockk<TrackOutput>(relaxed = true)
        every { mockExtractorOutput.track(any(), C.TRACK_TYPE_VIDEO) } returns mockTrackOutput

        val extractorOutputSlot = slot<ExtractorOutput>()
        every { mockExtractor.init(capture(extractorOutputSlot)) } returns Unit

        wrappedExtractor.init(mockExtractorOutput)
        val wrappedTrackOutput = extractorOutputSlot.captured.track(1, C.TRACK_TYPE_VIDEO)

        val formatSlot = slot<Format>()
        every { mockTrackOutput.format(capture(formatSlot)) } returns Unit

        val dv81Format = Format.Builder()
            .setId("1")
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.06")
            .build()

        wrappedTrackOutput.format(dv81Format)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, formatSlot.captured.sampleMimeType)
        assertEquals("dvhe.08.06", formatSlot.captured.codecs)
    }

    private fun buildLengthDelimitedSample(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            val len = nal.size
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }
}
