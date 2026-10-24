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

class IecReopenAfterOutputLossTest {

    private val clock = longArrayOf(0L)
    private val events = mutableListOf<String>()
    private val failures: Int
        get() = events.count { it.startsWith("iec_fallback_to_raw") }
    private var outputReady = true
    private val checkTimes = mutableListOf<Long>()
    private val raw = RecordingSink()

    @Before
    fun setUp() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @After
    fun tearDown() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @Test
    fun deadOutput_opensOneNewTrackAndCarriesOnThere() {
        val first = DyingTrack()
        val second = DyingTrack()
        val factory = TrackList(first, second)
        val sink = sink(factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)

        first.dead = true
        au = playFor(sink, second, au, 96)

        assertEquals(2, factory.opens)
        assertTrue(first.released)
        assertTrue(sink.isIecActive)
        assertTrue(sink.isKeepAliveActive)
        assertEquals(0, failures)
        assertEquals(1, events.count { it.startsWith("iec_reopened reason=write_error code=-6") })
        assertTrue(events.none { it.startsWith("iec_fallback_to_raw") })
        assertTrue("content goes to the new track", second.contentBytes > 0)
        assertEquals(0, raw.bytes)
        assertTrue(au > 96)
    }

    @Test
    fun deadOutput_theClockRestartsFromTheFirstReplayedUnit() {
        val first = DyingTrack()
        val second = DyingTrack()
        val sink = sink(TrackList(first, second))
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)
        val lastFedPts = (au - 1) * AU_US
        first.dead = true
        val anchorsBefore = events.count { it.startsWith("iec_anchor") }
        var tries = 0
        while (events.none { it.startsWith("iec_reopened") }) {
            if (offer(sink, au)) au++
            clock[0] += 10_000_000L
            assertTrue(++tries < 400)
        }

