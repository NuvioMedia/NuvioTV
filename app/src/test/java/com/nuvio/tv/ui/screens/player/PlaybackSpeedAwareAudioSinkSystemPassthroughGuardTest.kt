package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PlaybackSpeedAwareAudioSinkSystemPassthroughGuardTest {

    @Test
    fun systemPassthroughOn_everyHbrFormat_demandsNonTunnelledVideo() {
        val sink = PlaybackSpeedAwareAudioSink(sink = PlainSink(), systemPassthroughHbr = true)
        for (mime in HBR_MIMES) {
            assertTrue(mime, sink.systemPassthroughDemandsNonTunnelledVideo(format(mime)))
        }
    }

    @Test
    fun systemPassthroughOn_otherFormats_keepTheirTunnel() {
        val sink = PlaybackSpeedAwareAudioSink(sink = PlainSink(), systemPassthroughHbr = true)
        for (mime in NON_HBR_MIMES) {
            assertFalse(mime, sink.systemPassthroughDemandsNonTunnelledVideo(format(mime)))
        }
        assertFalse(sink.systemPassthroughDemandsNonTunnelledVideo(Format.Builder().build()))
    }

    @Test
    fun systemPassthroughOff_isTheDefault_andNeverFires() {
        val byDefault = PlaybackSpeedAwareAudioSink(sink = PlainSink())
        val explicitOff = PlaybackSpeedAwareAudioSink(sink = PlainSink(), systemPassthroughHbr = false)
        for (mime in HBR_MIMES + NON_HBR_MIMES) {
            assertFalse(mime, byDefault.systemPassthroughDemandsNonTunnelledVideo(format(mime)))
            assertFalse(mime, explicitOff.systemPassthroughDemandsNonTunnelledVideo(format(mime)))
        }
    }

    // The guard must hold whatever the sink would do with the format right now: a title decoded to
    // PCM at 1.25x goes back to passthrough at 1x without the player being rebuilt.
    @Test
    fun systemPassthroughOn_stillDemandsIt_whileTheFormatIsBeingDecodedToPcm() {
        val sink = PlaybackSpeedAwareAudioSink(sink = PlainSink(), systemPassthroughHbr = true)
        sink.setPlaybackParameters(PlaybackParameters(1.25f))
        assertTrue(sink.shouldForcePcmForFormat(format(MimeTypes.AUDIO_TRUEHD)))
        assertTrue(sink.systemPassthroughDemandsNonTunnelledVideo(format(MimeTypes.AUDIO_TRUEHD)))
    }

    // The IEC rule is untouched: without an IEC sink underneath it stays false either way.
    @Test
    fun iecRule_isIndependentOfTheSetting() {
        val on = PlaybackSpeedAwareAudioSink(sink = PlainSink(), systemPassthroughHbr = true)
        val off = PlaybackSpeedAwareAudioSink(sink = PlainSink())
        assertFalse(on.demandsNonTunnelledVideo(format(MimeTypes.AUDIO_TRUEHD)))
        assertFalse(off.demandsNonTunnelledVideo(format(MimeTypes.AUDIO_TRUEHD)))
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
        val HBR_MIMES = listOf(
            MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_X,
            MimeTypes.AUDIO_DTS_EXPRESS
        )
        val NON_HBR_MIMES = listOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_RAW
        )
    }
}
