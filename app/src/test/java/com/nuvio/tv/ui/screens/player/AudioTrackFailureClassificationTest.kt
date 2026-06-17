package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [isAudioTrackFailure].
 *
 * A fatal AudioTrack *write* failure (`ERROR_CODE_AUDIO_TRACK_WRITE_FAILED` / 5002, e.g.
 * `AudioTrack.ERROR_DEAD_OBJECT` (-6) on an E-AC-3 passthrough track) must be classified the
 * same as an *init* failure (5001) so it routes into the safe-audio → audio-disabled recovery
 * ladder instead of landing on the fatal "Playback Error" screen.
 */
class AudioTrackFailureClassificationTest {

    @Test
    fun `init failure code is an audio-track failure`() {
        assertTrue(isAudioTrackFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, ""))
    }

    @Test
    fun `write failure code is an audio-track failure`() {
        assertTrue(isAudioTrackFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, ""))
    }

    @Test
    fun `on-screen 5002 code is an audio-track failure`() {
        // Mirrors "AudioTrack write failed: -6 [5002]" reported on Android TV (Hisense 65U6G).
        assertTrue(isAudioTrackFailure(5002, ""))
    }

    @Test
    fun `write failure message is matched under a generic error code`() {
        assertTrue(
            isAudioTrackFailure(
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                "MediaCodecAudioRenderer error ... AudioTrack write failed: -6"
            )
        )
    }

    @Test
    fun `init failure message is matched under a generic error code`() {
        assertTrue(
            isAudioTrackFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, "AudioTrack init failed")
        )
    }

    @Test
    fun `message matching is case-insensitive`() {
        assertTrue(
            isAudioTrackFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, "AUDIOTRACK WRITE FAILED")
        )
    }

    @Test
    fun `unrelated decoder error is not an audio-track failure`() {
        assertFalse(
            isAudioTrackFailure(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                "MediaCodecVideoRenderer error ... decoder failed"
            )
        )
    }

    @Test
    fun `unrelated io error with empty message is not an audio-track failure`() {
        assertFalse(
            isAudioTrackFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, "")
        )
    }
}
