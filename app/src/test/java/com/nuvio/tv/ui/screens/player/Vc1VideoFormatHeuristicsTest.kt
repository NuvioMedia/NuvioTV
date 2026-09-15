package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Vc1VideoFormatHeuristicsTest {

    @Test
    fun isLikelyVc1_detectsMimeTypes() {
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(sampleMimeType = MimeTypes.VIDEO_VC1))
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(sampleMimeType = "video/wvc1"))
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(sampleMimeType = "video/vc1"))
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(sampleMimeType = "video/VC1"))
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(sampleMimeType = "video/x-ms-wmv"))
        assertFalse(Vc1VideoFormatHeuristics.isLikelyVc1(sampleMimeType = MimeTypes.VIDEO_H264))
        assertFalse(Vc1VideoFormatHeuristics.isLikelyVc1(sampleMimeType = MimeTypes.VIDEO_H265))
    }

    @Test
    fun isLikelyVc1_detectsCodecsAndLabels() {
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(codecs = "wvc1.1"))
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(codecs = "vc-1"))
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(codecs = "wmv3"))
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(label = "1080p VC1"))
        assertFalse(Vc1VideoFormatHeuristics.isLikelyVc1(codecs = "avc1.640028"))
        assertFalse(Vc1VideoFormatHeuristics.isLikelyVc1(codecs = "hvc1.1.6.L153.B0"))
    }

    @Test
    fun isLikelyVc1_detectsStreamNames() {
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(streamName = "Movie.2008.1080p.BluRay.Remux.VC-1.DTS-HD.MA.5.1"))
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(streamName = "Film.Title.VC1.1080p.mkv"))
        assertTrue(Vc1VideoFormatHeuristics.isLikelyVc1(streamName = "[VC-1] Feature Presentation"))
        assertFalse(Vc1VideoFormatHeuristics.isLikelyVc1(streamName = "Movie.2008.1080p.BluRay.Remux.AVC.DTS-HD.MA.5.1"))
        assertFalse(Vc1VideoFormatHeuristics.isLikelyVc1(streamName = "Show.S01E01.1080p.HEVC.mkv"))
    }

    @Test
    fun isLikelyVc1Stream_detectsFromMultipleHints() {
        assertTrue(
            Vc1VideoFormatHeuristics.isLikelyVc1Stream(
                null,
                "Regular Title",
                "The.Dark.Knight.2008.1080p.VC-1.mkv",
                null
            )
        )
        assertTrue(
            Vc1VideoFormatHeuristics.isLikelyVc1Stream(
                "[RD+] Movie 1080p VC1 Remux",
                null,
                null
            )
        )
        assertTrue(
            Vc1VideoFormatHeuristics.isLikelyVc1Stream(
                "http://stream.example/files/movie.vc-1.mkv"
            )
        )
        assertFalse(
            Vc1VideoFormatHeuristics.isLikelyVc1Stream(
                "AVC movie",
                "1080p x264",
                "http://stream.example/movie.mkv"
            )
        )
        assertFalse(
            Vc1VideoFormatHeuristics.isLikelyVc1Stream(null, null)
        )
    }

    @Test
    fun isVc1PlaybackFailure_whenTrackIsVc1AndDecoderInitFails_returnsTrue() {
        val error = PlaybackException(
            "Decoder init failed",
            null,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )
        assertTrue(
            Vc1VideoFormatHeuristics.isVc1PlaybackFailure(
                error = error,
                currentVideoTrackIsLikelyVc1 = true
            )
        )
    }

    @Test
    fun isVc1PlaybackFailure_whenStrictlyAudioRendererFails_returnsFalse() {
        val audioFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .build()
        val error = ExoPlaybackException.createForRenderer(
            RuntimeException("Audio sink error"),
            "audioRenderer",
            0,
            audioFormat,
            C.FORMAT_HANDLED,
            false,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        )
        assertFalse(
            Vc1VideoFormatHeuristics.isVc1PlaybackFailure(
                error = error,
                currentVideoTrackIsLikelyVc1 = true
            )
        )
    }

    @Test
    fun isVc1PlaybackFailure_whenRendererFormatIsVc1_returnsTrue() {
        val videoFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_VC1)
            .build()
        val error = ExoPlaybackException.createForRenderer(
            RuntimeException("Video codec error"),
            "videoRenderer",
            0,
            videoFormat,
            C.FORMAT_HANDLED,
            false,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )
        assertTrue(
            Vc1VideoFormatHeuristics.isVc1PlaybackFailure(
                error = error,
                currentVideoTrackIsLikelyVc1 = false
            )
        )
    }

    @Test
    fun isVc1PlaybackFailure_whenStreamNameHasVc1AndDecoderInitFails_returnsTrue() {
        val error = PlaybackException(
            "No decoder found",
            null,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )
        assertTrue(
            Vc1VideoFormatHeuristics.isVc1PlaybackFailure(
                error = error,
                currentVideoTrackIsLikelyVc1 = false,
                currentStreamName = "Movie.Remux.VC-1.1080p.mkv"
            )
        )
    }
}
