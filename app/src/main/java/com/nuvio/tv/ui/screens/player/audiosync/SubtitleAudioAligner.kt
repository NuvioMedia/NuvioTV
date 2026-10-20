package com.nuvio.tv.ui.screens.player.audiosync

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds where a subtitle file lines up with detected speech.
 *
 * For each candidate playback-rate ratio the subtitle track is rendered to the 32 ms speech grid
 * and the masked Pearson correlation against speech probability is computed for every lag with
 * FFTs. The mapping is media time = subtitle time * scale + shift.
 */
internal object SubtitleAudioAligner {
    /** Common frame-rate mismatches between subtitle and video releases. 1.0 must stay first. */
    val CANDIDATE_SCALES = doubleArrayOf(
        1.0,
        24_000.0 / 23_976.0,
        23_976.0 / 24_000.0,
        25.0 / 23.976,
        23.976 / 25.0,
        25.0 / 24.0,
        24.0 / 25.0,
    )

    /**
     * Silero reacts ~45 ms after speech starts and subtitles usually lead speech slightly, so the raw
     * correlation peak sits this much after the subtitle author's intended timing.
     */
    const val DETECTOR_BIAS_MS = 100.0

    /** Excluded neighbourhood around the peak when measuring how much it stands out. */
    private const val PROMINENCE_EXCLUSION_MS = 2_500.0

    /** Peaks this close to either end of the searched shift range are flagged as unreliable. */
    private const val EDGE_MARGIN_MS = 1_500.0

    /** Keeps a mistaken ~0.1% ratio from winning on noise over the plain 1.0 hypothesis. */
    private const val SCALE_PREFERENCE_MARGIN = 0.01

    data class Estimate(
        val scale: Double,
        val shiftMs: Double,
        /** Pearson correlation at the peak. */
        val peak: Double,
        /** Peak minus the best correlation more than 2.5 s away from it. */
        val prominence: Double,
        val analysedSeconds: Double,
        val speechSeconds: Double,
        val cueCount: Int,
        /** True when the peak sits at the edge of the searched range, so it may not be a real maximum. */
        val atSearchEdge: Boolean = false,
    )

    /**
     * @param probabilities speech probability per frame starting at [fromFrame], NaN when unknown.
     * @param minShiftMs lowest shift considered; together with [maxShiftMs] bounds the lag search.
     */
    fun estimate(
        probabilities: FloatArray,
        fromFrame: Int,
        track: SubtitleSpeechTrack,
        scales: DoubleArray = CANDIDATE_SCALES,
        minShiftMs: Double,
        maxShiftMs: Double,
        /** Latency of the speech detector; 0 when [probabilities] is not detector output. */
        detectorBiasMs: Double = DETECTOR_BIAS_MS,
    ): Estimate? = estimate(listOf(SpeechSegment(fromFrame, probabilities)), track, scales, minShiftMs, maxShiftMs, detectorBiasMs)

    /** Like the single-array form, over separate stretches of known speech (see [SpeechTimeline.segments]). */
    fun estimate(
        segments: List<SpeechSegment>,
        track: SubtitleSpeechTrack,
        scales: DoubleArray = CANDIDATE_SCALES,
        minShiftMs: Double,
        maxShiftMs: Double,
        detectorBiasMs: Double = DETECTOR_BIAS_MS,
    ): Estimate? = prepare(segments, minShiftMs, maxShiftMs, detectorBiasMs)?.estimate(track, scales)

    fun prepare(
        probabilities: FloatArray,
        fromFrame: Int,
        minShiftMs: Double,
        maxShiftMs: Double,
        detectorBiasMs: Double = DETECTOR_BIAS_MS,
    ): PreparedSpeech? = prepare(listOf(SpeechSegment(fromFrame, probabilities)), minShiftMs, maxShiftMs, detectorBiasMs)

