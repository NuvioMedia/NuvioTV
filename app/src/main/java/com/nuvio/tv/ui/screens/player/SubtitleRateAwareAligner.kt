package com.nuvio.tv.ui.screens.player

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * An alignment plan: a frame-rate correction of the target timeline, followed by the piecewise
 * offsets of a [SubtitleSyncModel].
 *
 * [model] was produced by aligning the reference against the target *after* it had been rescaled by
 * [rateRatio], so its segment boundaries and offsets are expressed in the rescaled timeline. That
 * is why [rewrite] must rescale before delegating: scale first, offset second.
 */
internal data class SubtitleSyncPlan(
    val rateRatio: Double,
    val model: SubtitleSyncModel
) {
    val confidence: Double get() = model.confidence

    /** True when the target had to be resampled to a different frame rate to match the reference. */
    val isRescaled: Boolean get() = rateRatio != 1.0

    fun rewrite(document: SrtDocument): SrtDocument = model.rewrite(document.scaledBy(rateRatio))
}

/**
 * Frame-rate aware wrapper around [SubtitleTimingAligner].
 *
 * A subtitle authored against a different frame rate drifts linearly, and [SubtitleTimingAligner]
 * cannot express that: its model is offset-only, and its matching window spans minutes, so drift
 * accumulating past the match tolerance destroys matching outright. Rather than teach the aligner a
 * scale term, this rescales the target by each of a handful of *real* frame-rate ratios, runs the
 * unmodified aligner on each, and keeps the best scoring result.
 *
 * Frame rates are not a continuous space, which is what makes this safe: a continuous scale search
 * finds a plausible-looking fit for any input, whereas only a genuinely misframed track scores well
 * under one of these fixed ratios.
 *
 * ### Safety
 *
 * Drift that is *not* a frame-rate conversion (a stretched or badly retimed release, say) also
 * produces a best-scoring ratio, but a wrong one, and applying it is far worse than declining --
 * measured at 4-27 seconds of median error with 80%+ of the track displaced. Two independent guards
 * keep that out, and a candidate must clear both:
 *
 *  - [MIN_RESCALED_CONFIDENCE]. On a full length reference the correct ratio scores ~0.852 while
 *    every wrong or non-standard fit tops out at ~0.740, so the threshold sits in open space rather
 *    than on a boundary. Confidence is used in preference to segment count because a film with
 *    genuine frame-rate drift *and* an ad break would legitimately produce more than the two
 *    segments a clean recovery yields.
 *
 *  - [MIN_RESCALED_MATCHED_CUES]. Confidence alone is *not* sufficient: a short reference can be
 *    fitted by chance. A 16 cue reference taken from an unrelated title scores 0.881 against a
 *    989 cue target under one of these ratios -- higher than a genuine full length recovery --
 *    because seven hypotheses and a free offset can place a handful of points anywhere. What
 *    separates it is how much evidence the fit rests on: 15 matched cues there, versus 244 for the
 *    shortest legitimate reference measured (a 15 minute partial capture) and 1716 for a full one.
 *
 * Anything failing either guard falls back to the unscaled result -- usually null, so the user sees
 * the existing low-confidence message.
 */
internal object SubtitleRateAwareAligner {

    /**
     * A rescaled alignment is only trusted above this. See the class note: correct fits measure
     * ~0.852 and incorrect ones ~0.740 on a full length feature, so the threshold has room either
     * side rather than being tuned to a boundary.
     */
    private const val MIN_RESCALED_CONFIDENCE = 0.80

    /**
     * Minimum matched cue evidence behind a rescaled alignment.
     *
     * Rescaling tests seven hypotheses where the plain aligner tests one, so it needs proportionally
     * more support to rule out a coincidence. Measured either side of this: spurious fits on short
     * references rest on 15-24 matched cues, while the shortest legitimate reference that recovers
     * real drift (a 15 minute partial playback capture) rests on 244 and a full film on 1716.
     */
    private const val MIN_RESCALED_MATCHED_CUES = 200

    /**
     * Ratios to resample the target by, covering the film/PAL/NTSC conversions that actually occur.
     *
     * Deduplicated by value because several are the same number: 24/23.976 and 30/29.97 are both
     * the 1000/999 NTSC pulldown ratio, as are their inverses. Comparing with a tolerance rather
     * than `distinct()` since these are computed in floating point.
     */
    private val RATE_RATIOS: List<Double> = listOf(
        24.0 / 23.976,   // NTSC pulldown
        23.976 / 24.0,
        25.0 / 24.0,     // PAL speedup from film
        24.0 / 25.0,
        25.0 / 23.976,   // PAL speedup from NTSC film
        23.976 / 25.0,
        30.0 / 29.97,    // identical to 24/23.976, kept for intent
        29.97 / 30.0
    ).fold(mutableListOf()) { distinct, ratio ->
        if (distinct.none { abs(it - ratio) < 1e-9 }) distinct += ratio
        distinct
    }

    fun align(reference: List<SrtCue>, target: List<SrtCue>): SubtitleSyncPlan? {
        // The overwhelmingly common case is a correctly framed subtitle, so it must not pay for the
        // rate scan: a single alignment is ~80ms and every extra ratio costs the same again.
        val direct = SubtitleTimingAligner.align(reference, target)
            ?.let { SubtitleSyncPlan(rateRatio = 1.0, model = it) }
        if (direct != null && direct.confidence >= MIN_RESCALED_CONFIDENCE) return direct

        val document = SrtDocument(target)
        val rescaled = RATE_RATIOS.mapNotNull { ratio ->
            SubtitleTimingAligner.align(reference, document.scaledBy(ratio).cues)
                ?.takeIf {
                    it.confidence >= MIN_RESCALED_CONFIDENCE &&
                        it.matchedCueCount >= MIN_RESCALED_MATCHED_CUES
                }
                ?.let { SubtitleSyncPlan(rateRatio = ratio, model = it) }
        }

        // Ties are broken towards the least aggressive rescale, then the better supported model, so
        // the choice never depends on candidate ordering.
        return rescaled.maxWithOrNull(
            compareBy<SubtitleSyncPlan> { it.confidence }
                .thenByDescending { abs(ln(it.rateRatio)) }
                .thenBy { it.model.matchedCueCount }
                .thenBy { it.rateRatio }
        ) ?: direct
    }
}

/**
 * Resamples every timestamp by [ratio]. Identity for 1.0, so the unscaled path stays bit-for-bit
 * what it was before frame-rate support existed.
 *
 * The end is held at least one millisecond past the start: shrinking ratios can otherwise collapse
 * a very short cue to zero duration, and [SubtitleSyncModel.rewrite] silently drops those.
 */
private fun SrtDocument.scaledBy(ratio: Double): SrtDocument {
    if (ratio == 1.0) return this
    return SrtDocument(
        cues.map { cue ->
            val startMs = (cue.startMs * ratio).roundToLong().coerceAtLeast(0L)
            val endMs = (cue.endMs * ratio).roundToLong().coerceAtLeast(startMs + 1L)
            cue.copy(startMs = startMs, endMs = endMs)
        }
    )
}
