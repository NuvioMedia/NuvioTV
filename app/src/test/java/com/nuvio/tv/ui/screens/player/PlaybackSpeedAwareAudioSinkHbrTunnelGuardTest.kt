package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PlaybackSpeedAwareAudioSinkHbrTunnelGuardTest {

    // Keyed on the format alone: no setting, no probe result, no IEC sink underneath.
    @Test
    fun everyHbrFormat_demandsNonTunnelledVideo() {
        val sink = PlaybackSpeedAwareAudioSink(sink = PlainSink())
        for (mime in HBR_MIMES) {
            assertTrue(mime, sink.hbrDemandsNonTunnelledVideo(format(mime)))
        }
    }

    @Test
    fun otherFormats_keepTheirTunnel() {
        val sink = PlaybackSpeedAwareAudioSink(sink = PlainSink())
        for (mime in NON_HBR_MIMES) {
            assertFalse(mime, sink.hbrDemandsNonTunnelledVideo(format(mime)))
        }
        assertFalse(sink.hbrDemandsNonTunnelledVideo(Format.Builder().build()))
    }

    // The guard must hold whatever the sink would do with the format right now: a title decoded to
    // PCM at 1.25x goes back to passthrough at 1x without the player being rebuilt.
    @Test
    fun stillDemandsIt_whileTheFormatIsBeingDecodedToPcm() {
        val sink = PlaybackSpeedAwareAudioSink(sink = PlainSink())
        sink.setPlaybackParameters(PlaybackParameters(1.25f))
        assertTrue(sink.shouldForcePcmForFormat(format(MimeTypes.AUDIO_TRUEHD)))
        assertTrue(sink.hbrDemandsNonTunnelledVideo(format(MimeTypes.AUDIO_TRUEHD)))
    }

    // The lost race at title start: the resolved policy denies every format (a chain snapshot
    // taken while HDMI was down), the sink reports the format unsupported, and the guard still
    // fires, because the policy can be replaced in place later and the title then passes through.
    @Test
    fun stillDemandsIt_whileThePolicyDeniesTheFormat() {
        val denyAll = AudioPassthroughPolicy(
            allowAc3 = false, allowEac3 = false, allowTrueHd = false, allowDts = false, allowDtsHd = false
        )
        val sink = PlaybackSpeedAwareAudioSink(sink = PlainSink(), passthroughPolicy = denyAll)
        val trueHd = format(MimeTypes.AUDIO_TRUEHD)
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(trueHd))
        assertTrue(sink.hbrDemandsNonTunnelledVideo(trueHd))
        assertTrue(sink.setPassthroughPolicy(AudioPassthroughPolicy.ALLOW_ALL))
        assertTrue(sink.hbrDemandsNonTunnelledVideo(trueHd))
    }

    // The IEC rule is untouched: without an IEC sink underneath it stays false.
    @Test
    fun iecRule_isUnchanged() {
        val sink = PlaybackSpeedAwareAudioSink(sink = PlainSink())
        assertFalse(sink.demandsNonTunnelledVideo(format(MimeTypes.AUDIO_TRUEHD)))
        assertFalse(sink.demandsNonTunnelledVideo(format(MimeTypes.AUDIO_AC3)))
    }

    private fun format(mime: String): Format = Format.Builder()
        .setSampleMimeType(mime)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .build()

    private class PlainSink : AudioSink {
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            buffer.position(buffer.limit())
            return true
        }
        override fun playToEndOfStream() = Unit
        override fun isEnded(): Boolean = false
        override fun hasPendingData(): Boolean = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
        override fun getSkipSilenceEnabled(): Boolean = false
        override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) = Unit
        override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) = Unit
        override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit
        override fun enableTunnelingV21() = Unit
        override fun disableTunneling() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun reset() = Unit
        override fun release() = Unit
    }

    private companion object {
        // Everything isHbrPassthrough() accepts: the four media3 constants plus the two prefixes it matches.
        val HBR_MIMES = listOf(
            MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_X,
            MimeTypes.AUDIO_DTS_EXPRESS,
            "audio/vnd.dts.hd",
            "audio/vnd.dts.uhd"
        )
        val NON_HBR_MIMES = listOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_RAW
        )
    }
}