    /**
     * Transforms the speech side once so several subtitle files can be scored against the same
     * audio for the cost of one extra FFT pair each. The correlation terms are sums over frames, so
     * separate stretches are transformed on their own and their terms added: exactly the result of
     * one array covering all of them, at the cost of only the known audio. Null when there is too
     * little known speech.
     */
    fun prepare(
        segments: List<SpeechSegment>,
        minShiftMs: Double,
        maxShiftMs: Double,
        detectorBiasMs: Double = DETECTOR_BIAS_MS,
    ): PreparedSpeech? {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val minLag = Math.floorDiv((minShiftMs + detectorBiasMs).roundToInt(), frameMs.toInt())
        val maxLag = Math.floorDiv((maxShiftMs + detectorBiasMs).roundToInt(), frameMs.toInt()) + 1
        val lagCount = maxLag - minLag + 1

        // One mean and norm over every known frame, as if the stretches were a single array.
        var count = 0
        var sum = 0.0
        for (segment in segments) for (p in segment.probabilities) if (!p.isNaN()) {
            count++
            sum += p
        }
        if (count < 2) return null
        val mean = sum / count
        var energy = 0.0
        var speechSeconds = 0.0
        val parts = ArrayList<Part>(segments.size)
        for (segment in segments) {
            val n = segment.probabilities.size
            if (n == 0) continue
            // g covers frames [fromFrame - maxLag, fromFrame + n - minLag).
            val gLength = n + lagCount - 1
            val size = Fft.sizeFor(n + gLength)
            // Audio side, packed as (centered + i * mask), shared by every scale and subtitle.
            val audioRe = DoubleArray(size)
            val audioIm = DoubleArray(size)
            var known = 0
            for (i in 0 until n) {
                val p = segment.probabilities[i]
                if (p.isNaN()) continue
                val c = p - mean
                audioRe[i] = c
                audioIm[i] = 1.0
                energy += c * c
                speechSeconds += p * frameMs / 1_000.0
                known++
            }
            if (known == 0) continue
            val fft = Fft.of(size)
            fft.transform(audioRe, audioIm)
            parts += Part(fft, audioRe, audioIm, n, segment.fromFrame, gLength, fullyKnown = known == n)
        }
        if (energy <= 1e-9 || parts.isEmpty()) return null
        return PreparedSpeech(parts, count, sqrt(energy), speechSeconds, minLag, maxLag, detectorBiasMs)
    }

    /** One transformed stretch of speech. */
    internal class Part(
        val fft: Fft,
        val audioRe: DoubleArray,
        val audioIm: DoubleArray,
        val n: Int,
        val fromFrame: Int,
        val gLength: Int,
        /** No unknown frames: the mask terms are plain window sums (see [accumulateWindowSums]). */
        val fullyKnown: Boolean,
    )

