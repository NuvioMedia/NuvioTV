package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class IecPassthroughAudioSinkTest {

    @Test
    fun trueHd_writesIecBurstsToTrackAndReportsContentTime() {
        val fakeTrack = FakeIecAudioTrack(sampleRate = 192_000, frameSizeBytes = 16)
        val sink = IecPassthroughAudioSink(
            sink = RecordingSink(),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> fakeTrack }
        )
        sink.configure(trueHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        var pts = 0L
        for (i in 0 until 48) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            val buf = ByteBuffer.wrap(au)
            assertTrue(sink.handleBuffer(buf, pts, 1))
            pts += 833L
        }
        assertTrue(fakeTrack.written >= Iec61937Packer.TRUEHD_IEC_SIZE)
        assertEquals(0, fakeTrack.written % Iec61937Packer.TRUEHD_IEC_SIZE)
        val position = sink.getCurrentPositionUs(false)
        assertTrue("clock should advance with IEC frames, was $position", position >= 0L)
        assertTrue("clock should stay near 20 ms, was $position", position < 80_000L)
    }

    @Test
    fun pcm_isForwardedWithoutIec() {
        val sink = IecPassthroughAudioSink(RecordingSink())
        sink.configure(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setChannelCount(2)
                .setSampleRate(48_000)
                .build(),
            0,
            null
        )
        assertFalse(sink.isIecActive)
        val buf = ByteBuffer.allocate(32)
        assertTrue(sink.handleBuffer(buf, 0L, 1))
    }

    @Test
    fun trueHd_fallsBackWhenTrackFactoryReturnsNull() {
        val inner = RecordingSink()
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> null }
        )
        sink.configure(trueHdFormat(), 0, null)
        assertFalse(sink.isIecActive)
        val buf = ByteBuffer.allocate(40)
        assertTrue(sink.handleBuffer(buf, 0L, 1))
        assertEquals(1, inner.buffers)
    }

    private fun trueHdFormat(): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
            .setChannelCount(8)
            .setSampleRate(48_000)
            .build()
    }

    private class FakeIecAudioTrack(
        override val sampleRate: Int,
        override val frameSizeBytes: Int
    ) : IecAudioTrack {
        var written: Int = 0
            private set

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            written += size
            return size
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun playbackHeadFrames(): Long = (written / frameSizeBytes).toLong()
        override fun setVolume(volume: Float) = Unit
    }

    private class RecordingSink : AudioSink {
        var buffers: Int = 0
            private set
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
            buffers++
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
