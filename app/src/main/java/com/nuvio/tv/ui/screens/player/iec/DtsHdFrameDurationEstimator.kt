package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Chooses the IEC burst period for a core-less DTS:X / DTS-UHD access unit.
 *
 * DTS-UHD frames are 384, 480 or 512 clock periods times 1..8 (ETSI TS 103 491
 * Table 6-13). Matroska's default 1 ms timecode cannot name those durations
 * exactly, so a single PTS delta is snapped to that grid and a short running
 * mean separates a 480-sample stream from a 512-sample stream that alternates
 * 10 and 11 ms.
 *
 * The 512-sample default is a real lock. 480 and 512 sit inside 10% of each
 * other, so a single 10 ms delta must not commit to 480 — that is also the
 * first half of a 512-sample Matroska 10/11 ms pair. A second 10 ms delta
 * relocks to 480. Grid snap is tighter than that relock: a 44.1 kHz
 * 512-sample frame is 557 samples at 48 kHz (192 kHz IEC) and must not
 * collapse to 512. At 176.4 kHz IEC the clock is 44.1 kHz, so that frame
 * is 512 samples and a legal type-IV period. A header-derived count is
 * trusted immediately. After a seek the previous PTS is cleared but the last
 * resolved count is kept, so the first unit is not resized to the 512-sample
 * default.
 */
internal class DtsHdFrameDurationEstimator {

    var lastPtsUs: Long = C.TIME_UNSET
        private set
    var resolvedSampleCount: Int = 0
        private set
    var lastUhdDurationUs: Long = C.TIME_UNSET
        private set

    var clockSampleRate: Int = 48_000

    private val recentRawCounts = ArrayDeque<Int>()

    fun reset() {
        lastPtsUs = C.TIME_UNSET
        resolvedSampleCount = 0
        lastUhdDurationUs = C.TIME_UNSET
        clockSampleRate = 48_000
        recentRawCounts.clear()
    }

    fun clearPts() {
        lastPtsUs = C.TIME_UNSET
        recentRawCounts.clear()
    }

    fun observeKnownCount(sampleCount: Int) {
        val snapped = snapToUhdGrid(sampleCount.toDouble())
        if (snapped <= 0) return
        resolvedSampleCount = snapped
        recentRawCounts.clear()
    }

    fun rememberUhdDurationUs(durationUs: Long) {
        if (durationUs == C.TIME_UNSET || durationUs <= 0L) return
        lastUhdDurationUs = durationUs
        observeKnownCount(dtsSampleCountFromPtsDeltaUs(durationUs, clockSampleRate))
    }

    /** 48 kHz-equivalent samples from the last UHD sync duration; 0 if none yet. */
    fun sampleCountFromUhdCache(): Int {
        if (lastUhdDurationUs == C.TIME_UNSET || lastUhdDurationUs <= 0L) return 0
        val raw = dtsSampleCountFromPtsDeltaUs(lastUhdDurationUs, clockSampleRate)
        if (raw <= 0) return 0
        return snapToUhdGrid(raw.toDouble())
    }

    fun notePts(ptsUs: Long) {
        if (ptsUs != C.TIME_UNSET) lastPtsUs = ptsUs
    }

    fun resolveFromPts(ptsUs: Long): Int {
        if (ptsUs == C.TIME_UNSET) return resolvedOrDefault()
        val previousPtsUs = lastPtsUs
        if (previousPtsUs == C.TIME_UNSET) {
            lastPtsUs = ptsUs
            return resolvedOrDefault()
        }
        // Duplicate or backward timestamps are not a frame duration. Keep the
        // cursor on the last monotonic PTS so the next real delta is intact.
        if (ptsUs <= previousPtsUs) return resolvedOrDefault()
        lastPtsUs = ptsUs
        val cached = sampleCountFromUhdCache()
        if (cached > 0) return cached
        val raw = dtsSampleCountFromPtsDeltaUs(ptsUs - previousPtsUs, clockSampleRate)
        if (raw in MIN_DTS_SAMPLE_COUNT..MAX_DTS_SAMPLE_COUNT) {
            return observeRawCount(raw)
        }
        return resolvedOrDefault()
    }

    private fun observeRawCount(raw: Int): Int {
        recentRawCounts.addLast(raw)
        while (recentRawCounts.size > MEAN_WINDOW) recentRawCounts.removeFirst()
        val mean = recentRawCounts.sum().toDouble() / recentRawCounts.size
        val snapped = snapToUhdGrid(mean)
        resolvedSampleCount = when {
            resolvedSampleCount !in MIN_DTS_SAMPLE_COUNT..MAX_DTS_SAMPLE_COUNT -> snapped
            recentRawCounts.size >= MIN_SAMPLES_TO_RELOCK -> snapped
            relativeError(raw, resolvedSampleCount) > RELOCK_TOLERANCE -> snapped
            else -> resolvedSampleCount
        }
        return resolvedSampleCount
    }

    private fun resolvedOrDefault(): Int {
        if (resolvedSampleCount !in MIN_DTS_SAMPLE_COUNT..MAX_DTS_SAMPLE_COUNT) {
            resolvedSampleCount = DEFAULT_DTS_SAMPLE_COUNT
        }
        return resolvedSampleCount
    }

    companion object {
        const val DEFAULT_DTS_SAMPLE_COUNT = 512
        const val MIN_DTS_SAMPLE_COUNT = 128
        const val MAX_DTS_SAMPLE_COUNT = 8_192
        // 480 vs 512 is 6.25%; a single 10 ms delta must not steal the default.
        const val RELOCK_TOLERANCE = 0.10
        // Matroska 1 ms on a 512-sample 48 kHz frame is 3.1% (528). 44.1 kHz
        // 512-sample frames are 8.8% (557) and must keep the 48 kHz equivalent.
        const val GRID_SNAP_TOLERANCE = 0.05
        private const val MEAN_WINDOW = 4
        private const val MIN_SAMPLES_TO_RELOCK = 2

        val UHD_FRAME_SAMPLE_COUNTS: IntArray =
            intArrayOf(512, 480, 384)
                .flatMap { base -> (1..8).map { base * it } }
                .distinct()
                .sorted()
                .toIntArray()

        fun dtsSampleCountFromPtsDeltaUs(deltaUs: Long, sampleRate: Int = 48_000): Int {
            if (deltaUs <= 0L) return DEFAULT_DTS_SAMPLE_COUNT
            val rate = if (sampleRate > 0) sampleRate else 48_000
            val fromDelta = (deltaUs * rate.toLong() + 500_000L) / 1_000_000L
            return if (fromDelta in MIN_DTS_SAMPLE_COUNT.toLong()..MAX_DTS_SAMPLE_COUNT.toLong()) {
                fromDelta.toInt()
            } else {
                0
            }
        }

        fun snapToUhdGrid(sampleCount: Double): Int {
            if (sampleCount <= 0.0) return DEFAULT_DTS_SAMPLE_COUNT
            var best = DEFAULT_DTS_SAMPLE_COUNT
            var bestError = Double.MAX_VALUE
            for (candidate in UHD_FRAME_SAMPLE_COUNTS) {
                val error = abs(sampleCount - candidate) / candidate
                if (error < bestError) {
                    bestError = error
                    best = candidate
                }
            }
            return if (bestError <= GRID_SNAP_TOLERANCE) {
                best
            } else {
                sampleCount.toInt().coerceIn(MIN_DTS_SAMPLE_COUNT, MAX_DTS_SAMPLE_COUNT)
            }
        }

        private fun relativeError(value: Int, reference: Int): Double =
            abs(value - reference).toDouble() / reference
    }
}
