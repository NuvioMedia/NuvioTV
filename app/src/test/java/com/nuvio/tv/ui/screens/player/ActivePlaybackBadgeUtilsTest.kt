package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActivePlaybackBadgeUtilsTest {

    private var previousLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `formatVideoQuality maps standard and ultrawide cinema dimensions`() {
        assertEquals("4K", ActivePlaybackBadgeUtils.formatVideoQuality(3840, 2160))
        assertEquals("4K", ActivePlaybackBadgeUtils.formatVideoQuality(3840, 1606))
        assertEquals("1440p", ActivePlaybackBadgeUtils.formatVideoQuality(2560, 1440))
        assertEquals("1080p", ActivePlaybackBadgeUtils.formatVideoQuality(1920, 1080))
        assertEquals("1080p", ActivePlaybackBadgeUtils.formatVideoQuality(1920, 800))
        assertEquals("720p", ActivePlaybackBadgeUtils.formatVideoQuality(1280, 720))
        assertEquals("720p", ActivePlaybackBadgeUtils.formatVideoQuality(1280, 534))
        assertEquals("480p", ActivePlaybackBadgeUtils.formatVideoQuality(854, 480))
        assertNull(ActivePlaybackBadgeUtils.formatVideoQuality(0, 0))
    }

    @Test
    fun `detectHdrFormat identifies Dolby Vision HDR10Plus HDR10 and HLG`() {
        assertEquals(
            "Dolby Vision",
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_DOLBY_VISION,
                codecs = "dvhe.08.06",
                colorTransfer = C.COLOR_TRANSFER_ST2084
            )
        )
        assertEquals(
            "Dolby Vision",
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_H265,
                codecs = "hev1",
                colorTransfer = null,
                colormatrix = "dolbyvision"
            )
        )
        assertEquals(
            "HDR10+",
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_H265,
                codecs = "hev1.2.4.L153.B0",
                colorTransfer = C.COLOR_TRANSFER_ST2084,
                streamHints = listOf("Movie.2160p.UHD.BluRay.HDR10+.HEVC.mkv")
            )
        )
        assertEquals(
            "HDR10",
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_H265,
                codecs = "hev1.2.4.L153.B0",
                colorTransfer = C.COLOR_TRANSFER_ST2084
            )
        )
        assertEquals(
            "HLG",
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_H265,
                codecs = null,
                colorTransfer = C.COLOR_TRANSFER_HLG
            )
        )
        assertNull(
            ActivePlaybackBadgeUtils.detectHdrFormat(
                mimeType = MimeTypes.VIDEO_H264,
                codecs = "avc1.640028",
                colorTransfer = C.COLOR_TRANSFER_SDR
            )
        )
    }

    @Test
    fun `detectAudioFormat identifies Dolby and DTS and Stereo formats`() {
        val atmos = ActivePlaybackBadgeUtils.detectAudioFormat(
            mimeType = MimeTypes.AUDIO_E_AC3_JOC,
            codecs = "ec+3",
            label = "English",
            channelCount = 6
        )
        assertEquals("Atmos", atmos?.label)
        assertTrue(atmos?.isDolby == true)

        val ddp = ActivePlaybackBadgeUtils.detectAudioFormat(
            mimeType = MimeTypes.AUDIO_E_AC3,
            codecs = "ec-3",
            label = "English DD+ 5.1",
            channelCount = 6
        )
        assertEquals("Dolby Digital+", ddp?.label)
        assertTrue(ddp?.isDolby == true)

        val dtsHd = ActivePlaybackBadgeUtils.detectAudioFormat(
            mimeType = MimeTypes.AUDIO_DTS_HD,
            codecs = "dtshd",
            label = "DTS-HD MA 7.1",
            channelCount = 8
        )
        assertEquals("DTS-HD", dtsHd?.label)
        assertFalse(dtsHd?.isDolby == true)

        val stereo = ActivePlaybackBadgeUtils.detectAudioFormat(
            mimeType = MimeTypes.AUDIO_AAC,
            codecs = "mp4a.40.2",
            label = "Hindi",
            channelCount = 2
        )
        assertEquals("Stereo", stereo?.label)
        assertFalse(stereo?.isDolby == true)
    }

    @Test
    fun `formatAudioLanguageAndChannels formats language and numeric channel layout`() {
        assertEquals(
            "English 5.1",
            ActivePlaybackBadgeUtils.formatAudioLanguageAndChannels(
                languageCode = "eng",
                label = "English (EAC3 6ch)",
                channelCount = 6
            )
        )
        assertEquals(
            "Hindi 2.0",
            ActivePlaybackBadgeUtils.formatAudioLanguageAndChannels(
                languageCode = "hi",
                label = "Hindi Stereo",
                channelCount = 2
            )
        )
        assertEquals(
            "English 2.0",
            ActivePlaybackBadgeUtils.formatAudioLanguageAndChannels(
                languageCode = "und",
                label = "Original Audio",
                channelCount = 2,
                contentLanguageFallback = "en"
            )
        )
        assertEquals(
            "5.1",
            ActivePlaybackBadgeUtils.formatAudioLanguageAndChannels(
                languageCode = "und",
                label = "Track 1",
                channelCount = 6
            )
        )
    }
}
