package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IecKeepAliveTest {

    private val clock = longArrayOf(0L)

    @Before
    fun setUp() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @After
    fun tearDown() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @Test
    fun paddingOnlyFrame_carriesTheMatCodesAndNothingElse() {
        val frame = TrueHdMatPacker.paddingOnlyFrame()
        assertEquals(TrueHdMatPacker.MAT_BUFFER_SIZE, frame.size)
        assertRange(frame, 8, TrueHdMatPacker.MAT_START_CODE)
        assertRange(frame, 30716, TrueHdMatPacker.MAT_MIDDLE_CODE)
        assertRange(frame, TrueHdMatPacker.MAT_BUFFER_SIZE - 24, TrueHdMatPacker.MAT_END_CODE)
        val codes = (8 until 28) + (30716 until 30728) + ((TrueHdMatPacker.MAT_BUFFER_SIZE - 24) until TrueHdMatPacker.MAT_BUFFER_SIZE)
        for (i in frame.indices) {
            if (i !in codes) assertEquals("byte $i", 0, frame[i].toInt())
        }
        val wrapped = Iec61937Packer.packTrueHdInPlace(TrueHdMatPacker.paddingOnlyFrame())
        val header = ByteBuffer.wrap(wrapped).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Iec61937Packer.PREAMBLE1, header.getShort(0))
        assertEquals(Iec61937Packer.PREAMBLE2, header.getShort(2))
        assertEquals(Iec61937Packer.TYPE_TRUEHD, header.getShort(4).toInt())
    }

    @Test
    fun ledger_skipsFillerRunsAndMergesNeighbours() {
        val ledger = IecKeepAliveLedger()
        ledger.add(100, 140)
        ledger.add(140, 180)
        ledger.add(300, 320)
        assertEquals(100L, ledger.fillerFrames)
        assertEquals(50L, ledger.contentFrames(50))
        assertEquals(100L, ledger.contentFrames(100))
        assertEquals(100L, ledger.contentFrames(120))
        assertEquals(100L, ledger.contentFrames(180))
        assertEquals(200L, ledger.contentFrames(280))
        assertEquals(220L, ledger.contentFrames(310))
        assertEquals(300L, ledger.contentFrames(400))
        ledger.clear()
        assertEquals(400L, ledger.contentFrames(400))
    }

    @Test
    fun pauseAndSeek_neverPauseOrFlushTheTrack() {
        val track = HeadTrack()
        val sink = sink(track)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feed(sink, 0 until 48, ptsStartUs = 0L)
        assertTrue(sink.isKeepAliveActive)
        sink.pause()
        sink.flush()
        sink.play()
        assertEquals(0, track.pauses)
        assertEquals(0, track.flushes)
        assertTrue(track.plays >= 1)
    }

    @Test
    fun whilePaused_contentIsHeldAndFillerKeepsTheOutputAlive() {
        val track = HeadTrack()
        val events = mutableListOf<String>()
        val sink = sink(track, events)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feed(sink, 0 until 48, ptsStartUs = 0L)
        val writtenBefore = track.written
        assertEquals(0, writtenBefore % FRAME)

        sink.pause()
        track.playOut()
        for (n in 1..4) {
            sink.keepAliveTick()
            assertEquals("tick $n", writtenBefore + n * FRAME, track.written)
        }
        sink.keepAliveTick()
        assertEquals("queue sits at the low-water mark", writtenBefore + 4 * FRAME, track.written)
        assertTrue(track.writes.takeLast(4).all { it.filler })

        feed(sink, 48 until 72, ptsStartUs = 48 * AU_US, expectAccepted = false)
        assertEquals("content waits while paused", writtenBefore + 4 * FRAME, track.written)
        assertFalse(sink.hasPendingData())
        assertTrue(events.any { it.startsWith("iec_keepalive_hold reason=pause") })

        sink.play()
        feed(sink, 48 until 72, ptsStartUs = 48 * AU_US)
        assertTrue("content follows the filler", track.written > writtenBefore + 4 * FRAME)
        assertFalse(track.writes.last().filler)
    }

    @Test
    fun resumingAfterAPause_keepsTheClockInStepWithTheContent() {
        val track = HeadTrack()
        val sink = sink(track)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feed(sink, 0 until 48, ptsStartUs = 0L)
        track.playOut()
        val paused = sink.getCurrentPositionUs(false)
        assertEquals(track.written / 16L * 1_000_000L / 192_000L, paused)

        sink.pause()
        repeat(10) { sink.keepAliveTick() }
        val fillerBytes = track.writes.filter { it.filler }.sumOf { it.bytes }
        assertTrue(fillerBytes >= 4 * FRAME)
        track.playOut()
        assertEquals("clock holds while filler plays", paused, sink.getCurrentPositionUs(false))

        sink.play()
        feed(sink, 48 until 96, ptsStartUs = 48 * AU_US)
        track.playOut()
        val contentBytes = track.writes.filter { !it.filler }.sumOf { it.bytes }
        assertEquals(contentBytes / 16L * 1_000_000L / 192_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun seekWhilePaused_newContentStartsAfterTheFillerWithItsOwnClock() {
        val track = HeadTrack()
        val events = mutableListOf<String>()
        val sink = sink(track, events)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feed(sink, 0 until 48, ptsStartUs = 0L)
        track.playHead = track.written - 2 * FRAME
        sink.pause()
        sink.flush()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET.toLong(), sink.getCurrentPositionUs(false))
        repeat(6) { sink.keepAliveTick() }
        sink.play()

        val seekPts = 5_000_000L
        feed(sink, 0 until 72, ptsStartUs = seekPts)
        assertTrue(events.any { it.startsWith("iec_keepalive_hold reason=flush") })
        val newContentStart = track.writes.indexOfLast { it.filler }.let { last ->
            track.writes.take(last + 1).sumOf { it.bytes }
        }
        track.playHead = newContentStart - FRAME
        assertEquals("old tail and filler still playing", seekPts, sink.getCurrentPositionUs(false))
        clock[0] += 40_000_000L
        track.playHead = newContentStart + 2 * FRAME
        assertEquals(seekPts + 2L * FRAME / 16L * 1_000_000L / 192_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun whilePlaying_contentStopsAtTheCapAndFillerCoversAStall() {
        val track = HeadTrack()
        val sink = sink(track)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        val capBytes = IecPassthroughAudioSink.KEEP_ALIVE_CAP_MS * 192_000L / 1000L * 16L
        var pts = 0L
        var au = 0
        var refused = false
        while (au < 400 && !refused) {
            refused = !sink.handleBuffer(ByteBuffer.wrap(TrueHdMatPackerTest.trueHdAu(au * 40, au == 0)), pts, 1)
            pts += AU_US
            au++
        }
        assertTrue("sink refuses once the queue is full", refused)
        assertTrue(track.written <= capBytes + FRAME)
        assertTrue(track.written >= capBytes - FRAME)

        sink.keepAliveTick()
        assertFalse("enough queued, no filler", track.writes.last().filler)
        track.playHead = track.written - FRAME
        sink.keepAliveTick()
        assertTrue("the playback thread is late, filler covers the gap", track.writes.last().filler)
    }

    @Test
    fun keepAliveOff_pausesAndFlushesTheTrackAsBefore() {
        val track = HeadTrack()
        val sink = sink(track, keepAlive = false)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feed(sink, 0 until 48, ptsStartUs = 0L)
        assertFalse(sink.isKeepAliveActive)
        sink.pause()
        assertEquals(1, track.pauses)
        sink.flush()
        assertEquals(1, track.flushes)
        sink.keepAliveTick()
        assertTrue(track.writes.none { it.filler })
    }

    @Test
    fun dtsHd_keepsTheOldPauseAndFlush() {
        val track = HeadTrack()
        val sink = sink(track)
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()
        assertFalse(sink.isKeepAliveActive)
        sink.pause()
        assertEquals(1, track.pauses)
        sink.flush()
        assertEquals(1, track.flushes)
    }

    @Test
    fun aVeryLongPause_releasesTheOutputOnceAndResumePlaysAgain() {
        val track = HeadTrack()
        val sink = sink(track)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feed(sink, 0 until 48, ptsStartUs = 0L)
        sink.pause()
        clock[0] += IecPassthroughAudioSink.KEEP_ALIVE_MAX_HOLD_NANOS - 1L
        sink.keepAliveTick()
        assertEquals(0, track.pauses)
        clock[0] += 1L
        sink.keepAliveTick()
        assertEquals(1, track.pauses)
        val writtenAtRelease = track.written
        sink.keepAliveTick()
        assertEquals("no filler after the release", writtenAtRelease, track.written)
        val playsBefore = track.plays
        sink.play()
        assertTrue(track.plays > playsBefore)
        sink.pause()
        sink.keepAliveTick()
        assertTrue("a new pause keeps the output alive again", track.written > writtenAtRelease)
    }

    private fun sink(
        track: HeadTrack,
        events: MutableList<String> = mutableListOf(),
        keepAlive: Boolean = true
    ): IecPassthroughAudioSink = IecPassthroughAudioSink(
        sink = RecordingSink(),
        trackFactory = object : IecAudioTrackFactory {
            override fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int) = track
            override fun iec61937Ready(): Boolean = true
        },
        nanoTime = { clock[0] },
        onDiagnosticEvent = { events.add(it) },
        keepAliveEnabled = keepAlive,
        keepAliveThreadEnabled = false
    )

    private fun feed(
        sink: IecPassthroughAudioSink,
        units: IntRange,
        ptsStartUs: Long,
        expectAccepted: Boolean? = true
    ) {
        var pts = ptsStartUs
        for (i in units) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = (i * 40) and 0xFFFF, major = i == units.first)
            val accepted = sink.handleBuffer(ByteBuffer.wrap(au), pts, 1)
            if (expectAccepted != null) assertEquals("unit $i", expectAccepted, accepted)
            pts += AU_US
        }
    }

    private fun assertRange(data: ByteArray, offset: Int, expected: ByteArray) {
        for (i in expected.indices) assertEquals("offset ${offset + i}", expected[i], data[offset + i])
    }

    private fun trueHdFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
        .setChannelCount(8)
        .setSampleRate(48_000)
        .build()

    private fun dtsHdFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
        .setChannelCount(8)
        .setSampleRate(48_000)
        .build()

    private class Write(val bytes: Int, val filler: Boolean)

    /** A track whose playback head only moves when the test says so. */
    private class HeadTrack : IecAudioTrack {
        override val sampleRate: Int = 192_000
        override val frameSizeBytes: Int = 16
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        override val bufferSizeBytes: Int = 4 * 1024 * 1024
        var written: Int = 0
        var playHead: Int = 0
        var plays = 0
        var pauses = 0
        var flushes = 0
        val writes = mutableListOf<Write>()
        private val fillerSignature = Iec61937Packer.packTrueHdInPlace(TrueHdMatPacker.paddingOnlyFrame())

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            val filler = size == fillerSignature.size && data.contentEquals(fillerSignature)
            writes.add(Write(size, filler))
            written += size
            return size
        }

        fun playOut() {
            playHead = written
        }

        override fun play() {
            plays++
        }

        override fun pause() {
            pauses++
        }

        override fun flush() {
            flushes++
        }

        override fun release() = Unit
        override fun playbackHeadFrames(): Long = (playHead / frameSizeBytes).toLong()
        override fun setVolume(volume: Float) = Unit
        override fun underrunCount(): Int = 0
    }

    private class RecordingSink : AudioSink {
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
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            buffer.position(buffer.limit())
            return true
        }
        override fun playToEndOfStream() = Unit
        override fun isEnded(): Boolean = true
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
        const val FRAME = Iec61937Packer.TRUEHD_IEC_SIZE
        const val AU_US = 833L
    }
}
