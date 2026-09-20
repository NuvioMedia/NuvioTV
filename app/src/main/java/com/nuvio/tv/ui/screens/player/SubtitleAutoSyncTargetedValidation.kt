package com.nuvio.tv.ui.screens.player

import kotlin.math.abs

/**
 * Bounded second opinion for a plausible local offset that narrowly missed the normal safety gate.
 *
 * Confirmation results must come from independent probe windows. Cumulative results are used only
 * to nominate one candidate and are never counted as separate confirmations.
 */
internal object SubtitleAutoSyncTargetedValidation {
    const val SEARCH_RADIUS_MS = 3_000
    const val MAX_PROBES = 2

    private const val MIN_CANDIDATE_ABS_OFFSET_MS = 500
    private const val MIN_FINAL_CANDIDATE_WINDOWS = 4

    private const val MIN_INDEPENDENT_CANDIDATE_CONFIDENCE = 0.48
    private const val MIN_INDEPENDENT_CANDIDATE_MARGIN = 0.02
    private const val MIN_INDEPENDENT_CANDIDATE_SIGMA = 1.25
    private const val MIN_INDEPENDENT_CANDIDATE_AGREEMENT = 0.20
    private const val MIN_INDEPENDENT_CANDIDATE_WINDOWS = 2

    private const val MIN_CONFIRMATION_SIGMA = 1.25
    private const val MIN_CONFIRMATION_WINDOWS = 2
    private const val CONFIRMATION_OFFSET_TOLERANCE_MS = SEARCH_RADIUS_MS

    fun shouldStart(
        candidate: SubtitleAutoSyncResult,
        isLastStandardProbe: Boolean
    ): Boolean = isLastStandardProbe && isEligibleFinalCandidate(candidate)

    /**
     * Allows the final, complete standard probe to nominate a local candidate independently of a
     * misleading cumulative peak. This is deliberately a nomination only: two new, separate
     * validation probes must still confirm it before any delay is applied.
     */
    fun shouldStartFromFinalIndependentProbe(candidate: SubtitleAutoSyncResult): Boolean =
        candidate.rejection != SubtitleAutoSyncRejection.PCM_UNAVAILABLE &&
            candidate.rejection != SubtitleAutoSyncRejection.NOT_ENOUGH_AUDIO &&
            candidate.rejection != SubtitleAutoSyncRejection.NOT_ENOUGH_DIALOGUE &&
            abs(candidate.offsetMs.toLong()) in
            MIN_CANDIDATE_ABS_OFFSET_MS.toLong()..SubtitleAutoSyncEngine.LOCAL_FINE_SEARCH_RADIUS_MS.toLong() &&
            candidate.confidence >= MIN_INDEPENDENT_CANDIDATE_CONFIDENCE &&
            candidate.scoreMargin >= MIN_INDEPENDENT_CANDIDATE_MARGIN &&
            candidate.sigma >= MIN_INDEPENDENT_CANDIDATE_SIGMA &&
            candidate.windowAgreement >= MIN_INDEPENDENT_CANDIDATE_AGREEMENT &&
            candidate.evidenceWindows >= MIN_INDEPENDENT_CANDIDATE_WINDOWS

    fun confirmedResult(
        candidate: SubtitleAutoSyncResult,
        independentResults: List<SubtitleAutoSyncResult>
    ): SubtitleAutoSyncResult? {
        if (independentResults.size < MAX_PROBES) return null
        val confirmations = independentResults.take(MAX_PROBES)
        if (confirmations.any { !confirms(candidate.offsetMs, it) }) return null

        val offsets = (confirmations.map { it.offsetMs } + candidate.offsetMs).sorted()
        return candidate.copy(
            offsetMs = offsets[offsets.size / 2],
            confidence = confirmations.map { it.confidence }.average(),
            scoreMargin = confirmations.minOf { it.scoreMargin },
            sigma = confirmations.map { it.sigma }.average(),
            windowAgreement = 1.0,
            evidenceWindows = confirmations.sumOf { it.evidenceWindows },
            rejection = SubtitleAutoSyncRejection.NONE
        )
    }

