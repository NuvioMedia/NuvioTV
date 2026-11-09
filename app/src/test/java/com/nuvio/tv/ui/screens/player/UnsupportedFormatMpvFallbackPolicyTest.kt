package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsupportedFormatMpvFallbackPolicyTest {

    private fun unplayable(
        exo: Boolean = true,
        switched: Boolean = false,
        present: Boolean = true,
        selected: Boolean = false,
        support: Int = C.FORMAT_UNSUPPORTED_SUBTYPE
    ) = UnsupportedFormatMpvFallbackPolicy.shouldSwitchForUnplayableVideoTrack(exo, switched, present, selected, support)

    private fun fatal(
        errorCode: Int,
        mime: String?,
        exo: Boolean = true,
        switched: Boolean = false,
        firstFrame: Boolean = false,
        fileName: String? = null,
        streamMime: String? = null
    ) = UnsupportedFormatMpvFallbackPolicy.shouldSwitchForFatalError(
        exo, switched, firstFrame, errorCode, mime, fileName, streamMime
    )

    @Test
    fun videoTrackWithoutAnyDecoder_goesToMpvOnce() {
        assertTrue(unplayable())
        assertTrue(unplayable(support = C.FORMAT_UNSUPPORTED_TYPE))
        assertFalse(unplayable(switched = true))
        assertFalse(unplayable(exo = false))
    }

    @Test
    fun playableOrAudioOnlyStreams_stayOnExoPlayer() {
        assertFalse(unplayable(selected = true, support = C.FORMAT_HANDLED))
        assertFalse(unplayable(support = C.FORMAT_HANDLED))
        assertFalse(unplayable(support = C.FORMAT_EXCEEDS_CAPABILITIES))
        assertFalse(unplayable(support = C.FORMAT_UNSUPPORTED_DRM))
        assertFalse(unplayable(present = false))
    }

    @Test
    fun unreadableContainer_goesToMpvBeforeTheFirstFrameOnly() {
        val code = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        val wmv = "Some.Show.S01E01.WMV"
        assertTrue(fatal(code, mime = null, fileName = wmv))
        assertTrue(fatal(code, mime = null, fileName = "https://host/dl/film.rmvb?token=abc"))
        assertTrue(fatal(code, mime = null, streamMime = "video/x-ms-asf"))
        assertFalse(fatal(code, mime = null, fileName = wmv, firstFrame = true))
        assertFalse(fatal(code, mime = null, fileName = wmv, switched = true))
        assertFalse(fatal(code, mime = null, fileName = wmv, exo = false))
    }

    @Test
    fun deadLinksAndOrdinaryFiles_doNotGoToMpvOnAContainerError() {
        val code = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        assertFalse(fatal(code, mime = null))
        assertFalse(fatal(code, mime = null, fileName = "https://host/expired-link", streamMime = "text/html"))
        assertFalse(fatal(code, mime = null, fileName = "Film.2019.2160p.mkv"))
        assertFalse(fatal(code, mime = null, fileName = "film.wmv.torrent"))
    }

    @Test
    fun decoderFailure_goesToMpvForLegacyFormatsOnly() {
        val init = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        val decoding = PlaybackException.ERROR_CODE_DECODING_FAILED
        assertTrue(fatal(init, MimeTypes.VIDEO_VC1))
        assertTrue(fatal(init, MimeTypes.VIDEO_MP4V))
        assertTrue(fatal(decoding, MimeTypes.VIDEO_MP43, firstFrame = true))
        assertTrue(fatal(init, "video/x-ms-wmv"))

        assertFalse(fatal(init, MimeTypes.VIDEO_H265))
        assertFalse(fatal(init, MimeTypes.VIDEO_H264))
        assertFalse(fatal(init, MimeTypes.VIDEO_DOLBY_VISION))
        assertFalse(fatal(decoding, MimeTypes.VIDEO_AV1))
        assertFalse(fatal(init, null))
    }

    @Test
    fun networkAndAudioErrors_neverSwitch() {
        assertFalse(fatal(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, MimeTypes.VIDEO_VC1))
        assertFalse(fatal(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, MimeTypes.VIDEO_MP4V))
        assertFalse(fatal(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, MimeTypes.VIDEO_VC1))
        assertFalse(fatal(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, MimeTypes.VIDEO_VC1))
    }

    @Test
    fun `a transport stream with sound but no picture goes to MPV`() {
        fun missing(name: String?, audio: Boolean = true, mime: String? = null, switched: Boolean = false) =
            UnsupportedFormatMpvFallbackPolicy.shouldSwitchForMissingVideoTrack(true, switched, audio, name, mime)
        assertTrue(missing("Movie.VC1.m2ts"))
        assertTrue(missing("fmt-vc1.ts?token=1"))
        assertTrue(missing(null, mime = MimeTypes.VIDEO_MP2T))
        assertFalse(missing("fmt-vc1.ts", audio = false))
        assertFalse(missing("fmt-vc1.ts", switched = true))
        assertFalse(missing("album.flac"))
        assertFalse(missing("movie.mkv"))
        assertFalse(UnsupportedFormatMpvFallbackPolicy.shouldSwitchForMissingVideoTrack(false, false, true, "a.ts", null))
    }
}