    /**
     * The speech side of a correlation, transformed once; see [prepare]. Correlations already
     * computed for a subtitle and ratio are kept, so asking again (another ratio subset, or a
     * narrower shift range) costs nothing. Not thread-safe.
     */
    class PreparedSpeech internal constructor(
        private val parts: List<Part>,
        private val count: Int,
        private val norm: Double,
        private val speechSeconds: Double,
        private val minLag: Int,
        private val maxLag: Int,
        private val detectorBiasMs: Double,
    ) {
        private val correlations = HashMap<Pair<SubtitleSpeechTrack, Double>, DoubleArray>()

        /**
         * Best mapping of [track] onto the prepared speech among [scales], with the shift searched
         * in [minShiftMs, maxShiftMs] (clipped to the prepared range; null keeps the full range).
         */
        fun estimate(
            track: SubtitleSpeechTrack,
            scales: DoubleArray = CANDIDATE_SCALES,
            minShiftMs: Double? = null,
            maxShiftMs: Double? = null,
        ): Estimate? {
            if (track.size == 0) return null
            val frameMs = SpeechTimeline.FRAME_DURATION_MS
            // Index k = maxLag - lag; a narrower shift range is a slice of the same lags.
            val rangeMaxLag = maxShiftMs?.let { Math.floorDiv((it + detectorBiasMs).roundToInt(), frameMs.toInt()) + 1 }
                ?.coerceAtMost(maxLag) ?: maxLag
            val rangeMinLag = minShiftMs?.let { Math.floorDiv((it + detectorBiasMs).roundToInt(), frameMs.toInt()) }
                ?.coerceAtLeast(minLag) ?: minLag
            if (rangeMinLag > rangeMaxLag) return null
            val fromIndex = maxLag - rangeMaxLag
            val toIndex = maxLag - rangeMinLag + 1
            var best: Estimate? = null
            var bestPeak = Double.NEGATIVE_INFINITY
            var unitScale: Estimate? = null
            computeCorrelations(track, scales.filter { (track to it) !in correlations })
            for (scale in scales) {
                val correlation = correlations.getValue(track to scale).copyOfRange(fromIndex, toIndex)
                val estimate = correlationPeak(correlation, rangeMaxLag, scale, frameMs, detectorBiasMs) ?: continue
                val cues = parts.sumOf { part ->
                    track.countCuesIn(
                        fromMs = part.fromFrame * frameMs,
                        toMs = (part.fromFrame + part.n) * frameMs,
                        scale = scale,
                        shiftMs = estimate.shiftMs,
                    )
                }
                val complete = estimate.copy(
                    analysedSeconds = count * frameMs / 1_000.0,
                    speechSeconds = speechSeconds,
                    cueCount = cues,
                )
                if (scale == 1.0) unitScale = complete
                if (complete.peak > bestPeak) {
                    bestPeak = complete.peak
                    best = complete
                }
            }
            val unit = unitScale
            if (unit != null && best != null && best !== unit && unit.peak >= best.peak - SCALE_PREFERENCE_MARGIN &&
                isNearUnit(best.scale)
            ) {
                return unit
            }
            return best
        }

        /**
         * Masked Pearson correlation of [track] at each of [scales] for every prepared lag (NaN
         * where undefined), added to the cache. On stretches without gaps two ratios share each
         * transform and the mask terms come from window sums: the same numbers for a third of the work.
         */
        private fun computeCorrelations(track: SubtitleSpeechTrack, scales: List<Double>) {
            if (scales.isEmpty()) return
            val lagCount = maxLag - minLag + 1
            val c1 = List(scales.size) { DoubleArray(lagCount) }
            val cm = List(scales.size) { DoubleArray(lagCount) }
            val cm2 = List(scales.size) { DoubleArray(lagCount) }
            for (part in parts) {
                val g = scales.map { track.render(part.fromFrame - maxLag, part.fromFrame - maxLag + part.gLength, it) }
                if (part.fullyKnown) {
                    for (first in scales.indices step 2) {
                        val second = (first + 1).takeIf { it < scales.size }
                        accumulateCrossTerms(part, g[first], second?.let(g::get), lagCount, c1[first], second?.let(c1::get))
                    }
                    for (j in scales.indices) accumulateWindowSums(g[j], part.n, lagCount, cm[j], cm2[j])
                } else {
                    for (j in scales.indices) accumulateTerms(part, g[j], lagCount, c1[j], cm[j], cm2[j])
                }
            }
            for (j in scales.indices) {
                correlations[track to scales[j]] = DoubleArray(lagCount) { k ->
                    val variance = cm2[j][k] - cm[j][k] * cm[j][k] / count
                    if (variance > 1e-6 * count && cm[j][k] > 0.5) c1[j][k] / (norm * sqrt(variance)) else Double.NaN
                }
            }
        }
    }

    private fun isNearUnit(scale: Double): Boolean = abs(scale - 1.0) < 0.002

