package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.RendererCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareAv1RendererTest {

    private fun av1(
        width: Int = 1920,
        height: Int = 1080,
        codecs: String? = null,
        colorInfo: ColorInfo? = null,
        av1c: ByteArray? = null,
    ): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_AV1)
        .setWidth(width)
        .setHeight(height)
        .setCodecs(codecs)
        .setColorInfo(colorInfo)
        .setInitializationData(av1c?.let { listOf(it) })
        .build()

    private fun dav1d(format: Format, hardware: HardwareAv1Support): Int =
        softwareAv1FormatSupport(format, C.FORMAT_HANDLED) { hardware }

    private fun slot(renderers: List<String>, mode: Int, first: Boolean = false): Int? =
        softwareAv1Slot(renderers, mode, first) { it == "mediacodec" }

    /** Media3 maps a track to the renderer with the best support; the earlier renderer wins a tie. */
    private fun chosen(mode: Int, mediaCodec: Int, dav1d: Int, first: Boolean = false): String {
        val renderers = mutableListOf("mediacodec")
        val slot = slot(renderers, mode, first)
        if (slot != null) renderers.add(slot, "dav1d")
        val support = renderers.map { if (it == "dav1d") dav1d else mediaCodec }
        val best = support.maxOrNull()!!
        if (best < C.FORMAT_EXCEEDS_CAPABILITIES) return "none"
        return renderers[support.indexOf(best)]
    }

    @Test
    fun `dav1d goes right after MediaCodec unless extensions are off`() {
        assertEquals(1, slot(listOf("mediacodec", "libvpx"), EXTENSION_RENDERER_MODE_ON))
        assertEquals(2, slot(listOf("libvpx", "mediacodec"), EXTENSION_RENDERER_MODE_PREFER))
        assertEquals(1, slot(listOf("libvpx"), EXTENSION_RENDERER_MODE_ON))
        assertNull(slot(listOf("mediacodec"), EXTENSION_RENDERER_MODE_OFF))
    }

    @Test
    fun `test switch puts dav1d first`() {
        assertEquals(0, slot(listOf("mediacodec"), EXTENSION_RENDERER_MODE_ON, first = true))
        assertNull(slot(listOf("mediacodec"), EXTENSION_RENDERER_MODE_OFF, first = true))
    }

    @Test
    fun `Android's software AV1 gives way only for what dav1d plays`() {
        assertTrue(dav1dTakesOverFromAndroid(av1()))
        assertTrue(dav1dTakesOverFromAndroid(av1(width = Format.NO_VALUE, height = Format.NO_VALUE)))
        assertFalse(dav1dTakesOverFromAndroid(av1(codecs = "av01.0.08M.10")))
        assertFalse(dav1dTakesOverFromAndroid(av1(width = 3840, height = 2160)))
        assertFalse(dav1dTakesOverFromAndroid(av1().buildUpon().setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build()))
        assertFalse(dav1dTakesOverFromAndroid(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()))
    }

    @Test
    fun `hardware AV1 decoder keeps the stream in both modes`() {
        val format = av1()
        val software = dav1d(format, HardwareAv1Support.HANDLES)
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, software)
        assertEquals("mediacodec", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_HANDLED, software))
        assertEquals("mediacodec", chosen(EXTENSION_RENDERER_MODE_PREFER, C.FORMAT_HANDLED, software))
    }

    @Test
    fun `no hardware AV1 decoder plays 1080p on dav1d`() {
        val software = dav1d(av1(), HardwareAv1Support.NONE)
        assertEquals(C.FORMAT_HANDLED, software)
        assertEquals("dav1d", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_UNSUPPORTED_SUBTYPE, software))
        assertEquals("dav1d", chosen(EXTENSION_RENDERER_MODE_PREFER, C.FORMAT_UNSUPPORTED_SUBTYPE, software))
    }

    @Test
    fun `hardware beyond its limits hands 1080p to dav1d`() {
        val software = dav1d(av1(), HardwareAv1Support.BEYOND_LIMITS)
        assertEquals("dav1d", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_EXCEEDS_CAPABILITIES, software))
    }

    @Test
    fun `hardware beyond its limits with Android's software AV1 still hands 1080p to dav1d`() {
        // c2.android.av1.decoder is left out of MediaCodec's list, so only the hardware's answer is left.
        val format = av1()
        assertTrue(dav1dTakesOverFromAndroid(format))
        val software = dav1d(format, HardwareAv1Support.BEYOND_LIMITS)
        assertEquals("dav1d", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_EXCEEDS_CAPABILITIES, software))
        assertEquals("dav1d", chosen(EXTENSION_RENDERER_MODE_PREFER, C.FORMAT_EXCEEDS_CAPABILITIES, software))
    }

    @Test
    fun `above 1080p dav1d steps aside when the box has a hardware decoder`() {
        val uhd = av1(width = 3840, height = 2160)
        val software = dav1d(uhd, HardwareAv1Support.BEYOND_LIMITS)
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, software)
        assertEquals("mediacodec", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_EXCEEDS_CAPABILITIES, software))
        assertEquals("mediacodec", chosen(EXTENSION_RENDERER_MODE_PREFER, C.FORMAT_EXCEEDS_CAPABILITIES, software))
    }

    @Test
    fun `above 1080p without any hardware decoder is still tried on dav1d`() {
        val software = dav1d(av1(width = 3840, height = 2160), HardwareAv1Support.NONE)
        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, software)
        assertEquals("dav1d", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_UNSUPPORTED_SUBTYPE, software))
        // Android's software AV1 stays in MediaCodec's list above 1080p and wins when it claims the stream.
        assertEquals("mediacodec", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_HANDLED, software))
    }

    @Test
    fun `10-bit AV1 never goes to dav1d`() {
        val tenBit = av1(codecs = "av01.0.08M.10")
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, dav1d(tenBit, HardwareAv1Support.NONE))
        assertEquals("mediacodec", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_HANDLED, dav1d(tenBit, HardwareAv1Support.NONE)))
    }

    @Test
    fun `box with only Android's software AV1 decoder uses dav1d for 8-bit and Android for 10-bit`() {
        // 8-bit: c2.android.av1.decoder gives way, MediaCodec has nothing left. 10-bit: it stays.
        val eightBit = dav1d(av1(), HardwareAv1Support.NONE)
        assertEquals("dav1d", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_UNSUPPORTED_SUBTYPE, eightBit))
        assertEquals("dav1d", chosen(EXTENSION_RENDERER_MODE_PREFER, C.FORMAT_UNSUPPORTED_SUBTYPE, eightBit))
        assertEquals("mediacodec", chosen(EXTENSION_RENDERER_MODE_OFF, C.FORMAT_HANDLED, eightBit))
        val tenBit = dav1d(av1(codecs = "av01.0.08M.10"), HardwareAv1Support.NONE)
        assertEquals("mediacodec", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_HANDLED, tenBit))
    }

    @Test
    fun `test switch hands even a hardware stream to dav1d`() {
        // The switch replaces the hardware check with NONE; MediaCodec still reports the stream as handled.
        val software = dav1d(av1(), HardwareAv1Support.NONE)
        assertEquals("dav1d", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_HANDLED, software, first = true))
        assertEquals("mediacodec", chosen(EXTENSION_RENDERER_MODE_ON, C.FORMAT_HANDLED, software))
    }

    @Test
    fun `other answers pass through unchanged`() {
        var asked = false
        val result = softwareAv1FormatSupport(av1(), C.FORMAT_UNSUPPORTED_TYPE) {
            asked = true
            HardwareAv1Support.NONE
        }
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, result)
        assertFalse(asked)
    }

    @Test
    fun `bit depth from colour info, codec string and av1C`() {
        assertEquals(10, av1BitDepth(av1(colorInfo = ColorInfo.Builder().setLumaBitdepth(10).setChromaBitdepth(10).build())))
        assertEquals(10, av1BitDepth(av1(codecs = "av01.0.13M.10.0.110.09.16.09.0")))
        assertEquals(8, av1BitDepth(av1(codecs = "av01.0.08M.08")))
        assertEquals(10, av1BitDepth(av1(av1c = byteArrayOf(0x81.toByte(), 0x08, 0x4C, 0x00))))
        assertEquals(8, av1BitDepth(av1(av1c = byteArrayOf(0x81.toByte(), 0x08, 0x0C, 0x00))))
        assertEquals(12, av1BitDepth(av1(av1c = byteArrayOf(0x81.toByte(), 0x48, 0x6C, 0x00))))
        assertNull(av1BitDepth(av1()))
        assertNull(av1BitDepth(av1(av1c = byteArrayOf(0x01, 0x08, 0x4C))))
    }

    @Test
    fun `HDR transfer without a bit depth counts as 10-bit`() {
        val pq = ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
        assertTrue(isHighBitDepthAv1(av1(colorInfo = pq)))
        assertFalse(isHighBitDepthAv1(av1()))
    }

    @Test
    fun `size cap is 1080p either way round`() {
        assertFalse(exceedsSoftwareAv1Size(av1(1920, 1080)))
        assertFalse(exceedsSoftwareAv1Size(av1(1920, 1088)))
        assertFalse(exceedsSoftwareAv1Size(av1(1080, 1920)))
        assertTrue(exceedsSoftwareAv1Size(av1(2560, 1440)))
        assertTrue(exceedsSoftwareAv1Size(av1(1920, 1200)))
        assertFalse(exceedsSoftwareAv1Size(av1(Format.NO_VALUE, Format.NO_VALUE)))
    }

    @Test
    fun `capabilities wrapper keeps the decoder flags`() {
        val handled = RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
        )
        val delegate = object : RendererCapabilities {
            override fun getName() = "Libdav1dVideoRenderer"
            override fun getTrackType() = C.TRACK_TYPE_VIDEO
            override fun supportsFormat(format: Format) =
                if (format.sampleMimeType == MimeTypes.VIDEO_AV1) handled
                else RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
        }
        var hardware = HardwareAv1Support.NONE
        val wrapped = SoftwareAv1Capabilities(delegate) { hardware }
        assertEquals("Libdav1dVideoRenderer", wrapped.name)
        assertEquals(handled, wrapped.supportsFormat(av1()))
        val uhd = wrapped.supportsFormat(av1(width = 3840, height = 2160))
        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, RendererCapabilities.getFormatSupport(uhd))
        assertEquals(RendererCapabilities.ADAPTIVE_SEAMLESS, RendererCapabilities.getAdaptiveSupport(uhd))
        hardware = HardwareAv1Support.HANDLES
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, RendererCapabilities.getFormatSupport(wrapped.supportsFormat(av1())))
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, RendererCapabilities.getFormatSupport(wrapped.supportsFormat(hevc)))
    }
}
