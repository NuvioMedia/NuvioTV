package com.nuvio.tv.ui.screens.player

import kotlin.math.abs
import kotlin.math.roundToLong

internal data class SubtitleSyncSegment(
    val targetStartMs: Long,
    val targetEndMs: Long,
    val offsetMs: Long,
    val confidence: Double
)

internal data class SubtitleSyncModel(
    val segments: List<SubtitleSyncSegment>,
    val confidence: Double,
    val matchedCueCount: Int
) {
    fun rewrite(document: SrtDocument): SrtDocument {
        val rewritten = document.cues.mapNotNull { cue ->
            val segment = segments.firstOrNull { cue.startMs >= it.targetStartMs && cue.startMs < it.targetEndMs }
                ?: return@mapNotNull cue
            val startMs = cue.startMs + segment.offsetMs
            val endMs = cue.endMs + segment.offsetMs
            if (endMs <= 0L || endMs <= startMs) return@mapNotNull null
            SrtCue(startMs.coerceAtLeast(0L), endMs.coerceAtLeast(0L), cue.text)
        }
        return SrtDocument(rewritten.sortedBy(SrtCue::startMs))
    }
}

/**
 * Aligns translated subtitles from timing alone. Symmetric onset matching is resilient to
 * translated tracks splitting the same dialogue differently, while overlapping cue-count windows
 * allow sustained offset changes caused by inserted scenes or ads.
 */
internal object SubtitleTimingAligner {
    private const val BIN_MS = 500L
    private const val MAX_ABS_OFFSET_MS = 20L * 60L * 1000L
    private const val MATCH_TOLERANCE_MS = 1_500L
    private const val COVERAGE_PADDING_MS = 60L * 1000L
    private const val MIN_TOTAL_CUES = 12
    private const val MIN_WINDOW_CUES = 6
    private const val MIN_WINDOW_REFERENCE_MATCH_RATIO = 0.4
    private const val WINDOW_POINT_COUNT = 32
    private const val WINDOW_STEP_POINTS = 16
    private const val MIN_SEGMENT_WINDOWS = 1
    private const val OFFSET_MERGE_TOLERANCE_MS = 1_500L
    private const val MIN_REFERENCE_COVERAGE = 0.55
    private const val MAX_UNSUPPORTED_GAP_MS = 3L * 60L * 1000L
    private const val MIN_PARTIAL_CONSTANT_SCORE = 0.75
    private const val MIN_PARTIAL_CONSTANT_MARGIN = 0.06
    private const val MIN_PARTIAL_CONSTANT_MATCH_RATIO = 0.75
    private const val MIN_PARTIAL_CONSTANT_SPAN_MS = 30L * 1000L
    private const val MAX_PARTIAL_SCORE_POINTS = 128

    fun align(reference: List<SrtCue>, target: List<SrtCue>): SubtitleSyncModel? {
        val referencePoints = timingPoints(reference)
        val targetPoints = timingPoints(target)
        if (referencePoints.size < MIN_TOTAL_CUES || targetPoints.size < MIN_TOTAL_CUES) return null

        val candidates = globalOffsetCandidates(referencePoints, targetPoints)
        if (candidates.isEmpty()) return null

        val windows = localWindows(referencePoints, targetPoints, candidates)
        if (windows.isEmpty()) return null
        val groups = mergeWindows(windows).filter { it.windows.size >= MIN_SEGMENT_WINDOWS }
        if (groups.isEmpty()) return null

        val acceptedGroups = groups.filter { it.confidence >= 0.36 && it.matchedCueCount >= MIN_WINDOW_CUES }
        if (acceptedGroups.isEmpty()) return null
        val partialConstantScore = strongPartialConstantScore(
            acceptedGroups,
            referencePoints,
            targetPoints,
            candidates
        )
        val allowPartialConstant = partialConstantScore != null
        if (!allowPartialConstant) {
            val firstSupportedTargetMs = acceptedGroups.minOf { it.referenceStartMs - it.offsetMs }
            val lastSupportedTargetMs = acceptedGroups.maxOf { it.referenceEndMs - it.offsetMs }
            if (firstSupportedTargetMs > targetPoints.first() + COVERAGE_PADDING_MS ||
                lastSupportedTargetMs < targetPoints.last() - COVERAGE_PADDING_MS
            ) return null
        }
        val targetEndMs = target.maxOf(SrtCue::endMs) + 1L
        val segments = buildSegments(acceptedGroups, targetEndMs)
        if (segments.isEmpty()) return null

        val targetSpan = (targetPoints.last() - targetPoints.first()).coerceAtLeast(1L)
        val coverageIntervals = acceptedGroups
            .map { it.referenceStartMs..it.referenceEndMs }
            .sortedBy(LongRange::first)
            .fold(mutableListOf<LongRange>()) { merged, interval ->
                val previous = merged.lastOrNull()
                if (previous != null && interval.first <= previous.last) {
                    merged[merged.lastIndex] = previous.first..maxOf(previous.last, interval.last)
                } else {
                    merged += interval
                }
                merged
            }
        val coveredMs = coverageIntervals.sumOf { (it.last - it.first).coerceAtLeast(0L) }
        val coverageRatio = coveredMs.toDouble() / targetSpan
        if (!allowPartialConstant) {
            if (coverageRatio < MIN_REFERENCE_COVERAGE) return null
            if (coverageIntervals.zipWithNext().any { (before, after) ->
                    after.first - before.last > MAX_UNSUPPORTED_GAP_MS
                }
            ) return null
        }

        val totalMatched = acceptedGroups.sumOf(Group::matchedCueCount)
        if (totalMatched < MIN_TOTAL_CUES) return null
        val confidence = partialConstantScore ?: acceptedGroups
            .sumOf { it.confidence * it.matchedCueCount } / totalMatched.coerceAtLeast(1)
        if (confidence < 0.4) return null
        return SubtitleSyncModel(segments, confidence.coerceIn(0.0, 1.0), totalMatched)
    }

