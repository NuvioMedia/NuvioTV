package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class SurroundResolveInPlaceTest {

    // A chain snapshot taken while HDMI is down answers false to every encoding.
    @Test
    fun snapshot_deniesEveryEncoding_onlyWhenAllFiveAreFalse() {
        val allFalse = DirectSupport(ac3 = false, eac3 = false, trueHd = false, dts = false, dtsHd = false)
        assertTrue(AudioChainProbe.ChainSnapshot(direct = allFalse, maxPcmChannels = 8).deniesEveryEncoding())
        assertTrue(AudioChainProbe.ChainSnapshot(direct = allFalse, maxPcmChannels = null).deniesEveryEncoding())

        val onlyAc3 = allFalse.copy(ac3 = true)
        assertFalse(AudioChainProbe.ChainSnapshot(direct = onlyAc3, maxPcmChannels = 8).deniesEveryEncoding())
        val onlyTrueHd = allFalse.copy(trueHd = true)
        assertFalse(AudioChainProbe.ChainSnapshot(direct = onlyTrueHd, maxPcmChannels = 8).deniesEveryEncoding())

        // No direct-support answer at all (pre-Q) is not a denial.
        assertFalse(AudioChainProbe.ChainSnapshot(direct = null, maxPcmChannels = 2).deniesEveryEncoding())
    }

    // The policy handed to the sink at build can be replaced in place, and the sink's answer
    // for a passthrough format follows it without a rebuild.
    @Test
    fun setPassthroughPolicy_changesFormatSupport_andReportsWhetherItChanged() {
        val denyAll = AudioPassthroughPolicy(
            allowAc3 = false, allowEac3 = false, allowTrueHd = false, allowDts = false, allowDtsHd = false
        )
        val sink = PlaybackSpeedAwareAudioSink(sink = PlainSink(), passthroughPolicy = denyAll)
        val trueHd = format(MimeTypes.AUDIO_TRUEHD)
        val eac3 = format(MimeTypes.AUDIO_E_AC3)
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(trueHd))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(eac3))

        assertTrue(sink.setPassthroughPolicy(AudioPassthroughPolicy.ALLOW_ALL))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(trueHd))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(eac3))

        // Same policy again: nothing to do, and the caller should not nudge the player.
        assertFalse(sink.setPassthroughPolicy(AudioPassthroughPolicy.ALLOW_ALL))
        assertFalse(sink.setPassthroughPolicy(AudioPassthroughPolicy()))

        // Back to a denial: the sink answers unsupported again.
        assertTrue(sink.setPassthroughPolicy(denyAll.copy(allowEac3 = true)))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(trueHd))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(eac3))
    }

    // PCM is never a passthrough candidate, so the policy does not touch it either way.
    @Test
    fun setPassthroughPolicy_leavesPcmAlone() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = PlainSink(),
            passthroughPolicy = AudioPassthroughPolicy(allowAc3 = false, allowEac3 = false, allowTrueHd = false, allowDts = false, allowDtsHd = false)
        )
        val pcm = format(MimeTypes.AUDIO_RAW)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm))
        sink.setPassthroughPolicy(AudioPassthroughPolicy.ALLOW_ALL)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm))
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
}
