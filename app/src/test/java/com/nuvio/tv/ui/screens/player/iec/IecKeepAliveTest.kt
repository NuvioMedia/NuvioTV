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
        sink.keepAliveTick()
        assertEquals("one tick fills to the low-water mark", writtenBefore + 4 * FRAME, track.written)
        sink.keepAliveTick()
        assertEquals("queue sits at the low-water mark", writtenBefore + 4 * FRAME, track.written)
        assertTrue(track.writes.takeLast(4).all { it.filler })

        feed(sink, 48 until 72, ptsStartUs = 48 * AU_US, expectAccepted = false)
        assertEquals("content waits while paused", writtenBefore + 4 * FRAME, track.written)
        assertTrue("unplayed audio waits to be replayed", sink.hasPendingData())
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
    fun pause_replaysTheUnplayedContentSoTheClockResumesAtThePausedFrame() {
        val track = HeadTrack()
        val events = mutableListOf<String>()
        val sink = sink(track, events)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feed(sink, 0 until 96, ptsStartUs = 0L)
        val contentBefore = track.contents.size
        assertTrue(contentBefore >= 3)
        track.playHead = track.written - 2 * FRAME
        val atPause = sink.getCurrentPositionUs(false)
        assertEquals((track.written - 2L * FRAME) / 16L * 1_000_000L / 192_000L, atPause)

        sink.pause()
        assertTrue(events.any { it.startsWith("iec_keepalive_replay frames=2") })
        track.playOut()
        repeat(6) { sink.keepAliveTick() }
        track.playOut()
        assertEquals("clock holds at the paused frame", atPause, sink.getCurrentPositionUs(false))

        sink.play()
        feed(sink, 96 until 120, ptsStartUs = 96 * AU_US)
        val replayed = track.contents.drop(contentBefore).take(2)
        val queuedAtPause = track.contents.subList(contentBefore - 2, contentBefore)
        assertEquals(2, replayed.size)
        for (i in 0 until 2) assertTrue("frame $i", replayed[i].contentEquals(queuedAtPause[i]))
        var seen = 0
        var replayEnd = 0
        for (write in track.writes) {
            replayEnd += write.bytes
            if (!write.filler && ++seen == contentBefore + 2) break
        }
        track.playHead = replayEnd
        assertEquals("replayed audio continues from the paused frame", atPause + 2L * FRAME / 16L * 1_000_000L / 192_000L, sink.getCurrentPositionUs(false))
    }

    @Test
    fun aLatePause_replaysFromThePositionThePlayerLastSaw() {
        val track = HeadTrack()
        val events = mutableListOf<String>()
        val sink = sink(track, events)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feed(sink, 0 until 192, ptsStartUs = 0L)
        assertTrue(track.contents.size >= 5)
        track.playHead = track.written - 3 * FRAME
        val seenByThePlayer = sink.getCurrentPositionUs(false)
        assertEquals((track.written - 3L * FRAME) / 16L * 1_000_000L / 192_000L, seenByThePlayer)
        track.playOut()

        sink.pause()
        assertTrue(events.any { it.startsWith("iec_keepalive_replay frames=3") })
        repeat(6) { sink.keepAliveTick() }
        track.playOut()
        assertEquals(seenByThePlayer, sink.getCurrentPositionUs(false))
    }

    @Test
    fun pauseAfterEverythingPlayed_replaysNothing() {
        val track = HeadTrack()
        val events = mutableListOf<String>()
        val sink = sink(track, events)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feed(sink, 0 until 48, ptsStartUs = 0L)
        track.playOut()
        sink.pause()
        assertTrue(events.none { it.startsWith("iec_keepalive_replay") })
    }

    @Test
    fun ledger_markFillerMergesWithExistingRuns() {
        val ledger = IecKeepAliveLedger()
        ledger.add(100, 140)
        ledger.add(300, 320)
        ledger.markFiller(130, 200)
        ledger.markFiller(250, 310)
        assertEquals(100L, ledger.fillerBefore(250))
        assertEquals(170L, ledger.fillerFrames)
        assertEquals(100L, ledger.contentFrames(100))
        assertEquals(100L, ledger.contentFrames(200))
        assertEquals(150L, ledger.contentFrames(250))
        assertEquals(150L, ledger.contentFrames(320))
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
            track.playHead = au * 16
            refused = !sink.handleBuffer(ByteBuffer.wrap(TrueHdMatPackerTest.trueHdAu(au * 40, au == 0)), pts, 1)
            pts += AU_US
            au++
        }
        assertTrue("sink refuses once the queue is full", refused)
        val queued = track.written - track.playHead
        assertTrue(queued <= capBytes + 2 * FRAME)
        assertTrue(queued >= capBytes - FRAME)

        sink.keepAliveTick()
        assertFalse("enough queued, no filler", track.writes.last().filler)
        track.playHead = track.written - FRAME
        sink.keepAliveTick()
        assertTrue("the playback thread is late, filler covers the gap", track.writes.last().filler)
    }

    @Test
    fun resumeWithMoreThanTheCapQueued_fillsTheTrackSoItStarts() {
        val track = FillTrack(capacityBytes = 50 * FRAME)
        val events = mutableListOf<String>()
        val sink = sink(track, events)
        sink.configure(trueHdFormat(), 0, null)
        var au = 0
        while (au < 45 * 24) if (offer(sink, au)) au++
        val preRoll = track.written
        assertTrue(preRoll > 40 * FRAME && preRoll < 50 * FRAME)

        sink.play()
        repeat(20) {
            repeat(24) { if (offer(sink, au)) au++ }
            clock[0] += 50_000_000L
            track.advance(FRAME)
        }
        assertTrue("track started", track.running)
        assertTrue(events.none { it.startsWith("iec_fallback_to_raw") })
        assertTrue(sink.isIecActive)
        repeat(60) {
            repeat(24) { if (offer(sink, au)) au++ }
            clock[0] += 20_000_000L
            track.advance(FRAME)
        }
        assertTrue(sink.isIecActive)
        assertTrue("queue back at the cap", track.written - track.head <= CAP_BYTES + FRAME)
    }

    @Test
    fun openedWhilePlaying_fillsPastTheCapSoTheTrackStarts() {
        val track = FillTrack(capacityBytes = 50 * FRAME)
        val events = mutableListOf<String>()
        val sink = sink(track, events)
        sink.play()
        sink.configure(trueHdFormat(), 0, null)
        var au = 0
        while (au < 60 * 24 && !track.running) {
            if (offer(sink, au)) au++
            clock[0] += 100_000L
        }
        assertTrue("track started", track.running)
        assertTrue(sink.isIecActive)
        assertTrue(events.none { it.startsWith("iec_fallback_to_raw") })
    }

    @Test
    fun aTrackThatNeverStarts_stillFallsBackAfterTheStallBudget() {
        val track = FillTrack(capacityBytes = 50 * FRAME, startsWhenFull = false)
        val events = mutableListOf<String>()
        val sink = sink(track, events)
        sink.configure(trueHdFormat(), 0, null)
        var au = 0
        while (au < 45 * 24) if (offer(sink, au)) au++
        sink.play()
        var refusedAt = -1L
        while (sink.isIecActive && clock[0] < 4 * IecPassthroughAudioSink.WRITE_STALL_BUDGET_NANOS) {
            if (offer(sink, au)) au++ else if (refusedAt < 0) refusedAt = clock[0]
            clock[0] += 10_000_000L
        }
        assertEquals("buffer filled", 50 * FRAME, track.written)
        assertFalse(sink.isIecActive)
        assertTrue(refusedAt >= 0)
        assertTrue(clock[0] - refusedAt <= IecPassthroughAudioSink.WRITE_STALL_BUDGET_NANOS + 20_000_000L)
        assertTrue(events.any { it.startsWith("iec_fallback_to_raw reason=write_stall_ms=") })
    }

    @Test
    fun aHeadThatStopsMidPlay_liftsTheCapAfterTheIdleTime() {
        val track = HeadTrack()
        val sink = sink(track)
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        var au = 0
        while (au < 2_000) {
            track.playHead = au * 16
            if (!offer(sink, au)) break
            au++
        }
        assertTrue("capped", au < 2_000)
        val held = track.written
        assertTrue(held - track.playHead <= CAP_BYTES + 2 * FRAME)
        clock[0] += IecPassthroughAudioSink.KEEP_ALIVE_HEAD_IDLE_NANOS - 1L
        assertFalse(offer(sink, au))
        assertEquals("still capped inside the idle time", held, track.written)
        clock[0] += 1L
        offer(sink, au)
        assertTrue("cap lifted once the head stood still", track.written > held)
    }

    @Test
    fun aVeryLongPause_resumeFillsPastTheCapAgain() {
        val track = FillTrack(capacityBytes = 50 * FRAME)
        val sink = sink(track)
        sink.configure(trueHdFormat(), 0, null)
        var au = 0
        while (au < 49 * 24) if (offer(sink, au)) au++
        sink.play()
        while (!track.running) if (offer(sink, au)) au++
        repeat(60) {
            track.advance(FRAME)
            repeat(24) { if (offer(sink, au)) au++ }
        }
        sink.pause()
        clock[0] += IecPassthroughAudioSink.KEEP_ALIVE_MAX_HOLD_NANOS
        sink.keepAliveTick()
        assertFalse("output released", track.running)
        val queuedAtRelease = track.written - track.head
        assertTrue(queuedAtRelease < 50 * FRAME)
        sink.play()
        var tries = 0
        while (!track.running && tries < 5_000) {
            if (offer(sink, au)) au++
            tries++
        }
        assertTrue("output restarted", track.running)
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
    fun silentDtsCore_framesDescribeThemselves() {
        for (core in listOf(CORE_5_1, CORE_2_0)) {
            val frame = DtsSilence.frameFor(core)!!
            assertEquals(DtsSilence.FRAME_BYTES, frame.size)
            assertEquals(core, DtsSilence.parseCore(frame))
            assertEquals(core, DtsSilence.parseCore(swapped(frame)))
        }
        assertEquals(null, DtsSilence.parseCore(ByteArray(16) { 0x5A }))
    }

    @Test
    fun silentDtsCore_onlyMatchesA48kCoreWithTheSameLayout() {
        assertEquals(null, DtsSilence.frameFor(CORE_5_1.copy(sampleRate = 44_100)))
        assertEquals(null, DtsSilence.frameFor(CORE_5_1.copy(lfe = false)))
        assertEquals(null, DtsSilence.frameFor(CORE_5_1.copy(amode = 6)))
        assertEquals(null, DtsSilence.frameFor(CORE_2_0.copy(lfe = true)))
        assertEquals(null, DtsSilence.frameFor(CORE_5_1.copy(sampleCount = 1024)))
    }

    @Test
    fun dtsHd_a44k1Core_keepsTheOldPauseAndFlush() {
        val track = HeadTrack(fillerSignature = dtsFiller())
        val sink = sink(track)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        feedDts(sink, 0 until 24, ptsStartUs = 0L, core = withSampleRateCode(silence51(), 8))
        assertFalse(sink.isKeepAliveActive)
        sink.pause()
        assertEquals(1, track.pauses)
    }

    @Test
    fun dtsHd_aCoreWithoutLfe_keepsTheOldPauseAndFlush() {
        val track = HeadTrack(fillerSignature = dtsFiller())
        val sink = sink(track)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        feedDts(sink, 0 until 24, ptsStartUs = 0L, core = withoutLfe(silence51()))
        assertFalse(sink.isKeepAliveActive)
        sink.pause()
        assertEquals(1, track.pauses)
    }

    @Test
    fun plainDts_whilePaused_silentCoreBurstsKeepTheOutputAlive() {
        val filler = ByteArray(Iec61937Packer.dtsCoreBurstBytes(DtsSilence.SAMPLE_COUNT))
        assertTrue(Iec61937Packer.packDtsCoreInto(ByteBuffer.wrap(silence51()), DtsSilence.SAMPLE_COUNT, false, filler))
        val track = HeadTrack(fillerSignature = filler)
        val events = mutableListOf<String>()
        val sink = sink(track, events)
        sink.configure(plainDtsFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()
        var pts = 0L
        for (i in 0 until 24) {
            val unit = silence51()
            unit[unit.size - 1] = (i + 1).toByte()
            assertTrue(sink.handleBuffer(ByteBuffer.wrap(unit), pts, 1))
            pts += DTS_AU_US
        }
        assertTrue(sink.isKeepAliveActive)
        assertTrue(events.any { it.startsWith("iec_keepalive_armed mode=DTS_HD") })
        sink.pause()
        assertEquals(0, track.pauses)
        track.playOut()
        sink.keepAliveTick()
        assertTrue(track.writes.last().filler)
    }

    @Test
    fun dtsHd_whilePaused_silentCoreBurstsKeepTheOutputAlive() {
        val track = HeadTrack(fillerSignature = dtsFiller())
        val events = mutableListOf<String>()
        val sink = sink(track, events)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        feedDts(sink, 0 until 24, ptsStartUs = 0L)
        assertTrue(sink.isKeepAliveActive)
        assertTrue(events.any { it.startsWith("iec_keepalive_armed mode=DTS_HD") })
        val writtenBefore = track.written

        sink.pause()
        assertEquals(0, track.pauses)
        track.playOut()
        repeat(20) { sink.keepAliveTick() }
        assertTrue(track.written > writtenBefore)
        assertTrue(track.writes.drop(24).isNotEmpty())
        assertTrue(track.writes.drop(24).all { it.filler })

        sink.flush()
        assertEquals(0, track.flushes)
        sink.play()
        track.playOut()
        feedDts(sink, 24 until 30, ptsStartUs = 5_000_000L)
        assertFalse(track.writes.last().filler)
    }

    @Test
    fun dtsHd_oneTickFillsTheQueueToTheLowWaterMark() {
        val track = HeadTrack(fillerSignature = dtsFiller())
        val sink = sink(track)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        feedDts(sink, 0 until 24, ptsStartUs = 0L)
        sink.pause()
        track.playOut()
        sink.keepAliveTick()
        val lowWaterBytes = IecPassthroughAudioSink.KEEP_ALIVE_LOW_WATER_MS * 192_000L / 1000L * 16L
        assertTrue(track.written - track.playHead >= lowWaterBytes)
        assertTrue(track.written - track.playHead < lowWaterBytes + dtsFiller().size)
        val before = track.written
        sink.keepAliveTick()
        assertEquals(before, track.written)
    }

    @Test
    fun dtsHd_clockSkipsTheSilentCore() {
        val track = HeadTrack(fillerSignature = dtsFiller())
        val sink = sink(track)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        feedDts(sink, 0 until 24, ptsStartUs = 0L)
        track.playOut()
        val paused = sink.getCurrentPositionUs(false)
        sink.pause()
        repeat(20) { sink.keepAliveTick() }
        track.playOut()
        assertEquals(paused, sink.getCurrentPositionUs(false))
    }

    @Test
    fun dtsHd_withoutACoreHeader_keepsTheOldPauseAndFlush() {
        val track = HeadTrack()
        val sink = sink(track)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        var pts = 0L
        repeat(24) {
            sink.handleBuffer(ByteBuffer.wrap(ByteArray(1500) { 0x5A }), pts, 1)
            pts += DTS_AU_US
        }
        assertFalse(sink.isKeepAliveActive)
        sink.pause()
        assertEquals(1, track.pauses)
    }

    @Test
    fun dtsHd_keepAliveSwitchedOff_keepsTheOldPauseAndFlush() {
        val track = HeadTrack(fillerSignature = dtsFiller())
        val sink = sink(track, dtsKeepAlive = false)
        sink.configure(dtsHdFormat(), 0, null)
        sink.play()
        feedDts(sink, 0 until 24, ptsStartUs = 0L)
        assertFalse(sink.isKeepAliveActive)
        sink.pause()
        assertEquals(1, track.pauses)
        sink.keepAliveTick()
        assertTrue(track.writes.none { it.filler })
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
        track: IecAudioTrack,
        events: MutableList<String> = mutableListOf(),
        keepAlive: Boolean = true,
        dtsKeepAlive: Boolean = true
    ): IecPassthroughAudioSink = IecPassthroughAudioSink(
        sink = RecordingSink(),
        trackFactory = object : IecAudioTrackFactory {
            override fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int) = track
            override fun iec61937Ready(): Boolean = true
        },
        nanoTime = { clock[0] },
        onDiagnosticEvent = { events.add(it) },
        keepAliveEnabled = keepAlive,
        keepAliveThreadEnabled = false,
        dtsKeepAliveEnabled = dtsKeepAlive
    )

    private fun feedDts(
        sink: IecPassthroughAudioSink,
        units: IntRange,
        ptsStartUs: Long,
        core: ByteArray = silence51()
    ) {
        var pts = ptsStartUs
        for (i in units) {
            val au = core + ByteArray(64) { (i and 0x7F).toByte() }
            assertTrue("unit $i", sink.handleBuffer(ByteBuffer.wrap(au), pts, 1))
            pts += DTS_AU_US
        }
    }

    private fun dtsFiller(): ByteArray = Iec61937Packer.packDtsHd(
        silence51(),
        Iec61937Packer.dtsHdIecPeriod(8, DtsSilence.SAMPLE_COUNT)
    )

    private fun silence51(): ByteArray = DtsSilence.frameFor(CORE_5_1)!!

    private fun withSampleRateCode(frame: ByteArray, code: Int): ByteArray {
        val out = frame.copyOf()
        out[8] = ((out[8].toInt() and 0xC3) or (code shl 2)).toByte()
        return out
    }

    private fun withoutLfe(frame: ByteArray): ByteArray {
        val out = frame.copyOf()
        out[10] = (out[10].toInt() and 0xF9).toByte()
        return out
    }

    private fun swapped(frame: ByteArray): ByteArray {
        val out = frame.copyOf()
        for (i in 0 until out.size - 1 step 2) {
            val b = out[i]
            out[i] = out[i + 1]
            out[i + 1] = b
        }
        return out
    }

    private fun plainDtsFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .build()

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

    /** One access unit, offered the way the player does it: a refused unit is offered again later. */
    private fun offer(sink: IecPassthroughAudioSink, au: Int): Boolean {
        val unit = TrueHdMatPackerTest.trueHdAu(frameTime = (au * 40) and 0xFFFF, major = au % 24 == 0)
        return sink.handleBuffer(ByteBuffer.wrap(unit), au * AU_US, 1)
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
    private class HeadTrack(
        private val fillerSignature: ByteArray = Iec61937Packer.packTrueHdInPlace(TrueHdMatPacker.paddingOnlyFrame())
    ) : IecAudioTrack {
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
        val contents = mutableListOf<ByteArray>()

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            val filler = size == fillerSignature.size && data.contentEquals(fillerSignature)
            writes.add(Write(size, filler))
            if (!filler) contents.add(data.copyOfRange(offset, offset + size))
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

    /** Like a platform AudioTrack: a fixed buffer, and once play() is called it only starts when that buffer is full. */
    private class FillTrack(
        private val capacityBytes: Int,
        private val startsWhenFull: Boolean = true
    ) : IecAudioTrack {
        override val sampleRate: Int = 192_000
        override val frameSizeBytes: Int = 16
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        override val bufferSizeBytes: Int = capacityBytes
        var written: Int = 0
        var head: Int = 0
        var running = false
        private var playCalled = false

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            val count = minOf(size, capacityBytes - (written - head))
            written += count
            maybeStart()
            return count
        }

        fun advance(bytes: Int) {
            if (running) head = minOf(written, head + bytes)
        }

        private fun maybeStart() {
            if (playCalled && startsWhenFull && written - head >= capacityBytes) running = true
        }

        override fun play() {
            playCalled = true
            maybeStart()
        }

        override fun pause() {
            playCalled = false
            running = false
        }

        override fun flush() = Unit
        override fun release() = Unit
        override fun playbackHeadFrames(): Long = (head / frameSizeBytes).toLong()
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
        const val DTS_AU_US = 10_667L
        val CORE_5_1 = DtsSilence.CoreHeader(sampleCount = 512, amode = 9, sampleRate = 48_000, lfe = true)
        val CORE_2_0 = DtsSilence.CoreHeader(sampleCount = 512, amode = 2, sampleRate = 48_000, lfe = false)
        const val CAP_BYTES = IecPassthroughAudioSink.KEEP_ALIVE_CAP_MS * 192_000L / 1000L * 16L
    }
}
