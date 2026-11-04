package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegVideoFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegVideoFormatsTest {

    private fun avc(codecs: String? = null, sps: ByteArray? = null): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs(codecs)
            .setInitializationData(listOfNotNull(sps))
            .build()

    private fun sps(profileIdc: Int, startCode: Boolean = true): ByteArray {
        val nal = byteArrayOf(0x67, profileIdc.toByte(), 0x00, 0x28)
        return if (startCode) byteArrayOf(0, 0, 0, 1) + nal else nal
    }

    @Test
    fun hi10pFromCodecsString() {
        assertEquals(110, FfmpegVideoFormats.getAvcProfileIdc(avc(codecs = "avc1.6E0028")))
        assertTrue(FfmpegVideoFormats.isSoftwareDecodeCandidate(avc(codecs = "avc1.6E0028")))
        assertTrue(FfmpegVideoFormats.isSoftwareDecodeCandidate(avc(codecs = "mp4a.40.2, avc3.7A0029")))
        assertTrue(FfmpegVideoFormats.isSoftwareDecodeCandidate(avc(codecs = "avc1.F4001F")))
    }

    @Test
    fun hi10pFromSps() {
        assertTrue(FfmpegVideoFormats.isSoftwareDecodeCandidate(avc(sps = sps(110))))
        assertTrue(FfmpegVideoFormats.isSoftwareDecodeCandidate(avc(sps = sps(122, startCode = false))))
        assertTrue(FfmpegVideoFormats.isSoftwareDecodeCandidate(avc(sps = sps(44))))
    }

    @Test
    fun eightBitAvcStaysOnHardware() {
        assertFalse(FfmpegVideoFormats.isSoftwareDecodeCandidate(avc(codecs = "avc1.640028")))
        assertFalse(FfmpegVideoFormats.isSoftwareDecodeCandidate(avc(codecs = "avc1.4D401F")))
        assertFalse(FfmpegVideoFormats.isSoftwareDecodeCandidate(avc(sps = sps(100))))
        // Unknown profile: leave it to MediaCodec.
        assertFalse(FfmpegVideoFormats.isSoftwareDecodeCandidate(avc()))
        assertEquals(-1, FfmpegVideoFormats.getAvcProfileIdc(avc(sps = byteArrayOf(0, 0, 1, 0x68, 0x6E))))
    }

    @Test
    fun otherCodecs() {
        val vc1 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VC1).build()
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L120.B0").build()
        assertTrue(FfmpegVideoFormats.isSoftwareDecodeCandidate(vc1))
        assertFalse(FfmpegVideoFormats.isSoftwareDecodeCandidate(hevc))
        assertFalse(FfmpegVideoFormats.isHighBitDepthAvc(hevc))
    }
}
