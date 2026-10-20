package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackInfoBadgeFormatterTest {

    @Test
    fun `resolution tiers map to pretty labels`() {
        assertEquals("4K", formatPlaybackResolutionLabel(3840, 2160))
        assertEquals("1440p", formatPlaybackResolutionLabel(2560, 1440))
        assertEquals("1080p", formatPlaybackResolutionLabel(1920, 1080))
        assertEquals("720p", formatPlaybackResolutionLabel(1280, 720))
        assertEquals("480p", formatPlaybackResolutionLabel(853, 480))
    }

    @Test
    fun `rotated video classifies by longer edge`() {
        assertEquals("4K", formatPlaybackResolutionLabel(2160, 3840))
        assertEquals("1080p", formatPlaybackResolutionLabel(1080, 1920))
    }

    @Test
    fun `invalid dimensions produce no resolution segment`() {
        assertNull(formatPlaybackResolutionLabel(null, 1080))
        assertNull(formatPlaybackResolutionLabel(1920, null))
        assertNull(formatPlaybackResolutionLabel(0, 0))
        assertNull(formatPlaybackResolutionLabel(-1, 1080))
    }

    @Test
    fun `hdr label from mime and color transfer`() {
        assertEquals("Dolby Vision", formatPlaybackHdrLabel("video/dolby-vision", null))
        assertEquals("HDR10", formatPlaybackHdrLabel("video/hevc", androidx.media3.common.C.COLOR_TRANSFER_ST2084))
        assertEquals("HLG", formatPlaybackHdrLabel("video/hevc", androidx.media3.common.C.COLOR_TRANSFER_HLG))
        assertNull(formatPlaybackHdrLabel(null, null))
        assertNull(formatPlaybackHdrLabel("video/unknown-fmt", null))
    }

    @Test
    fun `audio track label combines language and channels`() {
        assertEquals("English 5.1", formatPlaybackAudioTrackLabel(6, "en"))
        assertEquals("Tamil Stereo", formatPlaybackAudioTrackLabel(2, "ta"))
        assertEquals("Stereo", formatPlaybackAudioTrackLabel(2, "und"))
        assertNull(formatPlaybackAudioTrackLabel(null, null))
        assertEquals("5.1", formatPlaybackAudioTrackLabel(6, ""))
    }

    @Test
    fun `dd plus joc reports as dolby atmos`() {
        assertEquals("Dolby Atmos", formatPlaybackAudioCodecLabel("E-AC-3-JOC"))
        assertEquals("TrueHD", formatPlaybackAudioCodecLabel("TrueHD"))
        assertNull(formatPlaybackAudioCodecLabel(null))
    }

    @Test
    fun `badge segments drop unknown values`() {
        val segments = buildPlaybackInfoBadgeSegments(
            video = ActivePlaybackInfo(width = 3840, height = 2160, mimeType = "video/dolby-vision"),
            selectedAudio = TrackInfo(index = 0, name = "Audio 1", language = "en", channelCount = 6)
        )
        assertEquals(listOf("4K", "Dolby Vision", "English 5.1"), segments)
    }

    @Test
    fun `badge with nothing known is empty`() {
        val segments = buildPlaybackInfoBadgeSegments(
            video = null,
            selectedAudio = TrackInfo(index = 0, name = "Audio 1", language = null)
        )
        assertEquals(emptyList<String>(), segments)
    }
}
