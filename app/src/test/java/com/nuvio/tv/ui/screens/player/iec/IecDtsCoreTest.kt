package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IecDtsCoreTest {

    @Before
    fun setUp() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @After
    fun tearDown() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @Test
    fun packer_wrapsABigEndianFrameWithItsLengthInBits() {
        val frame = coreFrame(samples = 512, size = 2012)
        val out = ByteArray(2048)
        assertTrue(Iec61937Packer.packDtsCoreInto(ByteBuffer.wrap(frame), 512, false, out))
        val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Iec61937Packer.PREAMBLE1, header.getShort(0))
        assertEquals(Iec61937Packer.PREAMBLE2, header.getShort(2))
        assertEquals(Iec61937Packer.TYPE_DTS1.toShort(), header.getShort(4))
        assertEquals((2012 * 8).toShort(), header.getShort(6))
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0x7F, 0x01, 0x80.toByte()), out.copyOfRange(8, 12))
        assertTrue(out.copyOfRange(8 + 2012, 2048).all { it == 0.toByte() })
    }

    @Test
    fun packer_burstTypeFollowsTheSampleCount() {
        assertEquals(Iec61937Packer.TYPE_DTS2, Iec61937Packer.dtsCoreBurstType(1024))
        assertEquals(Iec61937Packer.TYPE_DTS3, Iec61937Packer.dtsCoreBurstType(2048))
        assertEquals(8192, Iec61937Packer.dtsCoreBurstBytes(2048))
        assertNull(Iec61937Packer.dtsCoreBurstType(256))
        assertEquals(0, Iec61937Packer.dtsCoreBurstBytes(256))
    }

    @Test
    fun packer_aFrameThatFillsTheBurstGoesWithoutAHeader() {
        val frame = coreFrame(samples = 512, size = 2048)
        val out = ByteArray(2048)
        assertTrue(Iec61937Packer.packDtsCoreInto(ByteBuffer.wrap(frame), 512, false, out))
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0x7F, 0x01, 0x80.toByte()), out.copyOfRange(0, 4))
    }

    @Test
    fun packer_refusesAFrameThatLeavesNoRoomForTheHeader() {
        val out = ByteArray(2048)
        assertFalse(Iec61937Packer.packDtsCoreInto(ByteBuffer.wrap(coreFrame(512, 2044)), 512, false, out))
        assertFalse(Iec61937Packer.packDtsCoreInto(ByteBuffer.wrap(coreFrame(512, 3000)), 512, false, out))
    }

    @Test
    fun packer_littleEndianFramesAreNotSwapped() {
        val frame = swapped(coreFrame(samples = 512, size = 1000))
        val out = ByteArray(2048)
        assertTrue(Iec61937Packer.packDtsCoreInto(ByteBuffer.wrap(frame), 512, true, out))
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0x7F, 0x01, 0x80.toByte()), out.copyOfRange(8, 12))
    }

    @Test
    fun syncWordOrder() {
        assertEquals(false, IecPassthroughAudioSink.dtsCoreLittleEndian(coreFrame(512, 64)))
        assertEquals(true, IecPassthroughAudioSink.dtsCoreLittleEndian(swapped(coreFrame(512, 64))))
        assertNull(IecPassthroughAudioSink.dtsCoreLittleEndian(byteArrayOf(0x1F, 0xFF.toByte(), 0xE8.toByte(), 0x00)))
        assertNull(IecPassthroughAudioSink.dtsCoreLittleEndian(byteArrayOf(0x7F)))
    }

    @Test
    fun plainDts_goesOnTheOwnTrackAtItsOwnRate() {
        val factory = Tracks()
        val inner = RecordingSink()
        val sink = sink(factory, inner, dts(48_000))
        assertTrue(sink.isIecActive)
        assertEquals(listOf(48_000 to 2), factory.opened)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(coreFrame(512, 2012)), 0L, 1))
        assertEquals(2048, factory.tracks.single().written)
        assertTrue(inner.configured.isEmpty())
    }

    @Test
    fun plainDts_at44k1_opensAt44k1() {
        val factory = Tracks()
        sink(factory, RecordingSink(), dts(44_100))
        assertEquals(listOf(44_100 to 2), factory.opened)
    }

    @Test
    fun plainDts_staysOnAndroidsOutputWhereAndroidRefusesDts() {
        val factory = Tracks()
        val sink = sink(factory, RecordingSink(supported = false), dts(48_000))
        assertFalse(sink.isIecActive)
        assertTrue(factory.opened.isEmpty())
    }

    @Test
    fun plainDts_staysOnAndroidsOutputWithSystemPassthroughOrTunnelling() {
        val factory = Tracks()
        assertFalse(sink(factory, RecordingSink(), dts(48_000), hbrIecEnabled = false).isIecActive)
        val tunnelled = IecPassthroughAudioSink(sink = RecordingSink(), trackFactory = factory)
        tunnelled.enableTunnelingV21()
        tunnelled.configure(dts(48_000), 0, null)
        assertFalse(tunnelled.isIecActive)
        assertTrue(factory.opened.isEmpty())
    }

    @Test
    fun plainDts_waitsForTheIecProbe() {
        val factory = Tracks(ready = false)
        assertFalse(sink(factory, RecordingSink(), dts(48_000)).isIecActive)
    }

    @Test
    fun aFrameIecCannotCarry_movesThatStreamToAndroidsOutput_andTrueHdKeepsIec() {
        val factory = Tracks()
        val inner = RecordingSink()
        val sink = sink(factory, inner, dts(48_000, channels = 6))
        val fourteenBit = byteArrayOf(0x1F, 0xFF.toByte(), 0xE8.toByte(), 0x00, 0x07, 0xF0.toByte(), 0, 0) + ByteArray(56)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(fourteenBit), 0L, 1))
        assertFalse(sink.isIecActive)
        assertEquals(6, inner.configured.single().channelCount)
        assertEquals(1, inner.buffers)
        assertTrue(factory.ready)

        sink.configure(dts(48_000), 0, null)
        assertFalse(sink.isIecActive)

        sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).setChannelCount(8).setSampleRate(48_000).build(), 0, null)
        assertTrue(sink.isIecActive)
    }

    @Test
    fun anOversizedFrame_alsoFallsBack() {
        val factory = Tracks()
        val inner = RecordingSink()
        val sink = sink(factory, inner, dts(48_000))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(coreFrame(512, 2046)), 0L, 1))
        assertFalse(sink.isIecActive)
        assertEquals(1, inner.buffers)
    }

    private fun sink(
        factory: Tracks,
        inner: RecordingSink,
        format: Format,
        hbrIecEnabled: Boolean = true
    ): IecPassthroughAudioSink {
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = factory, hbrIecEnabled = hbrIecEnabled)
        sink.configure(format, 0, null)
        sink.play()
        return sink
    }

    private fun dts(sampleRate: Int, channels: Int = 6): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS)
        .setChannelCount(channels)
        .setSampleRate(sampleRate)
        .build()

    /** A big-endian core frame whose header says [samples] samples. */
    private fun coreFrame(samples: Int, size: Int): ByteArray {
        val frame = ByteArray(size)
        byteArrayOf(0x7F, 0xFE.toByte(), 0x80.toByte(), 0x01).copyInto(frame)
        val blocks = samples / 32 - 1
        frame[4] = (0x80 or (blocks shr 6)).toByte()
        frame[5] = ((blocks and 0x3F) shl 2).toByte()
        for (i in 16 until size) frame[i] = (i and 0x7F).toByte()
        return frame
    }

    private fun swapped(frame: ByteArray): ByteArray {
        val out = frame.copyOf()
        var i = 0
        while (i + 1 < out.size) {
            val t = out[i]
            out[i] = out[i + 1]
            out[i + 1] = t
            i += 2
        }
        return out
    }

    private class Tracks(var ready: Boolean = true) : IecAudioTrackFactory {
        val opened = mutableListOf<Pair<Int, Int>>()
        val tracks = mutableListOf<RecordingTrack>()

        override fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int): IecAudioTrack {
            opened.add(sampleRate to channelCount)
            return RecordingTrack(sampleRate, channelCount).also { tracks.add(it) }
        }

        override fun iec61937Ready(): Boolean = ready

        override fun markIecUnusable() {
            ready = false
        }
    }

    private class RecordingTrack(override val sampleRate: Int, channels: Int) : IecAudioTrack {
        override val frameSizeBytes: Int = channels * 2
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        override val bufferSizeBytes: Int = sampleRate * frameSizeBytes
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
        override fun underrunCount(): Int = 0
    }

    private class RecordingSink(private val supported: Boolean = true) : AudioSink {
        val configured = mutableListOf<Format>()
        var buffers = 0

        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatSupport(format: Format): Int =
            if (supported) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            else AudioSink.SINK_FORMAT_UNSUPPORTED
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            configured.add(inputFormat)
        }
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
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
