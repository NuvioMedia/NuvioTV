package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class IecAudioTimestampClockTest {

    @Before
    fun setUp() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @After
    fun tearDown() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @Test
    fun playHeld_orEmptyTrack_orMissingAnchor_isUnset() {
        val clock = ClockRig()
        assertEquals(NOT_SET, clock.pos(playHeld = true))
        assertEquals(NOT_SET, clock.pos(written = 0L))
        clock.startPts = C.TIME_UNSET
        assertEquals(NOT_SET, clock.pos(written = 192_000L))
    }

    @Test
    fun zeroSampleRate_staysOnTheAnchor() {
        val clock = ClockRig(sampleRate = 0)
        assertEquals(ANCHOR, clock.pos(head = 192_000L, written = 192_000L))
    }

    @Test
    fun noTimestamp_followsThePlaybackHead() {
        val clock = ClockRig()
        val head = 906_752L
        assertEquals(ANCHOR + us(head), clock.pos(head = head, written = 1_093_120L))
    }

    @Test
    fun positionNeverReportsTheWrittenLead() {
        val clock = ClockRig()
        val head = 906_752L
        val written = 1_093_120L
        val position = clock.pos(head = head, written = written)
        assertEquals(ANCHOR + us(head), position)
        assertTrue(position < ANCHOR + us(written))
        assertTrue(us(written) - us(head) in 900_000L..1_100_000L)
    }

    @Test
    fun latencyIsSubtractedAfterTheHead_andClampedToTheAnchor() {
        val clock = ClockRig()
        clock.latencyUs = 10_000L
        val head = 192_000L
        assertEquals(ANCHOR + us(head) - 10_000L, clock.pos(head = head, written = head))
        clock.latencyUs = 5_000_000L
        assertEquals(ANCHOR, clock.pos(head = head, written = head))
        clock.latencyUs = -20L
        assertEquals(ANCHOR + us(head), clock.pos(head = head, written = head))
    }

    @Test
    fun latencyIsNotSubtractedFromAnAdvancingTimestamp() {
        val clock = ClockRig()
        clock.latencyUs = 100_000L
        clock.lockAdvancing(head = 192_000L)
        clock.advanceUs(IecAudioTimestampClock.SLOW_POLL_INTERVAL_US)
        val head = clock.head
        clock.timestamp = clock.stamp(head)
        val position = clock.pos(head = head, written = head + 19_200_000L)
        assertNear(ANCHOR + us(head), position, slopUs = 3_000L)
    }

    @Test
    fun initializingWithoutATimestamp_staysOnTheHead_thenGivesUp() {
        val clock = ClockRig()
        var head = 96_000L
        repeat(10) {
            assertEquals(ANCHOR + us(head), clock.pos(head = head, written = head + 192_000L))
            clock.advanceUs(50_000L)
            head += clock.framesFor(50_000L)
        }
        clock.advanceUs(IecAudioTimestampClock.INITIALIZING_DURATION_US)
        head += clock.framesFor(IecAudioTimestampClock.INITIALIZING_DURATION_US)
        assertEquals(ANCHOR + us(head), clock.pos(head = head, written = head + 192_000L))
    }

    @Test
    fun firstTimestampIsNotTrustedUntilItAdvances() {
        val clock = ClockRig()
        clock.timestamp = clock.stamp(96_000L)
        val first = clock.pos(head = 96_000L, written = 288_000L)
        assertEquals(ANCHOR + us(96_000L), first)
        clock.advanceUs(20_000L)
        clock.timestamp = clock.stamp(96_000L)
        val stillHead = clock.pos(head = 99_840L, written = 288_000L)
        assertEquals(ANCHOR + us(99_840L), stillHead)
    }

    @Test
    fun twoAgreeingTimestamps_takeOverFromTheHead() {
        val clock = ClockRig()
        clock.lockAdvancing(head = 192_000L)
        clock.advanceUs(200_000L)
        val frozenHead = clock.head
        val written = frozenHead + 192_000L
        clock.timestamp = clock.stamp(clock.head)
        val position = clock.pos(head = frozenHead, written = written)
        val expected = ANCHOR + us(frozenHead) + 200_000L
        assertNear(expected, position, slopUs = 2_000L)
        assertTrue(position > ANCHOR + us(frozenHead) + 100_000L)
    }

    @Test
    fun frozenHead_withAnAdvancingTimestamp_keepsContentTime_untilWrittenRunsOut() {
        val clock = ClockRig()
        clock.lockAdvancing(head = 192_000L)
        val frozenHead = clock.head
        val written = frozenHead + 192_000L
        clock.advanceUs(400_000L)
        clock.timestamp = clock.stamp(frozenHead)
        val position = clock.pos(head = frozenHead, written = written)
        assertNear(ANCHOR + us(frozenHead) + 400_000L, position, slopUs = 2_000L)
        clock.advanceUs(2_000_000L)
        clock.timestamp = clock.stamp(frozenHead)
        val clamped = clock.pos(head = frozenHead, written = written)
        assertEquals(ANCHOR + us(written), clamped)
    }

    @Test
    fun timestampThatStopsUpdatingItsNanoTime_fallsBackAfterTheOffsetGate() {
        val clock = ClockRig()
        clock.lockAdvancing(head = 192_000L)
        val frozen = clock.stamp(clock.head)
        clock.timestamp = frozen
        clock.advanceUs(IecAudioTimestampClock.SLOW_POLL_INTERVAL_US)
        val head = clock.head
        val position = clock.pos(head = head, written = head + 192_000L)
        assertEquals(ANCHOR + us(head), position)
    }

    @Test
    fun timestampFiveSecondsAheadOfTheHead_isRejected() {
        val clock = ClockRig()
        val head = 192_000L
        clock.timestamp = clock.stamp(head + 192_000L * 6L)
        clock.pos(head = head, written = head + 192_000L * 8L)
        clock.advanceUs(20_000L)
        clock.timestamp = clock.stamp(head + 192_000L * 6L + 3_840L)
        val position = clock.pos(head = head + 3_840L, written = head + 192_000L * 8L)
        assertEquals(ANCHOR + us(head + 3_840L), position)
    }

    @Test
    fun timestampSystemTimeFiveSecondsOff_isRejected() {
        val clock = ClockRig()
        val head = 192_000L
        clock.timestamp = IecAudioTimestampSample(
            framePosition = head,
            nanoTime = clock.nowNs - 6L * C.MICROS_PER_SECOND * 1000L
        )
        val position = clock.pos(head = head, written = head + 192_000L)
        assertEquals(ANCHOR + us(head), position)
    }

    @Test
    fun plausibleTimestampAfterAnError_isTriedAgain() {
        val clock = ClockRig()
        val head = 192_000L
        clock.timestamp = IecAudioTimestampSample(
            framePosition = head,
            nanoTime = clock.nowNs - 6L * C.MICROS_PER_SECOND * 1000L
        )
        clock.pos(head = head, written = head + 384_000L)
        clock.advanceUs(IecAudioTimestampClock.ERROR_POLL_INTERVAL_US)
        clock.lockAdvancing(head = head)
        clock.advanceUs(100_000L)
        val frozenHead = clock.head
        val position = clock.pos(head = frozenHead, written = frozenHead + 192_000L)
        assertNear(ANCHOR + us(frozenHead) + 100_000L, position, slopUs = 2_000L)
    }

    @Test
    fun timestampThatDoesNotAdvanceForTwoSeconds_fallsBackToTheHead() {
        val clock = ClockRig()
        val origin = 192_000L
        clock.timestamp = clock.stamp(origin)
        clock.pos(head = origin, written = origin + 1_000_000L)
        var head = origin
        repeat(15) {
            clock.advanceUs(200_000L)
            head += clock.framesFor(200_000L)
            clock.timestamp = clock.stamp(origin)
            clock.pos(head = head, written = head + 192_000L)
        }
        val position = clock.pos(head = head, written = head + 192_000L)
        assertNear(ANCHOR + us(head), position, slopUs = 5_000L)
    }

    @Test
    fun lostTimestampWhileAdvancing_returnsToTheHead() {
        val clock = ClockRig()
        clock.lockAdvancing(head = 192_000L)
        clock.advanceUs(IecAudioTimestampClock.SLOW_POLL_INTERVAL_US)
        clock.timestamp = null
        val head = clock.head
        assertEquals(ANCHOR + us(head), clock.pos(head = head, written = head + 192_000L))
    }

    @Test
    fun timestampReappearsAfterNoTimestamp_canLockAgain() {
        val clock = ClockRig()
        clock.advanceUs(IecAudioTimestampClock.INITIALIZING_DURATION_US + 20_000L)
        clock.pos(head = 96_000L, written = 288_000L)
        clock.reset()
        clock.startPts = ANCHOR
        clock.lockAdvancing(head = 192_000L)
        val head = clock.head
        val baseline = clock.pos(head = head, written = head + 192_000L)
        clock.advanceUs(50_000L)
        assertNear(baseline + 50_000L, clock.pos(head = head, written = head + 192_000L), 2_000L)
    }

    @Test
    fun timestampFrameWrap_keepsAdvancing() {
        val clock = ClockRig()
        val beforeWrap = (1L shl 32) - 3_840L
        clock.lockAdvancing(head = beforeWrap)
        clock.advanceUs(IecAudioTimestampClock.SLOW_POLL_INTERVAL_US)
        val wrappedRaw = 3_840L
        clock.timestamp = clock.stamp(wrappedRaw)
        val written = beforeWrap + 192_000L
        val position = clock.pos(head = beforeWrap + 7_680L, written = written)
        assertTrue(position > ANCHOR + us(beforeWrap))
        assertTrue(position <= ANCHOR + us(written))
    }

    @Test
    fun staleTimestampAfterReset_doesNotCountAWrap() {
        val clock = ClockRig()
        clock.timestamp = clock.stamp(2_832_427L)
        val rejected = clock.pos(head = 8_192L, written = 200_000L)
        assertEquals(ANCHOR + us(8_192L), rejected)
        clock.advanceUs(IecAudioTimestampClock.ERROR_POLL_INTERVAL_US)
        clock.lockAdvancing(head = 96_000L)
        clock.advanceUs(100_000L)
        val head = clock.head
        clock.timestamp = clock.stamp(head)
        val position = clock.pos(head = head, written = head + 384_000L)
        assertNear(ANCHOR + us(head) + 100_000L, position, slopUs = 3_000L)
    }

    @Test
    fun smallBackwardTimestampJitter_isNotAWrap() {
        val clock = ClockRig()
        clock.lockAdvancing(head = 1_000_000L)
        clock.advanceUs(IecAudioTimestampClock.SLOW_POLL_INTERVAL_US)
        val head = clock.head
        clock.timestamp = clock.stamp(head)
        clock.pos(head = head, written = head + 19_200_000L)
        clock.advanceUs(IecAudioTimestampClock.SLOW_POLL_INTERVAL_US)
        clock.timestamp = clock.stamp(head - 100L)
        clock.pos(head = head, written = head + 19_200_000L)
        clock.advanceUs(200_000L)
        clock.timestamp = clock.stamp(head - 100L)
        val position = clock.pos(head = head, written = head + 19_200_000L)
        assertNear(ANCHOR + us(head - 100L) + 200_000L, position, slopUs = 3_000L)
    }

    @Test
    fun flushResetsAnAdvancingTimestamp() {
        val clock = ClockRig()
        clock.lockAdvancing(head = 192_000L)
        clock.reset()
        clock.startPts = ANCHOR
        clock.timestamp = clock.stamp(0L)
        assertEquals(ANCHOR + us(96_000L), clock.pos(head = 96_000L, written = 192_000L))
    }

    @Test
    fun smallHeadJitter_isSmoothedOnceTheClockIsMoving() {
        val clock = ClockRig()
        val first = clock.pos(head = 192_000L, written = 384_000L)
        assertEquals(ANCHOR + us(192_000L), first)
        clock.advanceUs(20_000L)
        val jumped = clock.pos(head = 192_000L + 38_400L, written = 384_000L)
        val expected = ANCHOR + us(192_000L) + 20_000L
        val maxStep = 20_000L * IecAudioTimestampClock.MAX_POSITION_SMOOTHING_SPEED_CHANGE_PERCENT / 100L
        assertTrue(jumped in (expected - maxStep)..(expected + maxStep))
        assertTrue(jumped < ANCHOR + us(192_000L + 38_400L))
    }

    @Test
    fun jumpLargerThanOneSecond_isAppliedImmediately() {
        val clock = ClockRig()
        clock.pos(head = 192_000L, written = 2_000_000L)
        clock.advanceUs(20_000L)
        val jumped = clock.pos(head = 192_000L + 384_000L, written = 2_000_000L)
        assertEquals(ANCHOR + us(192_000L + 384_000L), jumped)
    }

    @Test
    fun pausedClock_doesNotSmoothOrExtrapolate() {
        val clock = ClockRig()
        clock.pos(head = 192_000L, written = 384_000L)
        clock.advanceUs(200_000L)
        val paused = clock.pos(head = 192_000L, written = 384_000L, playing = false)
        assertEquals(ANCHOR + us(192_000L), paused)
    }

    @Test
    fun fortyFourOneFamily_usesTheContentRate() {
        val clock = ClockRig(sampleRate = 176_400)
        val head = 176_400L
        assertEquals(ANCHOR + 1_000_000L, clock.pos(head = head, written = head + 17_640L))
    }

    @Test
    fun fasterPlayback_extrapolatesTheTimestamp() {
        val clock = ClockRig()
        clock.lockAdvancing(head = 192_000L)
        val head = clock.head
        val baseline = clock.pos(head = head, written = head + 384_000L)
        clock.speed = 2f
        clock.advanceUs(100_000L)
        val position = clock.pos(head = head, written = head + 384_000L)
        assertTrue(position >= baseline + 180_000L)
        assertTrue(position <= baseline + 240_000L)
        assertTrue(position > baseline + 100_000L)
    }

    @Test
    fun zeroSpeed_isTreatedAsRealtime() {
        val clock = ClockRig()
        clock.speed = 0f
        assertEquals(ANCHOR + us(192_000L), clock.pos(head = 192_000L, written = 192_000L))
    }

    @Test
    fun placeholderTrueHdPts_isPreserved() {
        val clock = ClockRig()
        clock.startPts = 1_000_000_000_000L
        assertEquals(1_000_000_000_000L + us(906_752L), clock.pos(head = 906_752L, written = 1_093_120L))
    }

    @Test
    fun avengersLog_headTracksWallUntilTheSixteenSecondGap() {
        val playing = AVENGERS.filter { it.playing }
        val origin = playing.first()
        for (sample in playing) {
            if (sample.wallUs == STALL_WALL_US) break
            val wallFromOrigin = sample.wallUs - origin.wallUs
            val headFromOrigin = us(sample.head) - us(origin.head)
            assertNear(wallFromOrigin, headFromOrigin, slopUs = 50_000L)
        }
    }

    @Test
    fun avengersLog_sixteenSecondGapMovesTheHeadThreeHundredMilliseconds() {
        val before = AVENGERS.first { it.wallUs == BEFORE_STALL_WALL_US }
        val after = AVENGERS.first { it.wallUs == STALL_WALL_US }
        assertEquals(15_735_000L, after.wallUs - before.wallUs)
        assertEquals(340_000L, us(after.head) - us(before.head))
        assertEquals(345_333L, us(after.written) - us(before.written))
    }

    @Test
    fun avengersLog_withoutATimestamp_theClockFollowsTheFrozenHead() {
        val clock = ClockRig()
        clock.startPts = 1_000_000_000_000L
        var last = NOT_SET
        for (sample in AVENGERS) {
            clock.nowNs = sample.wallUs * 1000L
            if (!sample.playing || sample.written == 0L) {
                assertEquals(NOT_SET, clock.pos(head = sample.head, written = sample.written, playing = sample.playing))
                continue
            }
            last = clock.pos(head = sample.head, written = sample.written, playing = true)
            assertEquals(1_000_000_000_000L + us(sample.head), last)
        }
        val before = AVENGERS.first { it.wallUs == BEFORE_STALL_WALL_US }
        val after = AVENGERS.first { it.wallUs == STALL_WALL_US }
        clock.nowNs = before.wallUs * 1000L
        clock.reset()
        clock.startPts = 1_000_000_000_000L
        val beforePos = clock.pos(head = before.head, written = before.written)
        clock.nowNs = after.wallUs * 1000L
        val afterPos = clock.pos(head = after.head, written = after.written)
        assertEquals(340_000L, afterPos - beforePos)
    }

    @Test
    fun avengersLog_advancingTimestampCannotInventTimePastWritten() {
        val before = AVENGERS.first { it.wallUs == BEFORE_STALL_WALL_US }
        val clock = ClockRig()
        clock.nowNs = (before.wallUs - 80_000L) * 1000L
        clock.reset()
        clock.startPts = 1_000_000_000_000L
        clock.lockAdvancing(head = before.head - 7_680L, written = before.written)
        clock.advanceUs(2_000_000L)
        clock.timestamp = clock.stamp(clock.head)
        val position = clock.pos(head = before.head, written = before.written)
        assertEquals(1_000_000_000_000L + us(before.written), position)
        assertTrue(position < 1_000_000_000_000L + us(before.head) + 2_000_000L)
    }

    @Test
    fun avengersLog_writtenLeadStaysOneSecondWhilePlaying() {
        for (sample in AVENGERS.filter { it.playing }) {
            val leadUs = us(sample.written) - us(sample.head)
            assertTrue("leadUs=$leadUs head=${sample.head}", leadUs in 900_000L..1_100_000L)
        }
    }

    @Test
    fun avengersLog_writeStallsAboutSixtyTimesPerSecondUntilTheGap() {
        val first = AVENGERS.first { it.playing }
        val last = AVENGERS.first { it.wallUs == BEFORE_STALL_WALL_US }
        val dtUs = last.wallUs - first.wallUs
        val rate = (last.stalls - first.stalls) * 1_000_000.0 / dtUs
        assertTrue("stalls/s=$rate", rate in 50.0..70.0)
        val after = AVENGERS.first { it.wallUs == STALL_WALL_US }
        assertEquals(19L, after.stalls - last.stalls)
        assertTrue(AVENGERS.last().stalls == after.stalls)
    }

    @Test
    fun avengersLog_neverUnderruns() {
        assertTrue(AVENGERS.all { it.underruns == 0 })
    }

    @Test
    fun bothHeadAndTimestampFrozen_doNotInventTime() {
        val clock = ClockRig()
        clock.lockAdvancing(head = 192_000L)
        val frozenHead = clock.head
        clock.advanceUs(IecAudioTimestampClock.SLOW_POLL_INTERVAL_US)
        clock.timestamp = clock.stamp(frozenHead)
        val frozen = clock.pos(head = frozenHead, written = frozenHead + 192_000L)
        clock.advanceUs(IecAudioTimestampClock.SLOW_POLL_INTERVAL_US)
        clock.timestamp = clock.stamp(frozenHead)
        val still = clock.pos(head = frozenHead, written = frozenHead + 192_000L)
        assertEquals(frozen, still)
    }

    @Test
    fun sink_withoutATimestamp_matchesTheHeadClock() {
        val track = ScriptedIecAudioTrack()
        val sink = IecPassthroughAudioSink(
            sink = SilentSink(),
            trackFactory = ReadyFactory(track)
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feedTrueHd(sink)
        track.head = (track.writtenBytes / track.frameSizeBytes).toLong()
        val headUs = us(track.head)
        assertEquals(headUs, sink.getCurrentPositionUs(false))
        assertEquals(headUs, sink.getCurrentPositionUs(true))
    }

    @Test
    fun sink_advancingTimestampSurvivesAHeadStall() {
        val track = ScriptedIecAudioTrack()
        val now = longArrayOf(1_000_000_000L)
        val sink = IecPassthroughAudioSink(
            sink = SilentSink(),
            trackFactory = ReadyFactory(track),
            nanoTime = { now[0] }
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feedTrueHd(sink, accessUnits = 480)
        val writtenFrames = (track.writtenBytes / track.frameSizeBytes).toLong()
        track.head = writtenFrames / 4
        track.timestamp = IecAudioTimestampSample(track.head, now[0])
        sink.getCurrentPositionUs(false)
        now[0] += 20_000L * 1000L
        track.head = writtenFrames / 2 + 3_840L
        track.timestamp = IecAudioTimestampSample(track.head, now[0])
        sink.getCurrentPositionUs(false)
        val frozenHead = track.head
        val beforeStall = sink.getCurrentPositionUs(false)
        now[0] += 200_000L * 1000L
        track.head = frozenHead
        track.timestamp = IecAudioTimestampSample(frozenHead, now[0] - 200_000L * 1000L)
        val position = sink.getCurrentPositionUs(false)
        assertTrue("before=$beforeStall after=$position", position > beforeStall + 100_000L)
        assertTrue(position <= us(writtenFrames) + beforeStall)
    }

    @Test
    fun sink_advancingTimestampSurvivesADiscontinuityAnchor() {
        val track = ScriptedIecAudioTrack()
        val now = longArrayOf(1_000_000_000L)
        val sink = IecPassthroughAudioSink(
            sink = SilentSink(),
            trackFactory = ReadyFactory(track),
            nanoTime = { now[0] }
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feedTrueHd(sink, accessUnits = 480)
        track.head = (track.writtenBytes / track.frameSizeBytes).toLong() / 2
        track.timestamp = IecAudioTimestampSample(track.head, now[0])
        sink.getCurrentPositionUs(false)
        now[0] += 20_000L * 1000L
        track.head += 3_840L
        track.timestamp = IecAudioTimestampSample(track.head, now[0])
        sink.getCurrentPositionUs(false)
        sink.handleDiscontinuity()
        feedTrueHd(sink, accessUnits = 480)
        track.head += 3_840L
        track.timestamp = IecAudioTimestampSample(track.head, now[0])
        sink.getCurrentPositionUs(false)
        now[0] += 20_000L * 1000L
        track.head += 3_840L
        track.timestamp = IecAudioTimestampSample(track.head, now[0])
        sink.getCurrentPositionUs(false)
        val frozenHead = track.head
        val beforeStall = sink.getCurrentPositionUs(false)
        now[0] += 200_000L * 1000L
        track.timestamp = IecAudioTimestampSample(frozenHead, now[0] - 200_000L * 1000L)
        val position = sink.getCurrentPositionUs(false)
        assertTrue("before=$beforeStall after=$position", position > beforeStall + 100_000L)
    }

    @Test
    fun sink_flushClearsTheTimestampClock() {
        val track = ScriptedIecAudioTrack()
        val sink = IecPassthroughAudioSink(
            sink = SilentSink(),
            trackFactory = ReadyFactory(track)
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feedTrueHd(sink)
        track.head = 192_000L
        track.timestamp = IecAudioTimestampSample(192_000L, System.nanoTime())
        sink.flush()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
    }

    @Test
    fun sink_playHeldKeepsTheClockUnset() {
        val track = ScriptedIecAudioTrack()
        track.held = true
        val sink = IecPassthroughAudioSink(
            sink = SilentSink(),
            trackFactory = ReadyFactory(track)
        )
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        feedTrueHd(sink)
        track.head = 192_000L
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
    }

    private class ClockRig(private val sampleRate: Int = RATE) {
        var nowNs = 1_000_000_000L
        var startPts = ANCHOR
        var latencyUs = 0L
        var speed = 1f
        var timestamp: IecAudioTimestampSample? = null
        var head = 0L
        val clock = IecAudioTimestampClock { nowNs }

        init {
            clock.reset()
        }

        fun stamp(frames: Long): IecAudioTimestampSample {
            return IecAudioTimestampSample(frames, nowNs)
        }

        fun advanceUs(deltaUs: Long) {
            nowNs += deltaUs * 1000L
        }

        fun reset() {
            clock.reset()
        }

        fun pos(
            head: Long = this.head,
            written: Long = head + 192_000L,
            playHeld: Boolean = false,
            playing: Boolean = true
        ): Long {
            this.head = head
            return clock.positionUs(
                sampleRate = sampleRate,
                headFrames = head,
                writtenFrames = written,
                startPtsUs = startPts,
                latencyUs = latencyUs,
                playHeld = playHeld,
                timestamp = timestamp,
                playbackSpeed = speed,
                playing = playing
            )
        }

        fun lockAdvancing(head: Long, written: Long = head + 384_000L) {
            var current = head
            var currentWritten = written
            repeat(3) {
                timestamp = stamp(current)
                pos(head = current, written = currentWritten)
                advanceUs(20_000L)
                val step = framesFor(20_000L)
                current += step
                currentWritten += step
            }
            this.head = current
        }

        fun framesFor(deltaUs: Long): Long {
            return sampleRate.toLong() * deltaUs / 1_000_000L
        }
    }

    private class ScriptedIecAudioTrack : IecAudioTrack {
        override val sampleRate: Int = RATE
        override val frameSizeBytes: Int = 16
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        override val bufferSizeBytes: Int = RATE * 16
        var writtenBytes: Int = 0
        var head: Long = 0L
        var timestamp: IecAudioTimestampSample? = null
        var latency: Long = 0L
        var held: Boolean = false

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            writtenBytes += size
            return size
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun playbackHeadFrames(): Long = head
        override fun setVolume(volume: Float) = Unit
        override fun underrunCount(): Int = 0
        override fun isPlayHeld(): Boolean = held
        override fun outputLatencyUs(): Long = latency
        override fun timestamp(): IecAudioTimestampSample? = timestamp
    }

    private class ReadyFactory(private val track: IecAudioTrack) : IecAudioTrackFactory {
        override fun open(
            sampleRate: Int,
            channelCount: Int,
            bufferSizeBytes: Int,
            sessionId: Int
        ): IecAudioTrack = track

        override fun iec61937Ready(): Boolean = true
        override fun iec61937ReadyAt(sampleRate: Int): Boolean = true
    }

    private class SilentSink : AudioSink {
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
        ): Boolean = true
        override fun playToEndOfStream() = Unit
        override fun isEnded(): Boolean = false
        override fun hasPendingData(): Boolean = false
        override fun setPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters) = Unit
        override fun getPlaybackParameters(): androidx.media3.common.PlaybackParameters =
            androidx.media3.common.PlaybackParameters.DEFAULT
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

    private data class HealthSample(
        val wallUs: Long,
        val head: Long,
        val written: Long,
        val pending: Int,
        val stalls: Long,
        val playing: Boolean,
        val underruns: Int = 0
    )

    companion object {
        private const val RATE = 192_000
        private const val ANCHOR = 0L
        private val NOT_SET = AudioSink.CURRENT_POSITION_NOT_SET
        private const val BEFORE_STALL_WALL_US = 55_074_000L
        private const val STALL_WALL_US = 70_809_000L

        private val AVENGERS = listOf(
            HealthSample(wallUs(21, 0, 29, 454), 0, 0, 0, 0, false),
            HealthSample(wallUs(21, 0, 34, 458), 906_752, 1_093_120, 1, 292, true),
            HealthSample(wallUs(21, 0, 39, 473), 1_865_216, 2_059_776, 1, 594, true),
            HealthSample(wallUs(21, 0, 44, 484), 2_831_360, 3_022_080, 0, 893, true),
            HealthSample(wallUs(21, 0, 49, 486), 3_789_824, 3_982_080, 0, 1_187, true),
            HealthSample(wallUs(21, 0, 54, 492), 4_748_288, 4_942_080, 0, 1_494, true),
            HealthSample(wallUs(21, 0, 59, 492), 5_706_752, 5_901_824, 1, 1_818, true),
            HealthSample(wallUs(21, 1, 4, 499), 6_673_664, 6_865_920, 0, 2_121, true),
            HealthSample(wallUs(21, 1, 9, 499), 7_632_128, 7_825_920, 0, 2_421, true),
            HealthSample(wallUs(21, 1, 14, 512), 8_590_592, 8_785_408, 1, 2_731, true),
            HealthSample(wallUs(21, 1, 19, 519), 9_557_632, 9_743_872, 1, 3_041, true),
            HealthSample(wallUs(21, 1, 24, 528), 10_515_968, 10_709_760, 0, 3_341, true),
            HealthSample(wallUs(21, 1, 40, 263), 10_581_248, 10_776_064, 1, 3_360, true),
            HealthSample(wallUs(21, 1, 45, 280), 11_555_840, 11_738_880, 0, 3_360, true),
            HealthSample(wallUs(21, 1, 50, 294), 12_514_304, 12_702_720, 0, 3_360, true),
            HealthSample(wallUs(21, 1, 55, 308), 13_481_216, 13_666_560, 0, 3_360, true),
            HealthSample(wallUs(21, 2, 0, 316), 14_439_680, 14_626_560, 0, 3_360, true),
            HealthSample(wallUs(21, 2, 5, 320), 15_398_144, 15_586_560, 0, 3_360, true),
            HealthSample(wallUs(21, 2, 10, 320), 16_356_608, 16_546_560, 0, 3_360, true)
        )

        private fun wallUs(hour: Int, minute: Int, second: Int, millis: Int): Long {
            val origin = ((21L * 3600L + 29L) * 1000L + 454L)
            val now = ((hour.toLong() * 3600L + minute.toLong() * 60L + second.toLong()) * 1000L + millis.toLong())
            return (now - origin) * 1000L
        }

        private fun us(frames: Long, sampleRate: Int = RATE): Long {
            return IecAudioTimestampClock.framesToUs(frames, sampleRate)
        }

        private fun assertNear(expected: Long, actual: Long, slopUs: Long) {
            assertTrue(
                "expected $expected ± $slopUs, got $actual",
                actual in (expected - slopUs)..(expected + slopUs)
            )
        }

        private fun trueHdFormat(): Format {
            return Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
                .setChannelCount(8)
                .setSampleRate(48_000)
                .build()
        }

        private fun feedTrueHd(sink: IecPassthroughAudioSink, accessUnits: Int = 48) {
            var pts = 0L
            for (i in 0 until accessUnits) {
                val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
                assertTrue(sink.handleBuffer(ByteBuffer.wrap(au), pts, 1))
                pts += 833L
            }
        }
    }
}