    /**
     * Adds this stretch's correlation terms per lag: c1 = sum(centered * g), cm = sum(mask * g) and
     * cm2 = sum(mask * g^2), where index k = maxLag - lag.
     */
    private fun accumulateTerms(
        part: Part,
        g: DoubleArray,
        lagCount: Int,
        c1: DoubleArray,
        cm: DoubleArray,
        cm2: DoubleArray,
    ) {
        val fft = part.fft
        val size = fft.size
        val audioRe = part.audioRe
        val audioIm = part.audioIm
        // Subtitle side, packed as (g + i * g^2).
        val subRe = DoubleArray(size)
        val subIm = DoubleArray(size)
        for (i in g.indices) {
            subRe[i] = g[i]
            subIm[i] = g[i] * g[i]
        }
        fft.transform(subRe, subIm)

        // Unpack X = FFT(centered), M = FFT(mask), G = FFT(g), G2 = FFT(g^2) from the packed spectra
        // and form conj(X) * G + i * conj(M) * G (-> c1 + i * cm) and conj(M) * G2 (-> cm2).
        val c1cmRe = DoubleArray(size)
        val c1cmIm = DoubleArray(size)
        val cm2Re = DoubleArray(size)
        val cm2Im = DoubleArray(size)
        for (k in 0 until size) {
            val j = if (k == 0) 0 else size - k
            val aRe = audioRe[k]; val aIm = audioIm[k]
            val aRej = audioRe[j]; val aImj = -audioIm[j]
            val xRe = 0.5 * (aRe + aRej); val xIm = 0.5 * (aIm + aImj)
            val mRe = 0.5 * (aIm - aImj); val mIm = -0.5 * (aRe - aRej)
            val sRe = subRe[k]; val sIm = subIm[k]
            val sRej = subRe[j]; val sImj = -subIm[j]
            val gRe = 0.5 * (sRe + sRej); val gIm = 0.5 * (sIm + sImj)
            val g2Re = 0.5 * (sIm - sImj); val g2Im = -0.5 * (sRe - sRej)
            // conj(X) * G
            val p1Re = xRe * gRe + xIm * gIm
            val p1Im = xRe * gIm - xIm * gRe
            // conj(M) * G
            val p2Re = mRe * gRe + mIm * gIm
            val p2Im = mRe * gIm - mIm * gRe
            c1cmRe[k] = p1Re - p2Im
            c1cmIm[k] = p1Im + p2Re
            cm2Re[k] = mRe * g2Re + mIm * g2Im
            cm2Im[k] = mRe * g2Im - mIm * g2Re
        }
        fft.transform(c1cmRe, c1cmIm, inverse = true)
        fft.transform(cm2Re, cm2Im, inverse = true)
        val inv = 1.0 / size
        for (k in 0 until lagCount) {
            c1[k] += c1cmRe[k] * inv
            cm[k] += c1cmIm[k] * inv
            cm2[k] += cm2Re[k] * inv
        }
    }

