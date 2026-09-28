package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import java.util.ArrayDeque
import kotlin.math.abs

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
        const val RELOCK_TOLERANCE = 0.10
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
