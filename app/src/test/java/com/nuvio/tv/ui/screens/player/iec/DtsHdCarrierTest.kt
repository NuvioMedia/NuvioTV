package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DtsHdCarrierTest {

    @Test
    fun carrier_followsTheFirstComponentOfTheExtensionSubstream() {
        assertEquals(8, carrierOf(unit(XLL)))
        assertEquals(2, carrierOf(unit(XBR)))
        assertEquals(2, carrierOf(unit(XXCH)))
        assertEquals(2, carrierOf(unit(X96)))
        assertEquals(2, carrierOf(unit(XCH)))
        assertNull(carrierOf(unit(LBR)))
    }

    @Test
    fun carrier_readsTheLongHeaderSizeField() {
        assertEquals(8, carrierOf(unit(XLL, headerSize = 300, longSizeFields = true)))
        assertEquals(2, carrierOf(unit(XBR, headerSize = 300, longSizeFields = true)))
    }

    @Test
    fun carrier_findsTheSubstreamAfterACore() {
        val core = ByteArray(2012).also {
            it[0] = 0x7F
            it[1] = 0xFE.toByte()
            it[2] = 0x80.toByte()
            it[3] = 0x01
        }
        assertEquals(8, carrierOf(core + unit(XLL)))
        assertEquals(2, carrierOf(core + unit(XBR)))
    }

    @Test
    fun carrier_isUnknownWithoutASubstreamOrAKnownComponent() {
        assertNull(carrierOf(ByteArray(64)))
        assertNull(carrierOf(unit(byteArrayOf(0, 0, 0, 0))))
        assertNull(carrierOf(unit(XLL).copyOf(20)))
    }

    @Test
    fun carrier_ignoresTheLosslessSyncAwayFromTheComponentStart() {
        val highResolution = unit(XBR)
        XLL.copyInto(highResolution, highResolution.size - 8)
        assertEquals(2, carrierOf(highResolution))
    }

    @Test
    fun carrier_leavesTheBufferPositionAlone() {
        val buffer = ByteBuffer.wrap(unit(XBR))
        Iec61937Packer.dtsHdCarrierChannels(buffer)
        assertEquals(0, buffer.position())
    }

    @Test
    fun surroundHighResolution_movesToTwoChannelsBeforeAnythingIsWritten() {
        val factory = Tracks()
        val sink = sink(factory, dtsHd(channels = 6))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XBR)), 0L, 1))
        assertEquals(listOf(8, 2), factory.opened)
        assertEquals(0, factory.tracks[0].written)
        assertEquals(2048 * 4, factory.tracks[1].written)
    }

    @Test
    fun express_keepsItsFirstTrack() {
        val factory = Tracks()
        val sink = sink(factory, dtsHd(channels = 6))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(LBR)), 0L, 1))
        assertEquals(listOf(8), factory.opened)
    }

    @Test
    fun stereoLossless_movesToEightChannels() {
        val factory = Tracks()
        val sink = sink(factory, dtsHd(channels = 2))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XLL)), 0L, 1))
        assertEquals(listOf(2, 8), factory.opened)
        assertEquals(8192 * 4, factory.tracks[1].written)
    }

    @Test
    fun surroundLossless_keepsItsFirstTrack() {
        val factory = Tracks()
        val sink = sink(factory, dtsHd(channels = 8))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XLL)), 0L, 1))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(byteArrayOf(0, 0, 0, 0))), 10_667L, 1))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XXCH)), 21_333L, 1))
        assertEquals(listOf(8), factory.opened)
    }

    @Test
    fun aLosslessUnitLaterMovesAHighResolutionGuessUp_andNeverBack() {
        val factory = Tracks()
        val sink = sink(factory, dtsHd(channels = 6))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XBR)), 0L, 1))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XLL)), 10_667L, 1))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XBR)), 21_333L, 1))
        assertEquals(listOf(8, 2, 8), factory.opened)
    }

    @Test
    fun aRefusedTwoChannelTrack_staysOnEightInsteadOfLeavingIec() {
        val factory = Tracks(refuse = setOf(2))
        val sink = sink(factory, dtsHd(channels = 6))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XBR)), 0L, 1))
        assertEquals(listOf(8, 8), factory.opened)
        assertTrue(sink.isIecActive)
        assertEquals(8192 * 4, factory.tracks[1].written)
    }

    @Test
    fun unitsThatDoNotSay_keepTheChannelCountChoice() {
        val factory = Tracks()
        val sink = sink(factory, dtsHd(channels = 6))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(ByteArray(64)), 0L, 1))
        assertEquals(listOf(8), factory.opened)
    }

    @Test
    fun dtsUhd_isLeftOnItsChannelCountChoice() {
        val factory = Tracks()
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_DTS_X)
            .setChannelCount(6)
            .setSampleRate(48_000)
            .build()
        val sink = sink(factory, format)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XBR)), 0L, 1))
        assertEquals(listOf(8), factory.opened)
    }

    @Test
    fun aNewFormat_decidesAgain() {
        val factory = Tracks()
        val sink = sink(factory, dtsHd(channels = 6))
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XBR)), 0L, 1))
        sink.configure(dtsHd(channels = 8), 0, null)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit(XLL)), 0L, 1))
        assertEquals(listOf(8, 2, 8), factory.opened)
    }

    private fun carrierOf(accessUnit: ByteArray): Int? =
        Iec61937Packer.dtsHdCarrierChannels(ByteBuffer.wrap(accessUnit))

    private fun unit(
        component: ByteArray,
        headerSize: Int = 32,
        longSizeFields: Boolean = false
    ): ByteArray {
        val data = ByteArray(headerSize + 64)
        byteArrayOf(0x64, 0x58, 0x20, 0x25).copyInto(data)
        val field = headerSize - 1
        if (longSizeFields) {
            data[5] = (0x20 or (field shr 7)).toByte()
            data[6] = ((field and 0x7F) shl 1).toByte()
        } else {
            data[5] = (field shr 3).toByte()
            data[6] = ((field and 0x07) shl 5).toByte()
        }
        component.copyInto(data, headerSize)
        return data
    }

    private fun dtsHd(channels: Int): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
        .setChannelCount(channels)
        .setSampleRate(48_000)
        .build()

    private fun sink(factory: Tracks, format: Format): IecPassthroughAudioSink {
        val sink = IecPassthroughAudioSink(sink = UnusedSink(), trackFactory = factory)
        sink.configure(format, 0, null)
        sink.play()
        return sink
    }

    private class Tracks(private val refuse: Set<Int> = emptySet()) : IecAudioTrackFactory {
        val opened = mutableListOf<Int>()
        val tracks = mutableListOf<RecordingTrack>()

        override fun open(
            sampleRate: Int,
            channelCount: Int,
            bufferSizeBytes: Int,
            sessionId: Int
        ): IecAudioTrack? {
            if (channelCount in refuse) return null
            opened.add(channelCount)
            return RecordingTrack(channelCount).also { tracks.add(it) }
        }

        override fun iec61937Ready(): Boolean = true
    }

    private class RecordingTrack(channels: Int) : IecAudioTrack {
        override val sampleRate: Int = 192_000
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

    private class UnusedSink : AudioSink {
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
        val XLL = byteArrayOf(0x41, 0xA2.toByte(), 0x95.toByte(), 0x47)
        val XBR = byteArrayOf(0x65, 0x5E, 0x31, 0x5E)
        val XXCH = byteArrayOf(0x47, 0x00, 0x4A, 0x03)
        val X96 = byteArrayOf(0x1D, 0x95.toByte(), 0xF2.toByte(), 0x62)
        val XCH = byteArrayOf(0x5A, 0x5A, 0x5A, 0x5A)
        val LBR = byteArrayOf(0x0A, 0x80.toByte(), 0x19, 0x21)
    }
}