    /**
     * Adds sum(centered * g) per lag for one or two subtitle renderings [gA] and [gB] of a stretch
     * without gaps: both real, so they share one forward and one inverse transform.
     */
    private fun accumulateCrossTerms(
        part: Part,
        gA: DoubleArray,
        gB: DoubleArray?,
        lagCount: Int,
        c1A: DoubleArray,
        c1B: DoubleArray?,
    ) {
        val fft = part.fft
        val size = fft.size
        val audioRe = part.audioRe
        val audioIm = part.audioIm
        // Subtitle side, packed as (gA + i * gB).
        val subRe = DoubleArray(size)
        val subIm = DoubleArray(size)
        gA.copyInto(subRe)
        gB?.copyInto(subIm)
        fft.transform(subRe, subIm)
        // Unpack X = FFT(centered), GA and GB, and form conj(X) * GA + i * conj(X) * GB.
        val outRe = DoubleArray(size)
        val outIm = DoubleArray(size)
        for (k in 0 until size) {
            val j = if (k == 0) 0 else size - k
            val xRe = 0.5 * (audioRe[k] + audioRe[j]); val xIm = 0.5 * (audioIm[k] - audioIm[j])
            val aRe = 0.5 * (subRe[k] + subRe[j]); val aIm = 0.5 * (subIm[k] - subIm[j])
            val bRe = 0.5 * (subIm[k] + subIm[j]); val bIm = -0.5 * (subRe[k] - subRe[j])
            val pRe = xRe * aRe + xIm * aIm
            val pIm = xRe * aIm - xIm * aRe
            val qRe = xRe * bRe + xIm * bIm
            val qIm = xRe * bIm - xIm * bRe
            outRe[k] = pRe - qIm
            outIm[k] = pIm + qRe
        }
        fft.transform(outRe, outIm, inverse = true)
        val inv = 1.0 / size
        for (k in 0 until lagCount) {
            c1A[k] += outRe[k] * inv
            c1B?.let { it[k] += outIm[k] * inv }
        }
    }

    /**
     * Adds sum(mask * g) and sum(mask * g^2) per lag for a stretch without gaps, where the mask is
     * all ones and both are sliding sums over [n] frames: g[k] + ... + g[k + n - 1].
     */
    private fun accumulateWindowSums(g: DoubleArray, n: Int, lagCount: Int, cm: DoubleArray, cm2: DoubleArray) {
        val prefix = DoubleArray(g.size + 1)
        val prefixSquares = DoubleArray(g.size + 1)
        for (i in g.indices) {
            prefix[i + 1] = prefix[i] + g[i]
            prefixSquares[i + 1] = prefixSquares[i] + g[i] * g[i]
        }
        for (k in 0 until lagCount) {
            cm[k] += prefix[k + n] - prefix[k]
            cm2[k] += prefixSquares[k + n] - prefixSquares[k]
        }
    }

    /** Peak of [correlation] (index k = [maxLag] - lag), its prominence and refined shift. */
    private fun correlationPeak(
        correlation: DoubleArray,
        maxLag: Int,
        scale: Double,
        frameMs: Double,
        detectorBiasMs: Double,
    ): Estimate? {
        val lagCount = correlation.size
        var peakIndex = -1
        for (k in 0 until lagCount) {
            val value = correlation[k]
            if (!value.isNaN() && (peakIndex < 0 || value > correlation[peakIndex])) peakIndex = k
        }
        if (peakIndex < 0) return null
        val peak = correlation[peakIndex]
        val exclusion = (PROMINENCE_EXCLUSION_MS / frameMs).toInt()
        var runnerUp = Double.NEGATIVE_INFINITY
        for (k in 0 until lagCount) {
            val value = correlation[k]
            if (!value.isNaN() && abs(k - peakIndex) > exclusion && value > runnerUp) runnerUp = value
        }
        var refinedIndex = peakIndex.toDouble()
        if (peakIndex in 1 until lagCount - 1) {
            val left = correlation[peakIndex - 1]
            val right = correlation[peakIndex + 1]
            val curvature = left - 2 * peak + right
            if (!left.isNaN() && !right.isNaN() && curvature < 0) {
                refinedIndex += (0.5 * (left - right) / curvature).coerceIn(-0.5, 0.5)
            }
        }
        val lagFrames = maxLag - refinedIndex
        val edgeFrames = (EDGE_MARGIN_MS / frameMs).toInt()
        return Estimate(
            scale = scale,
            shiftMs = lagFrames * frameMs - detectorBiasMs,
            peak = peak,
            prominence = if (runnerUp.isFinite()) peak - runnerUp else peak,
            analysedSeconds = 0.0,
            speechSeconds = 0.0,
            cueCount = 0,
            atSearchEdge = peakIndex < edgeFrames || peakIndex >= lagCount - edgeFrames,
        )
    }
}