    private fun timingPoints(cues: List<SrtCue>): List<Long> = cues.map(SrtCue::startMs).distinct().sorted()

    private fun globalOffsetCandidates(reference: List<Long>, target: List<Long>): List<Long> {
        val histogram = HashMap<Long, Int>()
        for (referenceMs in reference) {
            for (targetMs in target) {
                val difference = referenceMs - targetMs
                if (abs(difference) > MAX_ABS_OFFSET_MS) continue
                val bin = (difference.toDouble() / BIN_MS).roundToLong()
                histogram[bin] = (histogram[bin] ?: 0) + 1
            }
        }
        return histogram.entries
            .sortedByDescending(Map.Entry<Long, Int>::value)
            .map { it.key * BIN_MS }
            .fold(mutableListOf<Long>()) { selected, value ->
                if (selected.none { abs(it - value) < OFFSET_MERGE_TOLERANCE_MS }) selected += value
                selected
            }
            .take(32)
    }

    private fun localWindows(
        reference: List<Long>,
        target: List<Long>,
        candidates: List<Long>
    ): List<WindowMatch> {
        val result = mutableListOf<WindowMatch>()
        var startIndex = 0
        while (startIndex < reference.size) {
            val endIndex = minOf(startIndex + WINDOW_POINT_COUNT, reference.size)
            val localReference = reference.subList(startIndex, endIndex)
            if (localReference.size >= MIN_WINDOW_CUES) {
                val scored = candidates.map { scoreOffset(localReference, target, it) }
                    .sortedByDescending(WindowScore::score)
                val best = scored.first()
                val secondScore = scored.firstOrNull {
                    abs(it.offsetMs - best.offsetMs) > MATCH_TOLERANCE_MS
                }?.score ?: 0.0
                val margin = (best.score - secondScore).coerceAtLeast(0.0)
                val confidence = (best.score * 0.8 + margin * 0.6).coerceIn(0.0, 1.0)
                if (best.matches.size >= MIN_WINDOW_CUES &&
                    best.matches.size.toDouble() / localReference.size >= MIN_WINDOW_REFERENCE_MATCH_RATIO &&
                    best.score >= 0.32
                ) {
                    result += WindowMatch(
                        referenceStartMs = localReference.first(),
                        referenceEndMs = localReference.last() + 1L,
                        offsetMs = refineOffset(best),
                        confidence = confidence,
                        matchedCueCount = best.matches.size
                    )
                }
            }
            if (endIndex == reference.size) break
            startIndex += WINDOW_STEP_POINTS
        }
        return result
    }

