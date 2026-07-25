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
            val segment = segmentFor(cue.startMs) ?: return@mapNotNull cue
            val startMs = cue.startMs + segment.offsetMs
            val endMs = cue.endMs + segment.offsetMs
            if (endMs <= 0L || endMs <= startMs) return@mapNotNull null
            SrtCue(startMs.coerceAtLeast(0L), endMs.coerceAtLeast(0L), cue.text)
        }
        return SrtDocument(rewritten.sortedBy(SrtCue::startMs))
    }

    /**
     * Segments span the whole target, but a cue can still fall outside them all -- a cue timed
     * before zero after an inserted-content shift, for instance. Such a cue is clamped to the
     * nearest segment rather than left at its unsynchronized position, where it would be stranded
     * among corrected neighbours by the full desync amount.
     */
    private fun segmentFor(startMs: Long): SubtitleSyncSegment? {
        if (segments.isEmpty()) return null
        segments.forEach { segment ->
            if (startMs >= segment.targetStartMs && startMs < segment.targetEndMs) return segment
        }
        return segments.minByOrNull { segment ->
            if (startMs < segment.targetStartMs) segment.targetStartMs - startMs
            else startMs - segment.targetEndMs
        }
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
    private const val MINORITY_WINDOW_RATIO = 8
    private const val OFFSET_MERGE_TOLERANCE_MS = 1_500L
    private const val MIN_REFERENCE_COVERAGE = 0.55
    private const val MAX_UNSUPPORTED_GAP_MS = 3L * 60L * 1000L
    private const val MIN_PARTIAL_CONSTANT_SCORE = 0.75
    private const val MIN_PARTIAL_CONSTANT_MARGIN = 0.06
    private const val MIN_PARTIAL_CONSTANT_MATCH_RATIO = 0.75
    private const val MIN_PARTIAL_CONSTANT_SPAN_MS = 30L * 1000L
    /**
     * Upper bound on the reference points used to score a constant-offset hypothesis.
     *
     * This must stay comparable to the number of target points in the same span. [scoreOffset]
     * blends coverage as an F1 of the forward and reverse match ratios, so scoring a heavily
     * thinned reference against a dense target depresses `reverseRatio` and the hypothesis is
     * rejected however well it actually fits. At 128 that produced a dead zone in which a partial
     * playback capture could never be accepted: too dense for the sparse-index bypass
     * (`reference.size * 3 < localTarget.size`), too thin for a balanced F1.
     */
    private const val MAX_PARTIAL_SCORE_POINTS = 1024

    fun align(reference: List<SrtCue>, target: List<SrtCue>): SubtitleSyncModel? {
        val referencePoints = timingPoints(reference)
        val targetPoints = timingPoints(target)
        if (referencePoints.size < MIN_TOTAL_CUES || targetPoints.size < MIN_TOTAL_CUES) return null

        val candidates = globalOffsetCandidates(referencePoints, targetPoints)
        if (candidates.isEmpty()) return null

        val scratch = Scratch()
        val windows = localWindows(referencePoints, targetPoints, candidates, scratch)
        if (windows.isEmpty()) return null
        val groups = dropUnsupportedGroups(mergeWindows(windows))
        if (groups.isEmpty()) return null

        val acceptedGroups = groups.filter { it.confidence >= 0.36 && it.matchedCueCount >= MIN_WINDOW_CUES }
        if (acceptedGroups.isEmpty()) return null
        val partialConstantScore = strongPartialConstantScore(
            acceptedGroups,
            referencePoints,
            targetPoints,
            candidates,
            scratch
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

    /**
     * Votes every reference/target pair into a coarse offset histogram and returns the strongest,
     * well separated peaks.
     *
     * Backed by a flat [IntArray] indexed by bin rather than a `HashMap<Long, Int>`: the pair loop
     * runs into the tens of millions on a feature length film, and boxing a key and a value per
     * iteration dominated the cost. Both lists are sorted, so the inner loop is also clipped to the
     * reachable offset range instead of scanning the whole target.
     *
     * Ties are broken towards the smaller absolute offset. The previous implementation resolved
     * them in hash-table order, which was arbitrary.
     */
    private fun globalOffsetCandidates(reference: List<Long>, target: List<Long>): List<Long> {
        val centreBin = (MAX_ABS_OFFSET_MS / BIN_MS).toInt()
        val histogram = IntArray(centreBin * 2 + 1)

        for (referenceMs in reference) {
            var index = target.lowerBound(referenceMs - MAX_ABS_OFFSET_MS)
            while (index < target.size) {
                val difference = referenceMs - target[index]
                if (difference < -MAX_ABS_OFFSET_MS) break
                val bin = (difference.toDouble() / BIN_MS).roundToLong().toInt() + centreBin
                if (bin >= 0 && bin < histogram.size) histogram[bin]++
                index++
            }
        }

        val populated = histogram.indices.filter { histogram[it] > 0 }
        if (populated.isEmpty()) return emptyList()

        return populated
            .sortedWith(compareByDescending<Int> { histogram[it] }.thenBy { abs(it - centreBin) })
            .map { (it - centreBin).toLong() * BIN_MS }
            .fold(mutableListOf<Long>()) { selected, value ->
                if (selected.none { abs(it - value) < OFFSET_MERGE_TOLERANCE_MS }) selected += value
                selected
            }
            .take(32)
    }

    private fun localWindows(
        reference: List<Long>,
        target: List<Long>,
        candidates: List<Long>,
        scratch: Scratch
    ): List<WindowMatch> {
        val result = mutableListOf<WindowMatch>()
        var startIndex = 0
        while (startIndex < reference.size) {
            val endIndex = minOf(startIndex + WINDOW_POINT_COUNT, reference.size)
            val localReference = reference.subList(startIndex, endIndex)
            if (localReference.size >= MIN_WINDOW_CUES) {
                val scored = candidates.map { scoreOffset(localReference, target, it, scratch) }
                    .sortedByDescending(WindowScore::score)
                val best = scored.first()
                val secondScore = scored.firstOrNull {
                    abs(it.offsetMs - best.offsetMs) > MATCH_TOLERANCE_MS
                }?.score ?: 0.0
                val margin = (best.score - secondScore).coerceAtLeast(0.0)
                val confidence = (best.score * 0.8 + margin * 0.6).coerceIn(0.0, 1.0)
                if (best.matchCount >= MIN_WINDOW_CUES &&
                    best.matchCount.toDouble() / localReference.size >= MIN_WINDOW_REFERENCE_MATCH_RATIO &&
                    best.score >= 0.32
                ) {
                    // Only the winning candidate needs the alignment itself; every other candidate
                    // was scored without a traceback.
                    val matches = traceMatches(localReference, best.localTarget, best.offsetMs, scratch)
                    result += WindowMatch(
                        referenceStartMs = localReference.first(),
                        referenceEndMs = localReference.last() + 1L,
                        offsetMs = refineOffset(matches, best.offsetMs),
                        confidence = confidence,
                        matchedCueCount = best.matchCount
                    )
                }
            }
            if (endIndex == reference.size) break
            startIndex += WINDOW_STEP_POINTS
        }
        return result
    }

    private fun scoreOffset(
        reference: List<Long>,
        target: List<Long>,
        offsetMs: Long,
        scratch: Scratch
    ): WindowScore {
        val expectedStart = reference.first() - offsetMs - MATCH_TOLERANCE_MS
        val expectedEnd = reference.last() - offsetMs + MATCH_TOLERANCE_MS
        val localTarget = target.rangeView(expectedStart, expectedEnd)
        if (localTarget.isEmpty()) return WindowScore(offsetMs, emptyList(), 0, 0.0, 0.0)

        val matchCount = matchScore(reference, localTarget, offsetMs, scratch)
        val totalErrorMs = scratch.lastTotalErrorMs
        val forwardRatio = matchCount.toDouble() / reference.size.coerceAtLeast(1)
        val reverseRatio = matchCount.toDouble() / localTarget.size.coerceAtLeast(1)
        val coverage = if (reference.size * 3 < localTarget.size) {
            // A Matroska subtitle index may contain only sparse timing landmarks.
            forwardRatio
        } else if (forwardRatio + reverseRatio == 0.0) {
            0.0
        } else {
            2.0 * forwardRatio * reverseRatio / (forwardRatio + reverseRatio)
        }
        val precision = if (matchCount == 0) 0.0 else 1.0 -
            (totalErrorMs.toDouble() / matchCount / MATCH_TOLERANCE_MS).coerceIn(0.0, 1.0)
        return WindowScore(
            offsetMs = offsetMs,
            localTarget = localTarget,
            matchCount = matchCount,
            score = coverage * 0.75 + precision * 0.25,
            totalErrorMs = totalErrorMs.toDouble()
        )
    }

    /**
     * Optimal monotone one-to-one matching between two sorted timing lists, scored only.
     *
     * Identical recurrence to [traceMatches] but keeps just two rolling rows instead of the full
     * (reference x target) tables, because the vast majority of calls only ever need the match
     * count and the accumulated error. Writes the error sum to [Scratch.lastTotalErrorMs].
     */
    private fun matchScore(
        reference: List<Long>,
        target: List<Long>,
        offsetMs: Long,
        scratch: Scratch
    ): Int {
        val width = target.size + 1
        scratch.ensureRows(width)
        var previousCounts = scratch.countsA
        var previousErrors = scratch.errorsA
        var currentCounts = scratch.countsB
        var currentErrors = scratch.errorsB
        java.util.Arrays.fill(previousCounts, 0, width, 0)
        java.util.Arrays.fill(previousErrors, 0, width, 0L)

        for (referenceIndex in 1..reference.size) {
            currentCounts[0] = 0
            currentErrors[0] = 0L
            val referenceMs = reference[referenceIndex - 1]
            for (targetIndex in 1..target.size) {
                var bestCount = previousCounts[targetIndex]
                var bestError = previousErrors[targetIndex]

                val skipTargetCount = currentCounts[targetIndex - 1]
                val skipTargetError = currentErrors[targetIndex - 1]
                if (isBetter(skipTargetCount, skipTargetError, bestCount, bestError)) {
                    bestCount = skipTargetCount
                    bestError = skipTargetError
                }

                val error = abs(target[targetIndex - 1] - (referenceMs - offsetMs))
                if (error <= MATCH_TOLERANCE_MS) {
                    val pairCount = previousCounts[targetIndex - 1] + 1
                    val pairError = previousErrors[targetIndex - 1] + error
                    if (isBetter(pairCount, pairError, bestCount, bestError)) {
                        bestCount = pairCount
                        bestError = pairError
                    }
                }

                currentCounts[targetIndex] = bestCount
                currentErrors[targetIndex] = bestError
            }
            val swapCounts = previousCounts; previousCounts = currentCounts; currentCounts = swapCounts
            val swapErrors = previousErrors; previousErrors = currentErrors; currentErrors = swapErrors
        }

        scratch.lastTotalErrorMs = previousErrors[target.size]
        return previousCounts[target.size]
    }

    /** Same recurrence as [matchScore], but keeps the decision table so the pairs can be recovered. */
    private fun traceMatches(
        reference: List<Long>,
        target: List<Long>,
        offsetMs: Long,
        scratch: Scratch
    ): List<TimingMatch> {
        if (reference.isEmpty() || target.isEmpty()) return emptyList()
        val width = target.size + 1
        scratch.ensureRows(width)
        val decisions = scratch.ensureDecisions((reference.size + 1) * width)
        var previousCounts = scratch.countsA
        var previousErrors = scratch.errorsA
        var currentCounts = scratch.countsB
        var currentErrors = scratch.errorsB
        java.util.Arrays.fill(previousCounts, 0, width, 0)
        java.util.Arrays.fill(previousErrors, 0, width, 0L)

        for (referenceIndex in 1..reference.size) {
            currentCounts[0] = 0
            currentErrors[0] = 0L
            val referenceMs = reference[referenceIndex - 1]
            val rowOffset = referenceIndex * width
            for (targetIndex in 1..target.size) {
                var bestCount = previousCounts[targetIndex]
                var bestError = previousErrors[targetIndex]
                var decision = SKIP_REFERENCE

                val skipTargetCount = currentCounts[targetIndex - 1]
                val skipTargetError = currentErrors[targetIndex - 1]
                if (isBetter(skipTargetCount, skipTargetError, bestCount, bestError)) {
                    bestCount = skipTargetCount
                    bestError = skipTargetError
                    decision = SKIP_TARGET
                }

                val error = abs(target[targetIndex - 1] - (referenceMs - offsetMs))
                if (error <= MATCH_TOLERANCE_MS) {
                    val pairCount = previousCounts[targetIndex - 1] + 1
                    val pairError = previousErrors[targetIndex - 1] + error
                    if (isBetter(pairCount, pairError, bestCount, bestError)) {
                        bestCount = pairCount
                        bestError = pairError
                        decision = MATCH
                    }
                }

                currentCounts[targetIndex] = bestCount
                currentErrors[targetIndex] = bestError
                decisions[rowOffset + targetIndex] = decision
            }
            val swapCounts = previousCounts; previousCounts = currentCounts; currentCounts = swapCounts
            val swapErrors = previousErrors; previousErrors = currentErrors; currentErrors = swapErrors
        }

        val matches = mutableListOf<TimingMatch>()
        var referenceIndex = reference.size
        var targetIndex = target.size
        while (referenceIndex > 0 && targetIndex > 0) {
            when (decisions[referenceIndex * width + targetIndex]) {
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

    private fun isBetter(candidateCount: Int, candidateError: Long, count: Int, error: Long): Boolean =
        candidateCount > count || candidateCount == count && candidateError < error

    /**
     * Sorted sublist view of [this] within the inclusive range. A view rather than a copy, so the
     * ~40k scoring calls in a full alignment stop allocating a list each.
     */
    private fun List<Long>.rangeView(fromMs: Long, toMs: Long): List<Long> {
        if (isEmpty() || fromMs > toMs) return emptyList()
        val from = lowerBound(fromMs)
        val to = lowerBound(toMs + 1L)
        return if (from >= to) emptyList() else subList(from, to)
    }

    /** Index of the first element >= [valueMs]. */
    private fun List<Long>.lowerBound(valueMs: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle] < valueMs) low = middle + 1 else high = middle
        }
        return low
    }

    /**
     * Reusable dynamic programming buffers. One instance per [align] call, so it is confined to the
     * calling thread and needs no synchronization.
     */
    private class Scratch {
        var countsA = IntArray(0); private set
        var countsB = IntArray(0); private set
        var errorsA = LongArray(0); private set
        var errorsB = LongArray(0); private set
        private var decisions = ByteArray(0)
        var lastTotalErrorMs = 0L

        fun ensureRows(width: Int) {
            if (countsA.size >= width) return
            countsA = IntArray(width); countsB = IntArray(width)
            errorsA = LongArray(width); errorsB = LongArray(width)
        }

        fun ensureDecisions(size: Int): ByteArray {
            if (decisions.size < size) decisions = ByteArray(size)
            return decisions
        }
    }

    private fun refineOffset(matches: List<TimingMatch>, fallbackOffsetMs: Long): Long {
        val differences = matches.map { it.referenceMs - it.targetMs }.sorted()
        return differences.getOrNull(differences.size / 2) ?: fallbackOffsetMs
    }


    private fun strongPartialConstantScore(
        groups: List<Group>,
        reference: List<Long>,
        target: List<Long>,
        candidates: List<Long>,
        scratch: Scratch
    ): Double? {
        if (groups.maxOf(Group::offsetMs) - groups.minOf(Group::offsetMs) > OFFSET_MERGE_TOLERANCE_MS) return null
        if (reference.last() - reference.first() < MIN_PARTIAL_CONSTANT_SPAN_MS) return null

        val offsetMs = groups.map(Group::offsetMs).sorted()[groups.size / 2]
        val scoringReference = evenlySample(reference, MAX_PARTIAL_SCORE_POINTS)
        val selected = scoreOffset(scoringReference, target, offsetMs, scratch)
        val matchRatio = selected.matchCount.toDouble() / scoringReference.size.coerceAtLeast(1)
        if (selected.matchCount < MIN_TOTAL_CUES ||
            matchRatio < MIN_PARTIAL_CONSTANT_MATCH_RATIO ||
            selected.score < MIN_PARTIAL_CONSTANT_SCORE
        ) return null

        val competingCutoff = selected.score - MIN_PARTIAL_CONSTANT_MARGIN
        val hasCompetingOffset = candidates.any { candidate ->
            abs(candidate - offsetMs) > MATCH_TOLERANCE_MS &&
                scoreOffset(scoringReference, target, candidate, scratch).score > competingCutoff
        }
        if (hasCompetingOffset) return null
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

    /**
     * Rejects groups that are contradicted by far better supported evidence.
     *
     * Windows overlap by half their length, so a genuine timing region normally produces several.
     * A group that disagrees with every neighbour, is weaker than all of them, and accounts for a
     * tiny share of the matched windows is not evidence of an edit -- it is one window that locked
     * onto the wrong offset candidate. Accepting it shifts a short stretch of dialogue out of an
     * otherwise correct track.
     *
     * Such a spike is physically impossible anyway: inserting or cutting video shifts everything
     * that follows and it stays shifted. An offset that jumps away and immediately returns
     * describes no real edit.
     *
     * All three conditions are required together so that legitimate cases survive: a genuine ad
     * break splits a short reference into two equally weighted groups, and several independent
     * regions are all comparably supported.
     */
    private fun dropUnsupportedGroups(groups: List<Group>): List<Group> {
        if (groups.size <= 1) return groups
        val ordered = groups.sortedBy(Group::referenceStartMs)
        val totalWindows = ordered.sumOf { it.windows.size }

        val kept = ordered.filterIndexed { index, group ->
            val neighbours = listOfNotNull(ordered.getOrNull(index - 1), ordered.getOrNull(index + 1))
            val contradictsEveryNeighbour = neighbours.none {
                abs(it.offsetMs - group.offsetMs) <= OFFSET_MERGE_TOLERANCE_MS
            }
            val weakerThanEveryNeighbour = neighbours.all { group.windows.size < it.windows.size }
            val isSmallMinority = group.windows.size * MINORITY_WINDOW_RATIO <= totalWindows
            !(contradictsEveryNeighbour && weakerThanEveryNeighbour && isSmallMinority)
        }

        if (kept.size == ordered.size) return ordered
        if (kept.isEmpty()) return emptyList()
        // Dropping a spike can leave neighbours that now agree, so re-merge what survived.
        return mergeWindows(kept.flatMap(Group::windows))
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

    private class WindowScore(
        val offsetMs: Long,
        /** Sublist view of the target points this offset was scored against. */
        val localTarget: List<Long>,
        val matchCount: Int,
        val score: Double,
        val totalErrorMs: Double
    )
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
