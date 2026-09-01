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

    private fun dtsHdFormat(): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
            .setChannelCount(8)
            .setSampleRate(48_000)
            .build()
    }

    @Test
    fun dtsHd_writeError_fallsBackToWrappedSink() {
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16, fixedWriteResult = -2))
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
        assertEquals(0, inner.buffers)

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 100L, 1))
        assertEquals(1, inner.buffers)
    }

    @Test
    fun dtsHd_stalledWrites_fallBackAfterStallLimit() {
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16, fixedWriteResult = 0))
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        repeat(IecPassthroughAudioSink.MAX_WRITE_STALLS - 2) {
            assertFalse(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        }
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertFalse(sink.isIecActive)
        assertTrue(factory.markedUnusable)
    }

    @Test
    fun dtsHd_formatSupport_promotedWhenIecReady() {
        val promoted = IecPassthroughAudioSink(
            sink = RecordingSink(innerSupport = AudioSink.SINK_FORMAT_UNSUPPORTED),
            trackFactory = ReadyFactory(null)
        )
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, promoted.getFormatSupport(dtsHdFormat()))

        val notReady = IecPassthroughAudioSink(
            sink = RecordingSink(innerSupport = AudioSink.SINK_FORMAT_UNSUPPORTED),
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> null }
        )
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, notReady.getFormatSupport(dtsHdFormat()))
    }

    @Test
    fun discontinuity_reanchorsPlaybackHead() {
        val fakeTrack = FakeIecAudioTrack(192_000, 16)
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = ReadyFactory(fakeTrack))
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        repeat(10) { i ->
            assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), i * 10_000L, 1))
        }
        assertTrue(sink.getCurrentPositionUs(false) > 0L)

        sink.handleDiscontinuity()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET.toLong(), sink.getCurrentPositionUs(false))

        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 1_000_000L, 1))
        val afterJump = sink.getCurrentPositionUs(false)
        assertTrue(
            "position should stay near the new start PTS, was $afterJump",
            afterJump < 1_050_000L
        )
    }

    @Test
    fun dtsHd_unknownChannelCount_opensEightChannelTrack() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
            .setSampleRate(48_000)
            .build()
        sink.configure(format, 0, null)
        assertTrue(sink.isIecActive)
        assertEquals(8, factory.lastChannelCount)
    }

    @Test
    fun tunneling_opensIecTrackWithHwAvSyncAndTunnelSessionId() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.setAudioSessionId(42)
        sink.enableTunnelingV21()
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        assertTrue(factory.lastHwAvSync)
        assertEquals(42, factory.lastSessionId)
    }

    @Test
    fun noTunneling_opensIecTrackWithoutHwAvSync() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.setAudioSessionId(42)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        assertFalse(factory.lastHwAvSync)
    }

    @Test
    fun tunneling_withoutSessionId_skipsIecForWrappedRawTrack() {
        val inner = RecordingSink()
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory)
        sink.enableTunnelingV21()
        sink.configure(dtsHdFormat(), 0, null)
        assertFalse(sink.isIecActive)
        assertEquals(0, factory.openCount)
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(64), 0L, 1))
        assertEquals(1, inner.buffers)
    }

    @Test
    fun tunneling_sessionIdChange_reopensIecTrackOnNewSession() {
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        sink.setAudioSessionId(42)
        sink.enableTunnelingV21()
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        assertEquals(42, factory.lastSessionId)
        assertEquals(1, factory.openCount)

        sink.setAudioSessionId(77)
        assertTrue(sink.isIecActive)
        assertEquals(77, factory.lastSessionId)
        assertEquals(2, factory.openCount)
    }

    @Test
    fun tunneling_enableForwardedToWrappedSinkWhenIecNotActive() {
        val inner = RecordingSink()
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        )
        sink.enableTunnelingV21()
        assertTrue(inner.tunnelingEnabled)
        sink.disableTunneling()
        assertFalse(inner.tunnelingEnabled)
    }

    @Test
    fun opticalRoute_disablesHbrIec() {
        val inner = RecordingSink(innerSupport = AudioSink.SINK_FORMAT_UNSUPPORTED)
        val factory = ReadyFactory(FakeIecAudioTrack(192_000, 16))
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = factory,
            hbrIecEnabled = false
        )
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(dtsHdFormat()))
        sink.configure(dtsHdFormat(), 0, null)
        assertFalse(sink.isIecActive)
        assertEquals(0, factory.lastChannelCount)
    }

    @Test
    fun probeReadyListener_invokesCallback() {
        var captured: (() -> Unit)? = null
        val factory = object : IecAudioTrackFactory {
            override fun open(
                sampleRate: Int,
                channelCount: Int,
                bufferSizeBytes: Int,
                sessionId: Int
            ): IecAudioTrack? = null

            override fun setReadyListener(listener: (() -> Unit)?) {
                captured = listener
            }
        }
        var notified = false
        IecPassthroughAudioSink(RecordingSink(), factory) { notified = true }
        captured!!.invoke()
        assertTrue(notified)
    }

    private class ReadyFactory(private val track: IecAudioTrack?) : IecAudioTrackFactory {
        var markedUnusable = false
        var lastChannelCount: Int = 0
        var lastSessionId: Int = 0
        var lastHwAvSync: Boolean = false
        var openCount: Int = 0

        override fun open(
            sampleRate: Int,
            channelCount: Int,
            bufferSizeBytes: Int,
            sessionId: Int
        ): IecAudioTrack? {
            lastChannelCount = channelCount
            return track
        }

        override fun openHbr(
            sampleRate: Int,
            channelCount: Int,
            bufferSizeBytes: Int,
            sessionId: Int,
            trueHd: Boolean,
            hwAvSync: Boolean
        ): IecAudioTrack? {
            lastChannelCount = channelCount
            lastSessionId = sessionId
            lastHwAvSync = hwAvSync
            openCount++
            return track
        }

        override fun canOpen(sampleRate: Int, channelCount: Int): Boolean = true
        override fun iec61937Ready(): Boolean = true
        override fun markIecUnusable() {
            markedUnusable = true
        }
    }

    private class FakeIecAudioTrack(
        override val sampleRate: Int,
        override val frameSizeBytes: Int,
        override val payload: HbrPayload = HbrPayload.IEC_BURST,
        private val fixedWriteResult: Int? = null
    ) : IecAudioTrack {
        var written: Int = 0
            private set

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            if (fixedWriteResult != null) return fixedWriteResult
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

    private class RecordingSink(
        private val innerSupport: Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    ) : AudioSink {
        var buffers: Int = 0
            private set
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = innerSupport
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
        var tunnelingEnabled = false
            private set
        override fun enableTunnelingV21() {
            tunnelingEnabled = true
        }
        override fun disableTunneling() {
            tunnelingEnabled = false
        }
        override fun setVolume(volume: Float) = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun reset() = Unit
        override fun release() = Unit
    }
}