    private fun scoreOffset(reference: List<Long>, target: List<Long>, offsetMs: Long): WindowScore {
        val expectedStart = reference.first() - offsetMs - MATCH_TOLERANCE_MS
        val expectedEnd = reference.last() - offsetMs + MATCH_TOLERANCE_MS
        val localTarget = target.filter { it in expectedStart..expectedEnd }
        if (localTarget.isEmpty()) return WindowScore(offsetMs, emptyList(), 0.0)

        val matches = monotonicMatches(reference, localTarget, offsetMs)
        val forwardRatio = matches.size.toDouble() / reference.size.coerceAtLeast(1)
        val reverseRatio = matches.size.toDouble() / localTarget.size.coerceAtLeast(1)
        val coverage = if (reference.size * 3 < localTarget.size) {
            // A Matroska subtitle index may contain only sparse timing landmarks.
            forwardRatio
        } else if (forwardRatio + reverseRatio == 0.0) {
            0.0
        } else {
            2.0 * forwardRatio * reverseRatio / (forwardRatio + reverseRatio)
        }
        val precision = if (matches.isEmpty()) 0.0 else 1.0 -
            (matches.sumOf(TimingMatch::errorMs).toDouble() / matches.size / MATCH_TOLERANCE_MS)
                .coerceIn(0.0, 1.0)
        return WindowScore(offsetMs, matches, coverage * 0.75 + precision * 0.25)
    }

    private fun monotonicMatches(
        reference: List<Long>,
        target: List<Long>,
        offsetMs: Long
    ): List<TimingMatch> {
        val counts = Array(reference.size + 1) { IntArray(target.size + 1) }
        val errors = Array(reference.size + 1) { LongArray(target.size + 1) }
        val decisions = Array(reference.size + 1) { ByteArray(target.size + 1) }

        fun isBetter(candidateCount: Int, candidateError: Long, count: Int, error: Long): Boolean =
            candidateCount > count || candidateCount == count && candidateError < error

        for (referenceIndex in 1..reference.size) {
            for (targetIndex in 1..target.size) {
                var bestCount = counts[referenceIndex - 1][targetIndex]
                var bestError = errors[referenceIndex - 1][targetIndex]
                var decision = SKIP_REFERENCE

                val skipTargetCount = counts[referenceIndex][targetIndex - 1]
                val skipTargetError = errors[referenceIndex][targetIndex - 1]
                if (isBetter(skipTargetCount, skipTargetError, bestCount, bestError)) {
                    bestCount = skipTargetCount
                    bestError = skipTargetError
                    decision = SKIP_TARGET
                }

                val referenceMs = reference[referenceIndex - 1]
                val targetMs = target[targetIndex - 1]
                val error = abs(targetMs - (referenceMs - offsetMs))
                if (error <= MATCH_TOLERANCE_MS) {
                    val pairCount = counts[referenceIndex - 1][targetIndex - 1] + 1
                    val pairError = errors[referenceIndex - 1][targetIndex - 1] + error
                    if (isBetter(pairCount, pairError, bestCount, bestError)) {
                        bestCount = pairCount
                        bestError = pairError
                        decision = MATCH
                    }
                }

                counts[referenceIndex][targetIndex] = bestCount
                errors[referenceIndex][targetIndex] = bestError
                decisions[referenceIndex][targetIndex] = decision
            }
        }

        val matches = mutableListOf<TimingMatch>()
        var referenceIndex = reference.size
        var targetIndex = target.size
        while (referenceIndex > 0 && targetIndex > 0) {
            when (decisions[referenceIndex][targetIndex]) {
                MATCH -> {
                    val referenceMs = reference[referenceIndex - 1]
                    val targetMs = target[targetIndex - 1]
                    matches += TimingMatch(referenceMs, targetMs, abs(targetMs - (referenceMs - offsetMs)))
                    referenceIndex--
                    targetIndex--
                }
                SKIP_TARGET -> targetIndex--
                else -> referenceIndex--
            }
        }
        matches.reverse()
        return matches
    }

    private fun refineOffset(score: WindowScore): Long {
        val differences = score.matches.map { it.referenceMs - it.targetMs }.sorted()
        return differences.getOrNull(differences.size / 2) ?: score.offsetMs
    }

