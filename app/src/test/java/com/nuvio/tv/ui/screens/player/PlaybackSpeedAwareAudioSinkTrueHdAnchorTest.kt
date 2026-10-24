package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.ui.screens.player.iec.HbrPayload
import com.nuvio.tv.ui.screens.player.iec.IecAudioTrack
import com.nuvio.tv.ui.screens.player.iec.IecAudioTrackFactory
import com.nuvio.tv.ui.screens.player.iec.IecPassthroughAudioSink
import com.nuvio.tv.ui.screens.player.iec.TrueHdMatPackerTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PlaybackSpeedAwareAudioSinkTrueHdAnchorTest {

    @Test
    fun trueHd_afterFlush_unsyncedChunksThenSync_reanchorsOnce() {
        val inner = CountingSink()
        val events = mutableListOf<String>()
        val sink = PlaybackSpeedAwareAudioSink(sink = inner, onDiagnosticEvent = { events.add(it) })
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        sink.flush()

        sink.handleBuffer(chunk(major = false), 1_000_000L, 16)
        sink.handleBuffer(chunk(major = false), 1_013_333L, 16)
        assertEquals(0, inner.discontinuities)
        sink.handleBuffer(chunk(major = true), 1_026_666L, 16)
        assertEquals(1, inner.discontinuities)

        val anchor = events.single { it.startsWith("forward_anchor ") }
        assertTrue(anchor, anchor.contains("droppedChunks=2"))
        assertTrue(anchor, anchor.contains("deltaUs=26666"))
        assertTrue(anchor, anchor.contains("resynced=true"))

        sink.handleBuffer(chunk(major = false), 1_040_000L, 16)
        assertEquals(1, inner.discontinuities)
        assertEquals(1, events.count { it.startsWith("forward_anchor ") })
    }

    @Test
    fun trueHd_afterFlush_firstChunkSynced_doesNotReanchor() {
        val inner = CountingSink()
        val events = mutableListOf<String>()
        val sink = PlaybackSpeedAwareAudioSink(sink = inner, onDiagnosticEvent = { events.add(it) })
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        sink.flush()

        sink.handleBuffer(chunk(major = true), 2_000_000L, 16)
        sink.handleBuffer(chunk(major = false), 2_013_333L, 16)
        assertEquals(0, inner.discontinuities)
        val anchor = events.single { it.startsWith("forward_anchor ") }
        assertTrue(anchor, anchor.contains("droppedChunks=0"))
        assertTrue(anchor, anchor.contains("resynced=false"))
    }

    @Test
    fun refusedBuffer_offeredAgain_isEvaluatedOnce() {
        val inner = CountingSink()
        val events = mutableListOf<String>()
        val sink = PlaybackSpeedAwareAudioSink(sink = inner, onDiagnosticEvent = { events.add(it) })
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        sink.flush()

        val unsynced = chunk(major = false)
        sink.handleBuffer(unsynced, 3_000_000L, 16)
        unsynced.rewind()
        sink.handleBuffer(unsynced, 3_000_000L, 16)
        sink.handleBuffer(chunk(major = true), 3_013_333L, 16)
        val anchor = events.single { it.startsWith("forward_anchor ") }
        assertTrue(anchor, anchor.contains("droppedChunks=1"))
    }

    @Test
    fun iecFallbackMidTitle_rearmsWatcherAndForcesResyncOnFirstSyncedBuffer() {
        val inner = CountingSink()
        val events = mutableListOf<String>()
        val iecSink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> ScriptedIecTrack() },
            onDiagnosticEvent = { events.add(it) }
        )
        val sink = PlaybackSpeedAwareAudioSink(sink = iecSink, onDiagnosticEvent = { events.add(it) })
        sink.configure(trueHdFormat(), 0, null)
        assertTrue(iecSink.isIecActive)
        sink.play()
        assertTrue(events.none { it.startsWith("forward_anchor ") })

        var pts = 0L
        for (i in 0 until 48) {
            if (events.any { it.startsWith("iec_fallback_to_raw ") }) break
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            sink.handleBuffer(ByteBuffer.wrap(au), pts, 1)
            pts += 833L
        }
        assertTrue(events.any { it.startsWith("iec_fallback_to_raw ") })
        assertTrue(!iecSink.isIecActive)
        assertEquals(0, inner.discontinuities)

        sink.handleBuffer(chunk(major = true), 5_000_000L, 16)
        assertEquals(1, inner.discontinuities)
        val anchor = events.single { it.startsWith("forward_anchor ") }
        assertTrue(anchor, anchor.contains("armedBy=fallback"))
        assertTrue(anchor, anchor.contains("resynced=true"))

        sink.handleBuffer(chunk(major = false), 5_013_333L, 16)
        assertEquals(1, inner.discontinuities)
        sink.flush()
        sink.handleBuffer(chunk(major = true), 6_000_000L, 16)
        assertEquals(1, inner.discontinuities)
        val second = events.filter { it.startsWith("forward_anchor ") }.last()
        assertTrue(second, second.contains("armedBy=flush"))
        assertTrue(second, second.contains("resynced=false"))
    }

    @Test
    fun iecFallbackAtEndOfStream_marksPacerRawAndArmsAfterTheNextFlush() {
        val inner = CountingSink()
        val events = mutableListOf<String>()
        val track = ScriptedIecTrack(writeResult = null)
        val iecSink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> track },
            onDiagnosticEvent = { events.add(it) }
        )
        val sink = PlaybackSpeedAwareAudioSink(sink = iecSink, onDiagnosticEvent = { events.add(it) })
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var pts = 0L
        for (i in 0 until 48) {
            if (i == 16) track.writeResult = 0
            sink.handleBuffer(ByteBuffer.wrap(TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)), pts, 1)
            pts += 833L
        }
        assertTrue(iecSink.isIecActive)
        assertTrue(events.none { it.startsWith("iec_fallback_to_raw ") })
        track.writeResult = -1
        sink.playToEndOfStream()
        assertTrue(!iecSink.isIecActive)
        assertTrue(events.any { it.startsWith("iec_fallback_to_raw ") })
        sink.flush()
        sink.handleBuffer(chunk(major = false), 9_000_000L, 16)
        sink.handleBuffer(chunk(major = true), 9_013_333L, 16)
        assertEquals(1, inner.discontinuities)
        val anchor = events.single { it.startsWith("forward_anchor ") }
        assertTrue(anchor, anchor.contains("armedBy=flush"))
        assertTrue(anchor, anchor.contains("droppedChunks=1"))
    }

    @Test
    fun nonTrueHd_isNeverEvaluated() {
        val inner = CountingSink()
        val events = mutableListOf<String>()
        val sink = PlaybackSpeedAwareAudioSink(sink = inner, onDiagnosticEvent = { events.add(it) })
        sink.configure(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setChannelCount(6).setSampleRate(48_000).build(),
            0,
            null
        )
        sink.play()
        sink.flush()
        sink.handleBuffer(ByteBuffer.allocate(64), 1_000_000L, 1)
        sink.handleBuffer(ByteBuffer.allocate(64), 1_010_000L, 1)
        assertEquals(0, inner.discontinuities)
        assertTrue(events.none { it.startsWith("forward_anchor ") })
    }

    private fun trueHdFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
        .setChannelCount(8)
        .setSampleRate(48_000)
        .build()

    private fun chunk(major: Boolean): ByteBuffer {
        val buf = ByteBuffer.allocate(40 * 16)
        for (i in 0 until 16) {
            buf.put(TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = major && i == 5))
        }
        buf.flip()
        return buf
    }

    private class ScriptedIecTrack(var writeResult: Int? = -1) : IecAudioTrack {
        override val sampleRate: Int = 192_000
        override val frameSizeBytes: Int = 16
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        override val bufferSizeBytes: Int = sampleRate * frameSizeBytes
        override fun write(data: ByteArray, offset: Int, size: Int): Int = writeResult ?: size
        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun playbackHeadFrames(): Long = 0L
        override fun setVolume(volume: Float) = Unit
        override fun underrunCount(): Int = 0
    }

    private class CountingSink : AudioSink {
        var discontinuities: Int = 0
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
        override fun handleDiscontinuity() {
            discontinuities++
        }
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
