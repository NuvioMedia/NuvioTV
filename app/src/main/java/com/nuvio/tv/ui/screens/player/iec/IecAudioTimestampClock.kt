package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioSink
import kotlin.math.abs

internal data class IecAudioTimestampSample(
    val framePosition: Long,
    val nanoTime: Long
)

internal class IecAudioTimestampClock(
    private val nowNanos: () -> Long = System::nanoTime
) {
    private var state = State.INITIALIZING
    private var initializeSystemTimeUs = 0L
    private var lastTimestampSampleTimeUs = 0L
    private var sampleIntervalUs = FAST_POLL_INTERVAL_US
    private var initialTimestampPositionFrames = C.INDEX_UNSET.toLong()
    private var initialTimestampSystemTimeUs = C.TIME_UNSET
    private var lastRawTimestampFrames = 0L
    private var timestampWraps = 0L
    private var acceptedFrames = 0L
    private var acceptedSystemTimeUs = 0L
    private var hasAcceptedTimestamp = false
    private var lastSystemTimeUs = C.TIME_UNSET
    private var lastPositionUs = C.TIME_UNSET

    init {
        reset()
    }

    fun reset() {
        resetPoller()
        lastRawTimestampFrames = 0L
        timestampWraps = 0L
        lastSystemTimeUs = C.TIME_UNSET
        lastPositionUs = C.TIME_UNSET
    }

    fun positionUs(
        sampleRate: Int,
        headFrames: Long,
        writtenFrames: Long,
        startPtsUs: Long,
        latencyUs: Long,
        playHeld: Boolean,
        timestamp: IecAudioTimestampSample?,
        playbackSpeed: Float = 1f,
        playing: Boolean = true
    ): Long {
        if (playHeld || writtenFrames <= 0L || startPtsUs == C.TIME_UNSET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        if (sampleRate <= 0) {
            return startPtsUs
        }
        val speed = if (playbackSpeed > 0f) playbackSpeed else 1f
        val systemTimeUs = nowNanos() / 1000L
        val safeHead = headFrames.coerceAtLeast(0L)
        val safeWritten = writtenFrames.coerceAtLeast(0L)
        val headUs = framesToUs(safeHead, sampleRate)
        poll(systemTimeUs, timestamp, headUs, sampleRate, speed)
        val fromTimestamp = state == State.TIMESTAMP_ADVANCING && hasAcceptedTimestamp
        var positionUs = if (fromTimestamp) {
            extrapolate(acceptedFrames, acceptedSystemTimeUs, systemTimeUs, sampleRate, speed)
        } else {
            headUs
        }
        positionUs = minOf(positionUs, framesToUs(safeWritten, sampleRate))
        positionUs = smooth(systemTimeUs, positionUs, speed, playing)
        val presentedUs = if (fromTimestamp) {
            positionUs.coerceAtLeast(0L)
        } else if (positionUs <= 0L) {
            0L
        } else {
            (positionUs - latencyUs.coerceAtLeast(0L)).coerceAtLeast(0L)
        }
        return startPtsUs + presentedUs
    }

    private fun resetPoller() {
        state = State.INITIALIZING
        lastTimestampSampleTimeUs = 0L
        sampleIntervalUs = FAST_POLL_INTERVAL_US
        initialTimestampPositionFrames = C.INDEX_UNSET.toLong()
        initialTimestampSystemTimeUs = C.TIME_UNSET
        initializeSystemTimeUs = nowNanos() / 1000L
        acceptedFrames = 0L
        acceptedSystemTimeUs = 0L
        hasAcceptedTimestamp = false
    }

    private fun poll(
        systemTimeUs: Long,
        sample: IecAudioTimestampSample?,
        headUs: Long,
        sampleRate: Int,
        speed: Float
    ) {
        if (systemTimeUs - lastTimestampSampleTimeUs < sampleIntervalUs) {
            return
        }
        lastTimestampSampleTimeUs = systemTimeUs
        val stamp = sample
        val updated = stamp != null
        var wrappedFrames = 0L
        if (stamp != null) {
            val raw = stamp.framePosition
            if (raw < lastRawTimestampFrames &&
                lastRawTimestampFrames >= WRAP_FROM &&
                raw < WRAP_TO
            ) {
                timestampWraps++
            }
            lastRawTimestampFrames = raw
            wrappedFrames = raw + (timestampWraps shl 32)
            val tsSystemUs = stamp.nanoTime / 1000L
            val tsPosUs = extrapolate(wrappedFrames, tsSystemUs, systemTimeUs, sampleRate, speed)
            when {
                abs(tsSystemUs - systemTimeUs) > MAX_AUDIO_TIMESTAMP_OFFSET_US -> {
                    enter(State.ERROR)
                    hasAcceptedTimestamp = false
                }
                abs(tsPosUs - headUs) > MAX_AUDIO_TIMESTAMP_OFFSET_US -> {
                    enter(State.ERROR)
                    hasAcceptedTimestamp = false
                }
                state == State.ERROR -> {
                    resetPoller()
                    lastTimestampSampleTimeUs = systemTimeUs
                }
            }
        }
        when (state) {
            State.INITIALIZING -> {
                if (stamp != null) {
                    val tsSystemUs = stamp.nanoTime / 1000L
                    if (tsSystemUs >= initializeSystemTimeUs) {
                        initialTimestampPositionFrames = wrappedFrames
                        initialTimestampSystemTimeUs = tsSystemUs
                        enter(State.TIMESTAMP)
                    }
                } else if (systemTimeUs - initializeSystemTimeUs > INITIALIZING_DURATION_US) {
                    enter(State.NO_TIMESTAMP)
                }
            }
            State.TIMESTAMP -> {
                if (stamp != null) {
                    val tsSystemUs = stamp.nanoTime / 1000L
                    if (isAdvancing(systemTimeUs, sampleRate, speed, wrappedFrames, tsSystemUs)) {
                        acceptedFrames = wrappedFrames
                        acceptedSystemTimeUs = tsSystemUs
                        hasAcceptedTimestamp = true
                        enter(State.TIMESTAMP_ADVANCING)
                    } else if (systemTimeUs - initializeSystemTimeUs > WAIT_FOR_ADVANCE_DURATION_US) {
                        enter(State.NO_TIMESTAMP)
                    } else {
                        initialTimestampPositionFrames = wrappedFrames
                        initialTimestampSystemTimeUs = tsSystemUs
                    }
                } else {
                    resetPoller()
                }
            }
            State.TIMESTAMP_ADVANCING -> {
                if (stamp == null) {
                    hasAcceptedTimestamp = false
                    resetPoller()
                } else {
                    acceptedFrames = wrappedFrames
                    acceptedSystemTimeUs = stamp.nanoTime / 1000L
                    hasAcceptedTimestamp = true
                }
            }
            State.NO_TIMESTAMP -> {
                if (updated) {
                    resetPoller()
                }
            }
            State.ERROR -> Unit
        }
    }

    private fun isAdvancing(
        systemTimeUs: Long,
        sampleRate: Int,
        speed: Float,
        currentFrames: Long,
        currentSystemUs: Long
    ): Boolean {
        if (currentFrames <= initialTimestampPositionFrames) {
            return false
        }
        val fromInitial = extrapolate(
            initialTimestampPositionFrames,
            initialTimestampSystemTimeUs,
            systemTimeUs,
            sampleRate,
            speed
        )
        val fromCurrent = extrapolate(
            currentFrames,
            currentSystemUs,
            systemTimeUs,
            sampleRate,
            speed
        )
        return abs(fromCurrent - fromInitial) < MAX_POSITION_DRIFT_ADVANCING_TIMESTAMP_US
    }

    private fun extrapolate(
        frames: Long,
        stampSystemUs: Long,
        nowUs: Long,
        sampleRate: Int,
        speed: Float
    ): Long {
        return framesToUs(frames, sampleRate) + mediaDurationForPlayout(nowUs - stampSystemUs, speed)
    }

    private fun smooth(
        systemTimeUs: Long,
        positionUs: Long,
        speed: Float,
        playing: Boolean
    ): Long {
        if (!playing || lastSystemTimeUs == C.TIME_UNSET) {
            if (playing) {
                lastSystemTimeUs = systemTimeUs
                lastPositionUs = positionUs
            }
            return positionUs
        }
        val elapsedUs = systemTimeUs - lastSystemTimeUs
        val positionDiffUs = positionUs - lastPositionUs
        val expectedDiffUs = mediaDurationForPlayout(elapsedUs, speed)
        val expectedUs = lastPositionUs + expectedDiffUs
        val driftUs = abs(expectedUs - positionUs)
        var smoothed = positionUs
        if (positionDiffUs != 0L && driftUs < MAX_POSITION_DRIFT_FOR_SMOOTHING_US) {
            val maxAllowedUs = expectedDiffUs * MAX_POSITION_SMOOTHING_SPEED_CHANGE_PERCENT / 100L
            smoothed = positionUs.coerceIn(expectedUs - maxAllowedUs, expectedUs + maxAllowedUs)
        }
        lastSystemTimeUs = systemTimeUs
        lastPositionUs = smoothed
        return smoothed
    }

    private fun enter(next: State) {
        state = next
        sampleIntervalUs = when (next) {
            State.INITIALIZING, State.TIMESTAMP -> FAST_POLL_INTERVAL_US
            State.TIMESTAMP_ADVANCING, State.NO_TIMESTAMP -> SLOW_POLL_INTERVAL_US
            State.ERROR -> ERROR_POLL_INTERVAL_US
        }
    }

    private enum class State {
        INITIALIZING,
        TIMESTAMP,
        TIMESTAMP_ADVANCING,
        NO_TIMESTAMP,
        ERROR
    }

    companion object {
        const val FAST_POLL_INTERVAL_US = 10_000L
        const val SLOW_POLL_INTERVAL_US = 10_000_000L
        const val ERROR_POLL_INTERVAL_US = 500_000L
        const val INITIALIZING_DURATION_US = 500_000L
        const val WAIT_FOR_ADVANCE_DURATION_US = 2_000_000L
        const val MAX_AUDIO_TIMESTAMP_OFFSET_US = 5L * C.MICROS_PER_SECOND
        const val MAX_POSITION_DRIFT_ADVANCING_TIMESTAMP_US = 1_000L
        const val MAX_POSITION_DRIFT_FOR_SMOOTHING_US = C.MICROS_PER_SECOND
        const val MAX_POSITION_SMOOTHING_SPEED_CHANGE_PERCENT = 10
        const val WRAP_FROM = 0xC0000000L
        const val WRAP_TO = 0x40000000L

        fun framesToUs(frames: Long, sampleRate: Int): Long {
            if (sampleRate <= 0 || frames <= 0L) return 0L
            return frames * C.MICROS_PER_SECOND / sampleRate
        }

        fun mediaDurationForPlayout(playoutUs: Long, speed: Float): Long {
            val s = if (speed > 0f) speed else 1f
            return if (s == 1f) playoutUs else (playoutUs * s.toDouble()).toLong()
        }
    }
}