    private fun strongPartialConstantScore(
        groups: List<Group>,
        reference: List<Long>,
        target: List<Long>,
        candidates: List<Long>
    ): Double? {
        if (groups.maxOf(Group::offsetMs) - groups.minOf(Group::offsetMs) > OFFSET_MERGE_TOLERANCE_MS) return null
        if (reference.last() - reference.first() < MIN_PARTIAL_CONSTANT_SPAN_MS) return null

        val offsetMs = groups.map(Group::offsetMs).sorted()[groups.size / 2]
        val scoringReference = evenlySample(reference, MAX_PARTIAL_SCORE_POINTS)
        val selected = scoreOffset(scoringReference, target, offsetMs)
        val matchRatio = selected.matches.size.toDouble() / scoringReference.size.coerceAtLeast(1)
        if (selected.matches.size < MIN_TOTAL_CUES ||
            matchRatio < MIN_PARTIAL_CONSTANT_MATCH_RATIO ||
            selected.score < MIN_PARTIAL_CONSTANT_SCORE
        ) return null

        val competingScore = candidates.asSequence()
            .filter { abs(it - offsetMs) > MATCH_TOLERANCE_MS }
            .map { scoreOffset(scoringReference, target, it).score }
            .maxOrNull() ?: 0.0
        if (selected.score - competingScore < MIN_PARTIAL_CONSTANT_MARGIN) return null
        return selected.score.coerceIn(0.0, 1.0)
    }

    private fun evenlySample(points: List<Long>, maximumSize: Int): List<Long> {
        if (points.size <= maximumSize) return points
        return List(maximumSize) { index ->
            points[index * points.lastIndex / (maximumSize - 1)]
        }
    }

    private fun mergeWindows(windows: List<WindowMatch>): List<Group> {
        val groups = mutableListOf<Group>()
        for (window in windows.sortedBy(WindowMatch::referenceStartMs)) {
            val previous = groups.lastOrNull()
            if (previous != null &&
                window.referenceStartMs <= previous.referenceEndMs &&
                abs(window.offsetMs - previous.offsetMs) <= OFFSET_MERGE_TOLERANCE_MS
            ) {
                previous.windows += window
            } else {
                groups += Group(mutableListOf(window))
            }
        }
        return groups
    }

    private fun buildSegments(groups: List<Group>, targetEndMs: Long): List<SubtitleSyncSegment> {
        val ordered = groups.sortedBy(Group::referenceStartMs)
        val starts = LongArray(ordered.size)
        val ends = LongArray(ordered.size)
        starts[0] = 0L
        ends[ends.lastIndex] = targetEndMs

        for (index in 0 until ordered.lastIndex) {
            val current = ordered[index]
            val next = ordered[index + 1]
            val transitionVideoMs = (current.referenceEndMs + next.referenceStartMs) / 2L
            val currentTargetEnd = transitionVideoMs - current.offsetMs
            val nextTargetStart = transitionVideoMs - next.offsetMs
            val minimumBoundary = (starts[index] + 1L).coerceAtMost(targetEndMs)
            val sharedBoundary = ((currentTargetEnd + nextTargetStart) / 2L)
                .coerceIn(minimumBoundary, targetEndMs)
            ends[index] = sharedBoundary
            starts[index + 1] = sharedBoundary
        }

        return ordered.indices.mapNotNull { index ->
            val start = starts[index].coerceAtLeast(0L)
            val end = ends[index].coerceAtMost(targetEndMs)
            if (end <= start) null else SubtitleSyncSegment(
                targetStartMs = start,
                targetEndMs = end,
                offsetMs = ordered[index].offsetMs,
                confidence = ordered[index].confidence
            )
        }
    }

    private data class TimingMatch(val referenceMs: Long, val targetMs: Long, val errorMs: Long)
    private data class WindowScore(val offsetMs: Long, val matches: List<TimingMatch>, val score: Double)
    private data class WindowMatch(
        val referenceStartMs: Long,
        val referenceEndMs: Long,
        val offsetMs: Long,
        val confidence: Double,
        val matchedCueCount: Int
    )

    private data class Group(val windows: MutableList<WindowMatch>) {
        val referenceStartMs: Long get() = windows.minOf(WindowMatch::referenceStartMs)
        val referenceEndMs: Long get() = windows.maxOf(WindowMatch::referenceEndMs)
        val offsetMs: Long get() = windows.map(WindowMatch::offsetMs).sorted()[windows.size / 2]
        val confidence: Double get() = windows.map(WindowMatch::confidence).average()
        val matchedCueCount: Int get() = windows.sumOf(WindowMatch::matchedCueCount)
    }

    private const val SKIP_REFERENCE: Byte = 1
    private const val SKIP_TARGET: Byte = 2
    private const val MATCH: Byte = 3
}