    fun confirms(candidateOffsetMs: Int, result: SubtitleAutoSyncResult): Boolean =
        result.rejection != SubtitleAutoSyncRejection.PCM_UNAVAILABLE &&
            result.rejection != SubtitleAutoSyncRejection.NOT_ENOUGH_AUDIO &&
            result.rejection != SubtitleAutoSyncRejection.NOT_ENOUGH_DIALOGUE &&
            abs(result.offsetMs.toLong() - candidateOffsetMs.toLong()) <=
            CONFIRMATION_OFFSET_TOLERANCE_MS &&
            result.sigma >= MIN_CONFIRMATION_SIGMA &&
            result.evidenceWindows >= MIN_CONFIRMATION_WINDOWS

    private fun isEligibleFinalCandidate(result: SubtitleAutoSyncResult): Boolean =
        result.rejection == SubtitleAutoSyncRejection.LOW_CONFIDENCE &&
            abs(result.offsetMs.toLong()) in
            MIN_CANDIDATE_ABS_OFFSET_MS.toLong()..SubtitleAutoSyncEngine.LOCAL_FINE_SEARCH_RADIUS_MS.toLong() &&
            result.evidenceWindows >= MIN_FINAL_CANDIDATE_WINDOWS
}

/**
 * Picks dialogue-dense, mutually separated windows that do not overlap standard probe evidence.
 * Returned positions are media times after applying the proposed subtitle offset.
 */
internal fun planSubtitleAutoSyncValidationPositions(
    cues: List<SubtitleSyncCue>,
    candidateOffsetMs: Int,
    durationMs: Long,
    excludedObservedSpans: List<SubtitleSyncSpan>,
    maxAttempts: Int = SubtitleAutoSyncTargetedValidation.MAX_PROBES
): List<Long> {
    if (maxAttempts <= 0) return emptyList()

    val predictedCueTimes = cues.asSequence()
        .filter { cue ->
            cue.endTimeMs > cue.startTimeMs &&
                cue.endTimeMs - cue.startTimeMs <= 15_000L &&
                cue.text.any { it.isLetterOrDigit() }
        }
        .map { cue -> (cue.startTimeMs + candidateOffsetMs.toLong()).coerceAtLeast(0L) }
        .filter { durationMs <= 0L || it < durationMs }
        .sorted()
        .toList()
    if (predictedCueTimes.size < 8) return emptyList()

    data class DenseWindow(val preferredStartMs: Long, val effectiveStartMs: Long, val cueCount: Int)

    var right = 0
    val denseWindows = buildList {
        predictedCueTimes.forEachIndexed { index, predictedTimeMs ->
            if (right < index) right = index
            val preferredStartMs = predictedTimeMs
            val effectiveStartMs = validationEffectiveStart(
                preferredStartMs = preferredStartMs,
                durationMs = durationMs
            )
            val windowEndMs = effectiveStartMs + 60_000L
            while (right < predictedCueTimes.size && predictedCueTimes[right] < windowEndMs) {
                right++
            }
            val cueCount = right - predictedCueTimes.binarySearch(effectiveStartMs).let { found ->
                if (found >= 0) found else -found - 1
            }
            if (cueCount >= 8) {
                add(DenseWindow(preferredStartMs, effectiveStartMs, cueCount))
            }
        }
    }.sortedWith(
        compareByDescending<DenseWindow> { it.cueCount }
            .thenBy { it.effectiveStartMs }
    )

    val selected = mutableListOf<DenseWindow>()
    for (candidate in denseWindows) {
        val candidateSpan = SubtitleSyncSpan(
            candidate.effectiveStartMs,
            candidate.effectiveStartMs + 60_000L
        )
        val overlapsExistingEvidence = excludedObservedSpans.any { observed ->
            candidateSpan.startMs < observed.endMs && observed.startMs < candidateSpan.endMs
        }
        if (overlapsExistingEvidence) continue
        if (selected.any { abs(it.effectiveStartMs - candidate.effectiveStartMs) < 90_000L }) {
            continue
        }
        selected += candidate
        if (selected.size >= maxAttempts) break
    }

    return selected.take(maxAttempts).map { it.preferredStartMs }
}

private fun validationEffectiveStart(preferredStartMs: Long, durationMs: Long): Long {
    val preRolled = (preferredStartMs - 5_000L).coerceAtLeast(0L)
    if (durationMs <= 0L) return preRolled
    return preRolled.coerceAtMost((durationMs - 60_000L).coerceAtLeast(0L))
}
