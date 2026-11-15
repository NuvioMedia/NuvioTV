package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareVideoDecoderSelectorTest {

    private fun decoder(name: String, mimeType: String, softwareOnly: Boolean): MediaCodecInfo =
        MediaCodecInfo.newInstance(
            name,
            mimeType,
            mimeType,
            null,
            !softwareOnly,
            softwareOnly,
            !softwareOnly,
            false,
            false,
        )

    /** No codec string and no size: every decoder of the type reports the format as supported. */
    private fun format(mimeType: String): Format = Format.Builder().setSampleMimeType(mimeType).build()

    private fun names(format: Format, vararg decoders: MediaCodecInfo): List<String> =
        hardwareFirstVideoDecoders(format, decoders.filter { it.mimeType == format.sampleMimeType }).map { it.name }

    private data class FakeDecoder(val name: String, val software: Boolean, val vp9Profiles: Set<Int>)

    private fun vp9Profile(codecs: String): Int = codecs.split('.')[1].toInt()

    private fun vp9(codecs: String, decoders: List<FakeDecoder>): List<String> =
        hardwareFirstVideoDecoders(MimeTypes.VIDEO_VP9, decoders, { it.software }) {
            vp9Profile(codecs) in it.vp9Profiles
        }.map { it.name }

    @Test
    fun `VP9 profile 2 on profile-0-only hardware keeps the software decoder`() {
        val box = listOf(
            FakeDecoder("c2.vendor.vp9.decoder", software = false, vp9Profiles = setOf(0)),
            FakeDecoder("c2.android.vp9.decoder", software = true, vp9Profiles = setOf(0, 2)),
        )
        assertEquals(listOf("c2.vendor.vp9.decoder", "c2.android.vp9.decoder"), vp9("vp09.02.40.10", box))
        assertEquals(listOf("c2.vendor.vp9.decoder"), vp9("vp09.00.40.08", box))
    }

    @Test
    fun `software is dropped as soon as one hardware decoder supports the format`() {
        val box = listOf(
            FakeDecoder("OMX.vendor.vp9.decoder", software = false, vp9Profiles = setOf(0)),
            FakeDecoder("c2.vendor.vp9.decoder", software = false, vp9Profiles = setOf(0, 2)),
            FakeDecoder("c2.android.vp9.decoder", software = true, vp9Profiles = setOf(0, 2)),
        )
        assertEquals(listOf("OMX.vendor.vp9.decoder", "c2.vendor.vp9.decoder"), vp9("vp09.02.40.10", box))
    }

    @Test
    fun `a capability check that fails counts as not supported and keeps the whole list`() {
        // The JVM stubs make media3's size check throw here.
        val sized = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(7680).setHeight(4320).build()
        val decoders = listOf(
            decoder("c2.amlogic.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = false),
            decoder("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = true),
        )
        assertEquals(decoders.map { it.name }, hardwareFirstVideoDecoders(sized, decoders).map { it.name })
    }

    @Test
    fun `software HEVC and H264 are dropped when the hardware supports the format`() {
        val decoders = arrayOf(
            decoder("c2.amlogic.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = false),
            decoder("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = true),
            decoder("OMX.amlogic.avc.decoder.awesome2", MimeTypes.VIDEO_H264, softwareOnly = false),
            decoder("OMX.google.h264.decoder", MimeTypes.VIDEO_H264, softwareOnly = true),
            decoder("c2.android.avc.decoder", MimeTypes.VIDEO_H264, softwareOnly = true),
        )
        assertEquals(listOf("c2.amlogic.hevc.decoder"), names(format(MimeTypes.VIDEO_H265), *decoders))
        assertEquals(listOf("OMX.amlogic.avc.decoder.awesome2"), names(format(MimeTypes.VIDEO_H264), *decoders))
    }

    @Test
    fun `software stays when it is the only decoder for the type`() {
        val decoders = arrayOf(
            decoder("c2.android.vp9.decoder", MimeTypes.VIDEO_VP9, softwareOnly = true),
            decoder("c2.android.av1.decoder", MimeTypes.VIDEO_AV1, softwareOnly = true),
        )
        assertEquals(listOf("c2.android.vp9.decoder"), names(format(MimeTypes.VIDEO_VP9), *decoders))
        assertEquals(listOf("c2.android.av1.decoder"), names(format(MimeTypes.VIDEO_AV1), *decoders))
    }

    @Test
    fun `Android's software AV1 stays when the hardware AV1 decoder cannot take the stream`() {
        val decoders = listOf("c2.amlogic.av1.decoder" to false, "c2.android.av1.decoder" to true)
        val kept = hardwareFirstVideoDecoders(MimeTypes.VIDEO_AV1, decoders, { it.second }) { it.second }
        assertEquals(decoders, kept)
        val dropped = hardwareFirstVideoDecoders(MimeTypes.VIDEO_AV1, decoders, { it.second }) { true }
        assertEquals(listOf(decoders[0]), dropped)
    }

    @Test
    fun `name prefix catches a software decoder the flag misses`() {
        val decoders = arrayOf(
            decoder("c2.mtk.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = false),
            decoder("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = false),
        )
        assertEquals(listOf("c2.mtk.hevc.decoder"), names(format(MimeTypes.VIDEO_H265), *decoders))
    }

    @Test
    fun `hardware order is kept`() {
        val decoders = arrayOf(
            decoder("c2.amlogic.dolby-vision.dvhe.decoder", MimeTypes.VIDEO_DOLBY_VISION, softwareOnly = false),
            decoder("c2.android.dv.decoder", MimeTypes.VIDEO_DOLBY_VISION, softwareOnly = true),
            decoder("c2.amlogic.dolby-vision.dvav.decoder", MimeTypes.VIDEO_DOLBY_VISION, softwareOnly = false),
        )
        assertEquals(
            listOf("c2.amlogic.dolby-vision.dvhe.decoder", "c2.amlogic.dolby-vision.dvav.decoder"),
            names(format(MimeTypes.VIDEO_DOLBY_VISION), *decoders),
        )
    }

    @Test
    fun `other video types are untouched`() {
        val decoders = arrayOf(
            decoder("c2.amlogic.vc1.decoder", "video/vc1", softwareOnly = false),
            decoder("c2.amlogic.vc1.decoder.sw", "video/vc1", softwareOnly = true),
            decoder("c2.amlogic.mpeg2.decoder", MimeTypes.VIDEO_MPEG2, softwareOnly = false),
            decoder("c2.android.mpeg2.decoder", MimeTypes.VIDEO_MPEG2, softwareOnly = true),
        )
        assertEquals(listOf("c2.amlogic.vc1.decoder", "c2.amlogic.vc1.decoder.sw"), names(format("video/vc1"), *decoders))
        assertEquals(
            listOf("c2.amlogic.mpeg2.decoder", "c2.android.mpeg2.decoder"),
            names(format(MimeTypes.VIDEO_MPEG2), *decoders),
        )
    }

    @Test
    fun `empty list stays empty`() {
        assertTrue(hardwareFirstVideoDecoders(format(MimeTypes.VIDEO_H265), emptyList()).isEmpty())
    }

    @Test
    fun `decoder names in the log line`() {
        val softwareOnly = setOf("c2.amlogic.vc1.decoder.sw")
        assertTrue(isSoftwareVideoDecoderName("c2.android.hevc.decoder", emptySet()))
        assertTrue(isSoftwareVideoDecoderName("OMX.google.h264.decoder", emptySet()))
        assertTrue(isSoftwareVideoDecoderName("libdav1d", emptySet()))
        assertTrue(isSoftwareVideoDecoderName("libgav1", emptySet()))
        assertTrue(isSoftwareVideoDecoderName("C2.AMLOGIC.VC1.DECODER.SW", softwareOnly))
        assertFalse(isSoftwareVideoDecoderName("c2.amlogic.hevc.decoder", softwareOnly))
        assertFalse(isSoftwareVideoDecoderName("c2.amlogic.dolby-vision.dvhe.decoder", softwareOnly))
        assertFalse(isSoftwareVideoDecoderName("amstream_dves_hevc.sideband", softwareOnly))
        assertFalse(isSoftwareVideoDecoderName("OMX.MTK.VIDEO.DECODER.HEVC", softwareOnly))
    }
}
