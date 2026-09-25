package com.nuvio.tv.ui.screens.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal data class SubtitleSyncSpan(
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

internal data class SubtitleSpeechSnapshot(
    val speechSpans: List<SubtitleSyncSpan>,
    val observedSpans: List<SubtitleSyncSpan>,
    val pcmAvailable: Boolean,
    val failureReason: String? = null
)

/** Lightweight readiness result for callers that need to gather audio before running a search. */
internal data class SubtitleAutoSyncAudioEvidence(
    val observedMs: Long,
    val windowCount: Int,
    val ready: Boolean
)

internal enum class SubtitleAutoSyncRejection {
    NONE,
    PCM_UNAVAILABLE,
    NOT_ENOUGH_AUDIO,
    NOT_ENOUGH_DIALOGUE,
    LOW_CONFIDENCE
}

internal data class SubtitleAutoSyncResult(
    val offsetMs: Int,
    val confidence: Double,
    val scoreMargin: Double,
    val sigma: Double,
    val windowAgreement: Double,
    val evidenceWindows: Int,
    val rejection: SubtitleAutoSyncRejection
) {
    val shouldApply: Boolean get() = rejection == SubtitleAutoSyncRejection.NONE
}

/**
 * Finds a single, constant subtitle offset by correlating subtitle activity with detected speech.
 *
 * This intentionally implements only the safe V1 case: a global offset. Drift and recuts must be
 * rejected here rather than partially corrected with a misleading delay.
 */
internal object SubtitleAutoSyncEngine {
    private const val BIN_MS = 100L
    private const val WINDOW_MS = 20_000L
    private const val MIN_TRAILING_WINDOW_MS = 10_000L
    private const val MIN_OBSERVED_MS = 30_000L
    private const val MIN_SPEECH_MS = 2_500L
    private const val MIN_DIALOGUE_CUES = 8
    private const val OFFSET_STEP_MS = 100
    private const val COARSE_OFFSET_STEP_MS = 1_000
    private const val COARSE_SEARCH_THRESHOLD_MS = 360_000
    internal const val LOCAL_FINE_SEARCH_RADIUS_MS = 240_000
    private const val FINE_SEARCH_RADIUS_MS = 2_000
    private const val MAX_FINE_SEARCH_SEEDS = 12
    private const val MIN_SEED_DISTANCE_MS = 5_000
    private const val COMPETING_PEAK_DISTANCE_MS = 2_000
    private const val AGREEMENT_SEARCH_RADIUS_MS = 5_000
    private const val AGREEMENT_TOLERANCE_MS = 700
    private const val ONSET_FULL_MATCH_MS = 250L
    private const val ONSET_MAX_MATCH_MS = 1_800L
    private const val ONSET_SCORE_WEIGHT = 0.18
    private const val MIN_ONSET_CUES = 3
    private const val MIN_WINDOW_SPEECH_MS = 600L
    private const val MIN_WINDOW_F1 = 0.20
    private const val MIN_WINDOW_LOCAL_MARGIN = 0.005
    private const val GLOBAL_CONFIDENCE_THRESHOLD = 0.80
    private const val GLOBAL_MIN_DISTINCT_PEAK_SIGMA = 4.0
    private const val GLOBAL_MIN_SCORE_MARGIN = 0.05
    private const val GLOBAL_MIN_WINDOW_AGREEMENT = 0.75
    private const val GLOBAL_MIN_EVIDENCE_WINDOWS = 3

    // Conservative gate: uncertain matches stay untouched and fall back to the manual picker.
    internal const val CONFIDENCE_THRESHOLD = 0.72
    internal const val MIN_DISTINCT_PEAK_SIGMA = 3.5

    /**
     * Measures usable audio exactly as [findBestOffset] does, without running offset correlation.
     *
     * In particular, short trailing/discontinuous spans that cannot form an evidence window are
     * not included in [SubtitleAutoSyncAudioEvidence.observedMs]. This makes the result suitable
     * for deciding whether an on-demand audio probe should collect another window.
     */
    internal fun measureAudioEvidence(
        snapshot: SubtitleSpeechSnapshot
    ): SubtitleAutoSyncAudioEvidence = prepareAudioEvidence(snapshot).summary

    fun findBestOffset(
        cues: List<SubtitleSyncCue>,
        snapshot: SubtitleSpeechSnapshot,
        minimumOffsetMs: Int? = null,
        maximumOffsetMs: Int? = null
    ): SubtitleAutoSyncResult {
        if (!snapshot.pcmAvailable) {
            return rejected(SubtitleAutoSyncRejection.PCM_UNAVAILABLE)
        }

        val preparedEvidence = prepareAudioEvidence(snapshot)
        val windows = preparedEvidence.windows
        if (!preparedEvidence.summary.ready) {
            return rejected(
                reason = SubtitleAutoSyncRejection.NOT_ENOUGH_AUDIO,
                evidenceWindows = windows.size
            )
        }

        val speech = mergeSpans(snapshot.speechSpans, allowedGapMs = 300L)
        val sampleTimes = buildSampleTimes(windows)
        val speechMask = activityMask(sampleTimes, speech)
        val speechMs = speechMask.count { it } * BIN_MS
        if (speechMs < MIN_SPEECH_MS) {
            return rejected(
                reason = SubtitleAutoSyncRejection.NOT_ENOUGH_DIALOGUE,
                evidenceWindows = windows.size
            )
        }

        val cueFeatures = SubtitleAutoSyncCueProfile.features(cues)
        if (cueFeatures.size < MIN_DIALOGUE_CUES) {
            return rejected(
                reason = SubtitleAutoSyncRejection.NOT_ENOUGH_DIALOGUE,
                evidenceWindows = windows.size
            )
        }
        val dialogueSpans = dialogueSpans(cueFeatures)
        val subtitleSpans = mergeSpans(dialogueSpans, allowedGapMs = BIN_MS)
        val scoringContext = ScoringContext(
            sampleTimes = sampleTimes,
            speechMask = speechMask,
            subtitleSpans = subtitleSpans,
            windows = windows,
            speechOnsets = speech.map { it.startMs }.toLongArray(),
            cueFeatures = cueFeatures
        )

        val derivedMinimumOffsetMs = windows.minOf { it.startMs } - subtitleSpans.maxOf { it.endMs }
        val derivedMaximumOffsetMs = windows.maxOf { it.endMs } - subtitleSpans.minOf { it.startMs }
        val requestedMinimumOffsetMs = minimumOffsetMs?.toLong() ?: derivedMinimumOffsetMs
        val requestedMaximumOffsetMs = maximumOffsetMs?.toLong() ?: derivedMaximumOffsetMs
        val intersectedMinimumOffsetMs = maxOf(
            requestedMinimumOffsetMs,
            SUBTITLE_DELAY_MIN_MS.toLong()
        )
        val intersectedMaximumOffsetMs = minOf(
            requestedMaximumOffsetMs,
            SUBTITLE_DELAY_MAX_MS.toLong()
        )
        // Clamping both ends independently turns an entirely out-of-range timeline into a fake
        // candidate at exactly +/-6 h. Intersect first and reject an empty feasible range.
        if (intersectedMinimumOffsetMs > intersectedMaximumOffsetMs) {
            return rejected(SubtitleAutoSyncRejection.LOW_CONFIDENCE)
        }
        val searchMinimumOffsetMs = intersectedMinimumOffsetMs.toInt()
        val searchMaximumOffsetMs = intersectedMaximumOffsetMs.toInt()

        val hasExplicitSearchBounds = minimumOffsetMs != null || maximumOffsetMs != null
        if (hasExplicitSearchBounds) {
            return evaluateSearch(
                search = searchCandidates(
                    minimumOffsetMs = searchMinimumOffsetMs,
                    maximumOffsetMs = searchMaximumOffsetMs,
                    context = scoringContext
                ),
                windows = windows,
                speech = speech,
                subtitleSpans = subtitleSpans,
                cueFeatures = cueFeatures,
                searchMinimumOffsetMs = searchMinimumOffsetMs,
                searchMaximumOffsetMs = searchMaximumOffsetMs,
                acceptance = SearchAcceptance.standard
            )
        }

        // Most real-world constant subtitle offsets are small. Search +/-4 minutes exhaustively
        // before allowing unrelated scenes elsewhere in a long film to compete in a coarse scan.
        val localMinimumOffsetMs = maxOf(searchMinimumOffsetMs, -LOCAL_FINE_SEARCH_RADIUS_MS)
        val localMaximumOffsetMs = minOf(searchMaximumOffsetMs, LOCAL_FINE_SEARCH_RADIUS_MS)
        val localResult = if (localMinimumOffsetMs <= localMaximumOffsetMs) {
            evaluateSearch(
                search = searchCandidates(
                    minimumOffsetMs = localMinimumOffsetMs,
                    maximumOffsetMs = localMaximumOffsetMs,
                    context = scoringContext,
                    forceFine = true
                ),
                windows = windows,
                speech = speech,
                subtitleSpans = subtitleSpans,
                cueFeatures = cueFeatures,
                searchMinimumOffsetMs = localMinimumOffsetMs,
                searchMaximumOffsetMs = localMaximumOffsetMs,
                acceptance = SearchAcceptance.standard
            )
        } else {
            null
        }
        if (localResult?.shouldApply == true) return localResult

        if (
            localResult != null &&
            localMinimumOffsetMs == searchMinimumOffsetMs &&
            localMaximumOffsetMs == searchMaximumOffsetMs
        ) {
            return localResult
        }

        val globalResult = evaluateSearch(
            search = searchCandidates(
                minimumOffsetMs = searchMinimumOffsetMs,
                maximumOffsetMs = searchMaximumOffsetMs,
                context = scoringContext
            ),
            windows = windows,
            speech = speech,
            subtitleSpans = subtitleSpans,
            cueFeatures = cueFeatures,
            searchMinimumOffsetMs = searchMinimumOffsetMs,
            searchMaximumOffsetMs = searchMaximumOffsetMs,
            acceptance = SearchAcceptance.global
        )
        return if (globalResult.shouldApply) globalResult else localResult ?: globalResult
    }

    private fun evaluateSearch(
        search: CandidateSearch,
        windows: List<SubtitleSyncSpan>,
        speech: List<SubtitleSyncSpan>,
        subtitleSpans: List<SubtitleSyncSpan>,
        cueFeatures: List<SubtitleAutoSyncCueFeature>,
        searchMinimumOffsetMs: Int,
        searchMaximumOffsetMs: Int,
        acceptance: SearchAcceptance
    ): SubtitleAutoSyncResult {
        val candidates = search.candidates
        val best = candidates.maxByOrNull { it.score }
            ?: return rejected(SubtitleAutoSyncRejection.LOW_CONFIDENCE)
        // Confidence statistics must come from a uniformly sampled population. Fine candidates
        // are deliberately concentrated around the strongest coarse peaks and would otherwise
        // bias both the competing-peak margin and the robust spread estimate.
        val statisticsCandidates = search.statisticsCandidates
            .filter { it.hasSubtitleOverlap }
        val competing = statisticsCandidates
            .asSequence()
            .filter { abs(it.offsetMs - best.offsetMs) >= COMPETING_PEAK_DISTANCE_MS }
            .maxByOrNull { it.score }
        val margin = (best.score - (competing?.score ?: 0.0)).coerceAtLeast(0.0)
        val sigma = robustSigma(best.score, statisticsCandidates.map { it.score })

        val agreementEvidence = windows.mapNotNull { window ->
            val windowTimes = buildSampleTimes(listOf(window))
            val windowSpeech = activityMask(windowTimes, speech)
            if (windowSpeech.count { it } * BIN_MS < MIN_WINDOW_SPEECH_MS) {
                return@mapNotNull null
            }

            // A 20-second window is too short to search the entire film independently: a similar
            // dialogue/silence pattern elsewhere can win by chance. Validate the global solution
            // only inside its local neighbourhood. Speech-bearing windows without subtitle
            // coverage remain in the denominator as negative evidence.
            val localMinimum = (best.offsetMs - AGREEMENT_SEARCH_RADIUS_MS)
                .coerceAtLeast(searchMinimumOffsetMs)
            val localMaximum = (best.offsetMs + AGREEMENT_SEARCH_RADIUS_MS)
                .coerceAtMost(searchMaximumOffsetMs)
            val windowContext = ScoringContext(
                sampleTimes = windowTimes,
                speechMask = windowSpeech,
                subtitleSpans = subtitleSpans,
                windows = listOf(window),
                speechOnsets = speech.asSequence()
                    .map { it.startMs }
                    .filter { it in window.startMs until window.endMs }
                    .toList()
                    .toLongArray(),
                cueFeatures = cueFeatures
            )
            val localScores = scoreRange(
                minimumOffsetMs = localMinimum,
                maximumOffsetMs = localMaximum,
                stepMs = OFFSET_STEP_MS,
                context = windowContext
            )
            val scoreAtGlobal = localScores.firstOrNull { it.offsetMs == best.offsetMs }
                ?: scoreOffset(best.offsetMs, windowContext)
            val localBest = localScores.asSequence()
                .filter { it.hasSubtitleOverlap }
                .maxByOrNull { it.score }
                ?: return@mapNotNull false
            val localCompetitor = localScores.asSequence()
                .filter {
                    it.hasSubtitleOverlap &&
                        abs(it.offsetMs - localBest.offsetMs) >= COMPETING_PEAK_DISTANCE_MS
                }
                .maxByOrNull { it.score }
            val localMargin = localBest.score - (localCompetitor?.score ?: -1.0)
            val informative = scoreAtGlobal.hasSubtitleOverlap &&
                scoreAtGlobal.f1 >= MIN_WINDOW_F1 &&
                (localMargin >= MIN_WINDOW_LOCAL_MARGIN || scoreAtGlobal.score >= 0.15)
            informative && abs(localBest.offsetMs - best.offsetMs) <= AGREEMENT_TOLERANCE_MS
        }
        val agreeingWindows = agreementEvidence.count { it }
        val agreement = if (agreementEvidence.isEmpty()) {
            0.0
        } else {
            agreeingWindows.toDouble() / agreementEvidence.size.toDouble()
        }

        val peakQuality = ((best.score + 1.0) / 2.0).coerceIn(0.0, 1.0)
        val f1Quality = best.f1.coerceIn(0.0, 1.0)
        val marginQuality = (margin / 0.12).coerceIn(0.0, 1.0)
        val sigmaQuality = (sigma / 8.0).coerceIn(0.0, 1.0)
        val confidence = (
            peakQuality * 0.35 +
                f1Quality * 0.25 +
                marginQuality * 0.20 +
                sigmaQuality * 0.10 +
                agreement * 0.10
            ).coerceIn(0.0, 1.0)

        val hasEnoughAgreement = agreementEvidence.size >= acceptance.minimumEvidenceWindows &&
            agreement >= acceptance.minimumWindowAgreement
        val hasDistinctPeak = margin >= acceptance.minimumScoreMargin &&
            sigma >= acceptance.minimumDistinctPeakSigma
        val rejection = if (
            confidence >= acceptance.confidenceThreshold &&
            hasEnoughAgreement &&
            hasDistinctPeak &&
            best.f1 >= 0.50 &&
            best.score >= 0.25
        ) {
            SubtitleAutoSyncRejection.NONE
        } else {
            SubtitleAutoSyncRejection.LOW_CONFIDENCE
        }

        return SubtitleAutoSyncResult(
            offsetMs = best.offsetMs,
            confidence = confidence,
            scoreMargin = margin,
            sigma = sigma,
            windowAgreement = agreement,
            evidenceWindows = agreementEvidence.size,
            rejection = rejection
        )
    }

    private data class CandidateScore(
        val offsetMs: Int,
        val score: Double,
        val f1: Double,
        val hasSubtitleOverlap: Boolean
    )

    private data class ScoringContext(
        val sampleTimes: LongArray,
        val speechMask: BooleanArray,
        val subtitleSpans: List<SubtitleSyncSpan>,
        val windows: List<SubtitleSyncSpan>,
        val speechOnsets: LongArray,
        val cueFeatures: List<SubtitleAutoSyncCueFeature>
    )

    private data class CandidateSearch(
        val candidates: List<CandidateScore>,
        val statisticsCandidates: List<CandidateScore>
    )

    private data class SearchAcceptance(
        val confidenceThreshold: Double,
        val minimumDistinctPeakSigma: Double,
        val minimumScoreMargin: Double,
        val minimumWindowAgreement: Double,
        val minimumEvidenceWindows: Int
    ) {
        companion object {
            val standard = SearchAcceptance(
                confidenceThreshold = CONFIDENCE_THRESHOLD,
                minimumDistinctPeakSigma = MIN_DISTINCT_PEAK_SIGMA,
                minimumScoreMargin = 0.035,
                minimumWindowAgreement = 0.60,
                minimumEvidenceWindows = 2
            )
            val global = SearchAcceptance(
                confidenceThreshold = GLOBAL_CONFIDENCE_THRESHOLD,
                minimumDistinctPeakSigma = GLOBAL_MIN_DISTINCT_PEAK_SIGMA,
                minimumScoreMargin = GLOBAL_MIN_SCORE_MARGIN,
                minimumWindowAgreement = GLOBAL_MIN_WINDOW_AGREEMENT,
                minimumEvidenceWindows = GLOBAL_MIN_EVIDENCE_WINDOWS
            )
        }
    }

    private data class PreparedAudioEvidence(
        val windows: List<SubtitleSyncSpan>,
        val summary: SubtitleAutoSyncAudioEvidence
    )

    private fun prepareAudioEvidence(snapshot: SubtitleSpeechSnapshot): PreparedAudioEvidence {
        val observed = mergeSpans(snapshot.observedSpans, allowedGapMs = BIN_MS)
        val windows = selectEvidenceWindows(observed)
        val observedMs = windows.sumOf { it.durationMs }
        return PreparedAudioEvidence(
            windows = windows,
            summary = SubtitleAutoSyncAudioEvidence(
                observedMs = observedMs,
                windowCount = windows.size,
                ready = snapshot.pcmAvailable && observedMs >= MIN_OBSERVED_MS && windows.size >= 2
            )
        )
    }

    /**
     * Searches the whole feasible subtitle timeline without paying the cost of a 100 ms brute-force
     * scan across several hours. Wide ranges are sampled at one second first; the strongest,
     * separated peaks are then refined at the normal 100 ms resolution.
     */
    private fun searchCandidates(
        minimumOffsetMs: Int,
        maximumOffsetMs: Int,
        context: ScoringContext,
        forceFine: Boolean = false
    ): CandidateSearch {
        val rangeMs = maximumOffsetMs.toLong() - minimumOffsetMs.toLong()
        if (forceFine || rangeMs <= COARSE_SEARCH_THRESHOLD_MS) {
            val candidates = scoreRange(
                minimumOffsetMs,
                maximumOffsetMs,
                OFFSET_STEP_MS,
                context
            )
            return CandidateSearch(
                candidates = candidates,
                statisticsCandidates = candidates
            )
        }

        val coarse = scoreRange(
            minimumOffsetMs,
            maximumOffsetMs,
            COARSE_OFFSET_STEP_MS,
            context
        )
        val seeds = buildList<CandidateScore> {
            coarse.sortedByDescending { it.score }.forEach { candidate ->
                if (none { selected -> abs(selected.offsetMs - candidate.offsetMs) < MIN_SEED_DISTANCE_MS }) {
                    add(candidate)
                }
                if (size >= MAX_FINE_SEARCH_SEEDS) return@buildList
            }
        }
        val byOffset = coarse.associateByTo(linkedMapOf()) { it.offsetMs }
        seeds.forEach { seed ->
            val fineMinimum = (seed.offsetMs - FINE_SEARCH_RADIUS_MS).coerceAtLeast(minimumOffsetMs)
            val fineMaximum = (seed.offsetMs + FINE_SEARCH_RADIUS_MS).coerceAtMost(maximumOffsetMs)
            scoreRange(
                fineMinimum,
                fineMaximum,
                OFFSET_STEP_MS,
                context
            ).forEach { candidate -> byOffset[candidate.offsetMs] = candidate }
        }
        return CandidateSearch(
            candidates = byOffset.values.toList(),
            statisticsCandidates = coarse
        )
    }

    private fun scoreRange(
        minimumOffsetMs: Int,
        maximumOffsetMs: Int,
        stepMs: Int,
        context: ScoringContext
    ): List<CandidateScore> = buildList {
        var offset = minimumOffsetMs
        while (offset <= maximumOffsetMs) {
            add(scoreOffset(offset, context))
            if (maximumOffsetMs - offset < stepMs) break
            offset += stepMs
        }
        if (lastOrNull()?.offsetMs != maximumOffsetMs) {
            add(scoreOffset(maximumOffsetMs, context))
        }
    }

    private fun scoreOffset(
        offsetMs: Int,
        context: ScoringContext
    ): CandidateScore {
        var truePositive = 0
        var falsePositive = 0
        var falseNegative = 0
        var trueNegative = 0
        var subtitleIndex = 0

        context.sampleTimes.forEachIndexed { index, mediaTimeMs ->
            val subtitleTimeMs = mediaTimeMs - offsetMs.toLong()
            while (
                subtitleIndex < context.subtitleSpans.size &&
                context.subtitleSpans[subtitleIndex].endMs <= subtitleTimeMs
            ) {
                subtitleIndex++
            }
            val subtitleActive = subtitleIndex < context.subtitleSpans.size &&
                context.subtitleSpans[subtitleIndex].startMs <= subtitleTimeMs &&
                subtitleTimeMs < context.subtitleSpans[subtitleIndex].endMs
            when {
                context.speechMask[index] && subtitleActive -> truePositive++
                !context.speechMask[index] && subtitleActive -> falsePositive++
                context.speechMask[index] -> falseNegative++
                else -> trueNegative++
            }
        }

        val denominator = sqrt(
            (truePositive + falsePositive).toDouble() *
                (truePositive + falseNegative).toDouble() *
                (trueNegative + falsePositive).toDouble() *
                (trueNegative + falseNegative).toDouble()
        )
        val mcc = if (denominator > 0.0) {
            ((truePositive.toDouble() * trueNegative) -
                (falsePositive.toDouble() * falseNegative)) / denominator
        } else {
            -1.0
        }
        val f1Denominator = (2 * truePositive) + falsePositive + falseNegative
        val f1 = if (f1Denominator > 0) {
            (2.0 * truePositive) / f1Denominator.toDouble()
        } else {
            0.0
        }
        val onsetQuality = onsetAlignmentQuality(offsetMs, context)
        val combinedScore = if (onsetQuality == null) {
            mcc
        } else {
            mcc * (1.0 - ONSET_SCORE_WEIGHT) +
                ((onsetQuality * 2.0) - 1.0) * ONSET_SCORE_WEIGHT
        }
        return CandidateScore(
            offsetMs = offsetMs,
            score = combinedScore,
            f1 = f1,
            hasSubtitleOverlap = truePositive + falsePositive > 0
        )
    }

    private fun onsetAlignmentQuality(offsetMs: Int, context: ScoringContext): Double? {
        if (context.speechOnsets.size < MIN_ONSET_CUES) return null
        var weightedScore = 0.0
        var totalWeight = 0.0
        var cueCount = 0
        context.cueFeatures.forEach { cue ->
            val predictedMediaMs = cue.startMs + offsetMs.toLong()
            if (context.windows.none { predictedMediaMs in it.startMs until it.endMs }) return@forEach
            val nearestDistanceMs = nearestDistance(predictedMediaMs, context.speechOnsets)
            val match = when {
                nearestDistanceMs <= ONSET_FULL_MATCH_MS -> 1.0
                nearestDistanceMs >= ONSET_MAX_MATCH_MS -> 0.0
                else -> 1.0 -
                    (nearestDistanceMs - ONSET_FULL_MATCH_MS).toDouble() /
                    (ONSET_MAX_MATCH_MS - ONSET_FULL_MATCH_MS).toDouble()
            }
            weightedScore += match * cue.weight
            totalWeight += cue.weight
            cueCount++
        }
        if (cueCount < MIN_ONSET_CUES || totalWeight <= 0.0) return null
        return weightedScore / totalWeight
    }

    private fun nearestDistance(targetMs: Long, sortedTimes: LongArray): Long {
        val index = sortedTimes.binarySearch(targetMs)
        if (index >= 0) return 0L
        val insertion = -index - 1
        val before = sortedTimes.getOrNull(insertion - 1)?.let { abs(targetMs - it) }
        val after = sortedTimes.getOrNull(insertion)?.let { abs(it - targetMs) }
        return listOfNotNull(before, after).minOrNull() ?: Long.MAX_VALUE
    }

    private fun dialogueSpans(
        cues: List<SubtitleAutoSyncCueFeature>
    ): List<SubtitleSyncSpan> =
        cues.map { cue ->
                SubtitleSyncSpan(
                    startMs = cue.startMs,
                    endMs = max(cue.startMs + BIN_MS, cue.endMs)
                )
            }
            .filter { it.durationMs in BIN_MS..15_000L }

    private fun buildSampleTimes(windows: List<SubtitleSyncSpan>): LongArray =
        windows.flatMap { window ->
            buildList {
                var timeMs = window.startMs + (BIN_MS / 2L)
                while (timeMs < window.endMs) {
                    add(timeMs)
                    timeMs += BIN_MS
                }
            }
        }.toLongArray()

    private fun activityMask(
        sampleTimes: LongArray,
        spans: List<SubtitleSyncSpan>
    ): BooleanArray {
        var spanIndex = 0
        return BooleanArray(sampleTimes.size) { sampleIndex ->
            val timeMs = sampleTimes[sampleIndex]
            while (spanIndex < spans.size && spans[spanIndex].endMs <= timeMs) {
                spanIndex++
            }
            spanIndex < spans.size &&
                spans[spanIndex].startMs <= timeMs &&
                timeMs < spans[spanIndex].endMs
        }
    }

    private fun selectEvidenceWindows(observedSpans: List<SubtitleSyncSpan>): List<SubtitleSyncSpan> {
        val allWindows = buildList {
            observedSpans.forEach { observed ->
                var startMs = observed.startMs
                while (startMs < observed.endMs) {
                    val endMs = minOf(startMs + WINDOW_MS, observed.endMs)
                    if (endMs - startMs >= MIN_TRAILING_WINDOW_MS) {
                        add(SubtitleSyncSpan(startMs, endMs))
                    }
                    startMs = endMs
                }
            }
        }
        return allWindows
    }

    internal fun mergeSpans(
        spans: List<SubtitleSyncSpan>,
        allowedGapMs: Long = 0L
    ): List<SubtitleSyncSpan> {
        val sorted = spans
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()

        val result = ArrayList<SubtitleSyncSpan>(sorted.size)
        var current = sorted.first()
        for (index in 1 until sorted.size) {
            val next = sorted[index]
            if (next.startMs <= current.endMs + allowedGapMs) {
                current = current.copy(endMs = max(current.endMs, next.endMs))
            } else {
                result += current
                current = next
            }
        }
        result += current
        return result
    }

    private fun robustSigma(bestScore: Double, scores: List<Double>): Double {
        if (scores.size < 3) return 0.0
        val median = median(scores)
        val deviations = scores.map { abs(it - median) }
        val mad = median(deviations)
        val scale = if (mad >= 1e-6) {
            1.4826 * mad
        } else {
            // A zero MAD is common for discrete activity masks. It must not automatically turn
            // any score above the median into a maximum-strength peak. RMS deviation provides a
            // finite fallback, while a genuinely flat population remains at zero significance.
            sqrt(deviations.sumOf { it * it } / deviations.size.toDouble())
        }
        if (scale < 1e-6) return 0.0
        return ((bestScore - median) / scale).coerceIn(0.0, 12.0)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun rejected(
        reason: SubtitleAutoSyncRejection,
        evidenceWindows: Int = 0
    ) = SubtitleAutoSyncResult(
        offsetMs = 0,
        confidence = 0.0,
        scoreMargin = 0.0,
        sigma = 0.0,
        windowAgreement = 0.0,
        evidenceWindows = evidenceWindows,
        rejection = reason
    )
}