        while (second.written == 0) {
            assertTrue(offer(sink, au++))
            assertTrue(++tries < 400)
        }
        val anchors = events.filter { it.startsWith("iec_anchor") }
        assertEquals(anchorsBefore + 1, anchors.size)
        val anchorPts = Regex("anchorPts=(\\d+)").find(anchors.last())!!.groupValues[1].toLong()
        assertTrue("anchored on a unit the old track never played", anchorPts > 0L && anchorPts <= lastFedPts + 24 * AU_US)
        assertEquals("anchored on a major sync unit", 0L, anchorPts % (24 * AU_US))
        assertEquals(anchorPts, sink.getCurrentPositionUs(false))
        second.playHead = second.written
        val playedUs = second.written / 16L * 1_000_000L / 192_000L
        clock[0] += playedUs * 1_000L
        assertEquals(anchorPts + playedUs, sink.getCurrentPositionUs(false))
    }

    @Test
    fun reopenFails_fallsBackToRawOnce() {
        val first = DyingTrack()
        val factory = TrackList(first)
        val sink = sink(factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)

        first.dead = true
        var tries = 0
        while (sink.isIecActive) {
            if (offer(sink, au)) au++
            clock[0] += 10_000_000L
            assertTrue(++tries < 400)
        }
        repeat(48) { if (offer(sink, au)) au++ }

        assertEquals(2, factory.attempts)
        assertEquals(1, failures)
        assertEquals(1, events.count { it.startsWith("iec_fallback_to_raw") })
        assertTrue(events.any { it.startsWith("iec_fallback_to_raw reason=write_error code=-6 reopen=failed") })
        assertTrue(events.none { it.startsWith("iec_reopened") })
        assertTrue("the unplayed units and the rest go to the system output", raw.bytes > 0)
    }

    @Test
    fun anotherLossSoonAfter_fallsBackInsteadOfLooping() {
        val first = DyingTrack()
        val second = DyingTrack()
        val third = DyingTrack()
        val factory = TrackList(first, second, third)
        val sink = sink(factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)
        first.dead = true
        au = playFor(sink, second, au, 96)

        second.dead = true
        var tries = 0
        while (sink.isIecActive) {
            if (offer(sink, au)) au++
            clock[0] += 10_000_000L
            assertTrue(++tries < 400)
        }

        assertEquals(2, factory.opens)
        assertFalse(third.used)
        assertEquals(1, failures)
        assertTrue(events.any { it.startsWith("iec_fallback_to_raw reason=write_error code=-6 reopen=again_too_soon") })
    }

    @Test
    fun aLaterLoss_afterTheBackOff_reopensAgain() {
        val first = DyingTrack()
        val second = DyingTrack()
        val third = DyingTrack()
        val factory = TrackList(first, second, third)
        val sink = sink(factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)
        first.dead = true
        au = playFor(sink, second, au, 96)
        clock[0] += IecPassthroughAudioSink.IEC_REOPEN_BACKOFF_NANOS
        second.dead = true
        playFor(sink, third, au, 96)

        assertEquals(3, factory.opens)
        assertEquals(2, events.count { it.startsWith("iec_reopened") })
        assertEquals(0, failures)
        assertTrue(sink.isIecActive)
    }

    @Test
    fun outputStillGone_waitsWhilePlayingThenFallsBack() {
        val first = DyingTrack()
        val second = DyingTrack()
        val factory = TrackList(first, second)
        val sink = sink(factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)
        outputReady = false
        first.dead = true
        var refusedAt = -1L
        while (sink.isIecActive) {
            if (offer(sink, au)) au++ else if (refusedAt < 0) refusedAt = clock[0]
            clock[0] += 10_000_000L
            assertTrue(clock[0] < 4 * IecPassthroughAudioSink.IEC_REOPEN_OUTPUT_WAIT_NANOS)
        }

        assertEquals(1, factory.opens)
        assertTrue(refusedAt >= 0)
        val waited = clock[0] - refusedAt
        assertTrue(waited >= IecPassthroughAudioSink.IEC_REOPEN_OUTPUT_WAIT_NANOS)
        assertTrue(waited <= IecPassthroughAudioSink.IEC_REOPEN_OUTPUT_WAIT_NANOS + 2 * IecPassthroughAudioSink.IEC_REOPEN_POLL_NANOS + 20_000_000L)
        assertTrue("the output is asked at most once per poll", checkTimes.zipWithNext().all { (a, b) -> b - a >= IecPassthroughAudioSink.IEC_REOPEN_POLL_NANOS })
        assertTrue("the first check waits one poll", checkTimes.first() - refusedAt >= IecPassthroughAudioSink.IEC_REOPEN_POLL_NANOS - 20_000_000L)
        assertEquals(1, failures)
        assertTrue(events.any { it.startsWith("iec_fallback_to_raw reason=write_error code=-6 reopen=output_not_back_ms=") })
    }

    @Test
    fun outputBackWithinTheWait_reopensOnIec() {
        val first = DyingTrack()
        val second = DyingTrack()
        val factory = TrackList(first, second)
        val sink = sink(factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)
        outputReady = false
        first.dead = true
        repeat(150) {
            if (offer(sink, au)) au++
            clock[0] += 10_000_000L
        }
        assertEquals(1, factory.opens)
        assertTrue(sink.isIecActive)

        outputReady = true
        playFor(sink, second, au, 96)
        assertEquals(2, factory.opens)
        assertEquals(0, failures)
        assertTrue(sink.isIecActive)
    }

    @Test
    fun deadOutputWhilePaused_waitsForPlayWithoutFallingBack() {
        val first = DyingTrack()
        val second = DyingTrack()
        val factory = TrackList(first, second)
        val sink = sink(factory, keepAlive = false)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        repeat(20) { assertTrue(sink.handleBuffer(dtsUnit(), it * 10_667L, 1)) }
        sink.pause()
        outputReady = false
        first.dead = true
        repeat(1_000) {
            sink.handleBuffer(dtsUnit(), 0L, 1)
            clock[0] += 50_000_000L
        }
        assertTrue("paused: no fallback however long the output is gone", sink.isIecActive)
        assertEquals(0, failures)

        outputReady = true
        clock[0] += IecPassthroughAudioSink.IEC_REOPEN_POLL_NANOS
        sink.play()
        assertTrue(sink.handleBuffer(dtsUnit(), 30 * 10_667L, 1))
        assertEquals(2, factory.opens)
        assertTrue(sink.isIecActive)
        assertTrue(second.contentBytes > 0)
        assertEquals(0, failures)
    }

    @Test
    fun deadOutputUnderAHalfWrittenFiller_reopensInsteadOfStalling() {
        val first = DyingTrack()
        val second = DyingTrack()
        val factory = TrackList(first, second)
        val sink = sink(factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)
        first.playHead = first.written
        first.dieAfterBytes = 100
        sink.keepAliveTick()
        assertTrue("the output died half way through a filler frame", first.dead)
        au = playFor(sink, second, au, 96)

        assertEquals(2, factory.opens)
        assertTrue(events.none { it.startsWith("iec_fallback_to_raw") })
        assertTrue(au > 96)
    }

    @Test
    fun otherWriteErrors_stillFallBackAtOnce() {
        val first = DyingTrack(deathCode = -3)
        val factory = TrackList(first, DyingTrack())
        val sink = sink(factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)
        first.dead = true
        var tries = 0
        while (sink.isIecActive) {
            if (offer(sink, au)) au++
            clock[0] += 10_000_000L
            assertTrue(++tries < 400)
        }
        assertEquals(1, factory.opens)
        assertTrue(events.any { it.startsWith("iec_fallback_to_raw reason=write_error code=-3 mime") })
    }

    @Test
    fun pausedTime_doesNotCountTowardsTheWait() {
        val first = DyingTrack()
        val second = DyingTrack()
        val factory = TrackList(first, second)
        val sink = sink(factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)
        outputReady = false
        first.dead = true
        repeat(300) {
            if (offer(sink, au)) au++
            clock[0] += 10_000_000L
        }
        sink.pause()
        clock[0] += 60_000_000_000L
        sink.play()
        repeat(300) {
            if (offer(sink, au)) au++
            clock[0] += 10_000_000L
        }
        assertTrue("6 s of play in two parts, neither 5 s long", sink.isIecActive)
        assertEquals(0, failures)
        repeat(300) {
            if (offer(sink, au)) au++
            clock[0] += 10_000_000L
        }
        assertFalse(sink.isIecActive)
        assertEquals(1, failures)
    }

    @Test
    fun keepAlive_stopsWritingFillerToADeadTrack() {
        val first = DyingTrack()
        val second = DyingTrack()
        val sink = sink(TrackList(first, second))
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        val au = playFor(sink, first, 0, 96)
        sink.pause()
        first.playHead = first.written
        first.headStaysWhenDead = true
        first.dead = true
        repeat(50) { sink.keepAliveTick() }
        assertEquals("one failed filler write, then quiet", 1, first.deadWrites)

        clock[0] += IecPassthroughAudioSink.IEC_REOPEN_POLL_NANOS
        sink.play()
        playFor(sink, second, au, 96)
        assertTrue(sink.isIecActive)
        assertTrue(sink.isKeepAliveActive)
        sink.pause()
        second.playHead = second.written
        val before = second.written
        sink.keepAliveTick()
        assertTrue("the new track gets filler again", second.written > before)
    }

    @Test
    fun endOfStream_onTheNewTrack_endsWithoutFallingBack() {
        val first = DyingTrack()
        val second = DyingTrack()
        val sink = sink(TrackList(first, second))
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, first, 0, 96)
        first.dead = true
        var offered = 0
        while (offer(sink, au)) {
            au++
            clock[0] += 1_000_000L
            assertTrue(++offered < 100)
        }
        sink.playToEndOfStream()
        var tries = 0
        while (!sink.isEnded()) {
            second.playHead = second.written
            clock[0] += 10_000_000L
            assertTrue(++tries < 400)
        }
        assertTrue(sink.isIecActive)
        assertEquals(0, failures)
        assertEquals(1, events.count { it.startsWith("iec_reopened") })
    }

    @Test
    fun reopenCount_isCappedPerFormat() {
        val tracks = Array(8) { DyingTrack() }
        val factory = TrackList(*tracks)
        val sink = sink(factory)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = playFor(sink, tracks[0], 0, 96)
        for (i in 0 until IecPassthroughAudioSink.MAX_REOPENS_PER_FORMAT) {
            tracks[i].dead = true
            au = playFor(sink, tracks[i + 1], au, 96)
            clock[0] += IecPassthroughAudioSink.IEC_REOPEN_BACKOFF_NANOS
        }
        assertEquals(IecPassthroughAudioSink.MAX_REOPENS_PER_FORMAT, events.count { it.startsWith("iec_reopened") })
        tracks[IecPassthroughAudioSink.MAX_REOPENS_PER_FORMAT].dead = true
        var tries = 0
        while (sink.isIecActive) {
            if (offer(sink, au)) au++
            clock[0] += 10_000_000L
            assertTrue(++tries < 400)
        }
        assertTrue(events.any { it.startsWith("iec_fallback_to_raw reason=write_error code=-6 reopen=again_too_soon") })
    }

    /** Offers [count] units, letting [track] play what it took; returns the next unit number. */
    private fun playFor(sink: IecPassthroughAudioSink, track: DyingTrack, from: Int, count: Int): Int {
        var au = from
        var tries = 0
        while (au < from + count) {
            if (!track.dead) track.playHead = track.written
            if (offer(sink, au)) au++
            clock[0] += 1_000_000L
            assertTrue("unit $au never accepted", ++tries < 2_000)
        }
        return au
    }

    private fun offer(sink: IecPassthroughAudioSink, au: Int): Boolean {
        val unit = TrueHdMatPackerTest.trueHdAu(frameTime = (au * 40) and 0xFFFF, major = au % 24 == 0)
        return sink.handleBuffer(ByteBuffer.wrap(unit), au * AU_US, 1)
    }

    private fun dtsUnit(): ByteBuffer = ByteBuffer.wrap(ByteArray(64) { 0x5A })

    private fun sink(factory: IecAudioTrackFactory, keepAlive: Boolean = true) = IecPassthroughAudioSink(
        sink = raw,
        trackFactory = factory,
        nanoTime = { clock[0] },
        onDiagnosticEvent = { events.add(it) },
        keepAliveEnabled = keepAlive,
        keepAliveThreadEnabled = false,
        audioOutputReady = {
            checkTimes.add(clock[0])
            outputReady
        }
    )

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

    private class TrackList(vararg tracks: DyingTrack) : IecAudioTrackFactory {
        private val left = ArrayDeque(tracks.toList())
        var opens = 0
        var attempts = 0

        override fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int): IecAudioTrack? {
            attempts++
            val track = left.removeFirstOrNull() ?: return null
            opens++
            track.used = true
            return track
        }

        override fun canOpen(sampleRate: Int, channelCount: Int): Boolean = true
        override fun iec61937Ready(): Boolean = true
    }

    /** A track that plays whatever it is given, until the output dies under it. */
    private class DyingTrack(private val deathCode: Int = -6) : IecAudioTrack {
        override val sampleRate: Int = 192_000
        override val frameSizeBytes: Int = 16
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        override val bufferSizeBytes: Int = 4 * 1024 * 1024
        var dead = false
        var used = false
        var released = false
        var written = 0
        var contentBytes = 0
        var playHead = 0
        var dieAfterBytes = 0
        var deadWrites = 0
        var headStaysWhenDead = false
        private val fillerSignature = Iec61937Packer.packTrueHdInPlace(TrueHdMatPacker.paddingOnlyFrame())

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            if (dead) {
                deadWrites++
                return deathCode
            }
            val count = if (dieAfterBytes > 0) minOf(size, dieAfterBytes).also { dead = true } else size
            val filler = offset == 0 && size == fillerSignature.size && data.contentEquals(fillerSignature)
            if (!filler) contentBytes += count
            written += count
            return count
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() {
            released = true
        }
        override fun playbackHeadFrames(): Long = if (dead && !headStaysWhenDead) 0L else (playHead / frameSizeBytes).toLong()
        override fun setVolume(volume: Float) = Unit
        override fun underrunCount(): Int = 0
    }

    private class RecordingSink : AudioSink {
        var bytes = 0
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
            bytes += buffer.remaining()
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
        const val AU_US = 833L
    }
}
