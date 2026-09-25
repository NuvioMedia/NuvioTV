package com.nuvio.tv.ui.screens.player

import kotlin.math.abs

/**
 * Promotes a stable offset repeated across independent probe snapshots.
 *
 * Individual snapshots can narrowly miss the engine's sigma/agreement gates. Repeating the same
 * offset with a distinct peak across three disjoint audio samples is stronger evidence than any one
 * sample alone, while unrelated whole-film aliases jump by minutes between probes. Callers must
 * never pass cumulative snapshots here.
 */
internal object SubtitleAutoSyncProbeConsensus {
    private const val MIN_RESULTS = 3
    private const val OFFSET_TOLERANCE_MS = 700
    private const val MIN_CONFIDENCE = SubtitleAutoSyncEngine.CONFIDENCE_THRESHOLD
    private const val MIN_MARGIN = 0.035
    private const val MIN_WINDOW_AGREEMENT = 0.50
    private const val MIN_EVIDENCE_WINDOWS = 3

    fun stableResult(results: List<SubtitleAutoSyncResult>): SubtitleAutoSyncResult? {
        val eligible = results.filter { result ->
            result.confidence >= MIN_CONFIDENCE &&
                result.scoreMargin >= MIN_MARGIN &&
                result.windowAgreement >= MIN_WINDOW_AGREEMENT &&
                result.evidenceWindows >= MIN_EVIDENCE_WINDOWS
        }
        if (eligible.size < MIN_RESULTS) return null

        val cluster = eligible.asSequence()
            .map { anchor ->
                eligible.filter { candidate ->
                    abs(candidate.offsetMs.toLong() - anchor.offsetMs.toLong()) <=
                        OFFSET_TOLERANCE_MS
                }
            }
            .filter { it.size >= MIN_RESULTS }
            .maxWithOrNull(
                compareBy<List<SubtitleAutoSyncResult>> { it.size }
                    .thenBy { group -> group.sumOf { it.confidence } }
            )
            ?: return null

        val medianOffsetMs = cluster.map { it.offsetMs }.sorted().let { offsets ->
            offsets[offsets.size / 2]
        }
        val stableCluster = cluster.filter { result ->
            abs(result.offsetMs.toLong() - medianOffsetMs.toLong()) <= OFFSET_TOLERANCE_MS
        }
        if (stableCluster.size < MIN_RESULTS) return null

        val representative = stableCluster.maxBy { it.confidence }
        return representative.copy(
            offsetMs = medianOffsetMs,
            confidence = stableCluster.map { it.confidence }.average(),
            scoreMargin = stableCluster.minOf { it.scoreMargin },
            sigma = stableCluster.map { it.sigma }.average(),
            windowAgreement = stableCluster.map { it.windowAgreement }.average(),
            evidenceWindows = stableCluster.maxOf { it.evidenceWindows },
            rejection = SubtitleAutoSyncRejection.NONE
        )
    }
}
