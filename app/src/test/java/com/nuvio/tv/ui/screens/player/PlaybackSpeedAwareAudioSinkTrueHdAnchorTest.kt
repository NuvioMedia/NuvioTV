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

        // A sample-queue seek: two chunks without a major syncframe, then one with it.
        sink.handleBuffer(chunk(major = false), 1_000_000L, 16)
        sink.handleBuffer(chunk(major = false), 1_013_333L, 16)
        assertEquals(0, inner.discontinuities)
        sink.handleBuffer(chunk(major = true), 1_026_666L, 16)
        assertEquals(1, inner.discontinuities)

        val anchor = events.single { it.startsWith("forward_anchor ") }
        assertTrue(anchor, anchor.contains("droppedChunks=2"))
        assertTrue(anchor, anchor.contains("deltaUs=26666"))
        assertTrue(anchor, anchor.contains("resynced=true"))

        // Later buffers are not evaluated again.
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
        // The IEC track refuses every write, so the first MAT frame the packer emits makes the
        // IEC sink fall back to the wrapped sink from inside handleBuffer.
        val iecSink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = IecAudioTrackFactory { _, _, _, _ -> ScriptedIecTrack() },
            onDiagnosticEvent = { events.add(it) }
        )
        val sink = PlaybackSpeedAwareAudioSink(sink = iecSink, onDiagnosticEvent = { events.add(it) })
        sink.configure(trueHdFormat(), 0, null)
        assertTrue(iecSink.isIecActive)
        sink.play()
        // While IEC is active the watcher is idle: no forward_anchor for the start of play.
        assertTrue(events.none { it.startsWith("forward_anchor ") })

        // The packer emits its first MAT frame once the padding for the next unit overflows the
        // frame, about 25 units in (his packer test allows up to 48). The refused write of that
        // frame triggers the fallback; stop feeding as soon as it has.
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

        // The first buffer the wrapped sink sees after the fallback is synced: no unsynced chunk
        // was observed here, yet the wrapper must still resync because the fallback happened
        // out of its sight.
        sink.handleBuffer(chunk(major = true), 5_000_000L, 16)
        assertEquals(1, inner.discontinuities)
        val anchor = events.single { it.startsWith("forward_anchor ") }
        assertTrue(anchor, anchor.contains("armedBy=fallback"))
        assertTrue(anchor, anchor.contains("resynced=true"))

        // One-shot: later buffers are not evaluated, and a later flush arms normally again.
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
        // The first MAT frame emits about 25 units in. The track stalls (write returns 0) from
        // unit 16, so the frame stays pending instead of being written from handleBuffer, and
        // later units are refused at the leading drain; a few dozen stalls are far below the
        // fallback limit (1000).
        var pts = 0L
        for (i in 0 until 48) {
            if (i == 16) track.writeResult = 0
            sink.handleBuffer(ByteBuffer.wrap(TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)), pts, 1)
            pts += 833L
        }
        assertTrue(iecSink.isIecActive)
        assertTrue(events.none { it.startsWith("iec_fallback_to_raw ") })
        // End of stream drains the pending frame; the write now errors and the fallback fires here.
        track.writeResult = -1
        sink.playToEndOfStream()
        assertTrue(!iecSink.isIecActive)
        assertTrue(events.any { it.startsWith("iec_fallback_to_raw ") })
        // A seek after that: the flush arms the watcher the ordinary way.
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

    // Sixteen access units, the rechunker's chunk; the major sync, when present, is unit 5.
    private fun chunk(major: Boolean): ByteBuffer {
        val buf = ByteBuffer.allocate(40 * 16)
        for (i in 0 until 16) {
            buf.put(TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = major && i == 5))
        }
        buf.flip()
        return buf
    }

    /** writeResult null = accept the write; 0 = stall; negative = error. */
    private class ScriptedIecTrack(var writeResult: Int? = -1) : IecAudioTrack {
        override val sampleRate: Int = 192_000
        override val frameSizeBytes: Int = 16
        override val payload: HbrPayload = HbrPayload.IEC_BURST
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
