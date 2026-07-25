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
    /**
     * Slack, in target points, added either side of the bracket [refineBoundary] searches.
     *
     * The bracket is derived from the two groups' matching-window edges, and a window is
     * [WINDOW_POINT_COUNT] points long, so the group that swallowed the straddling window can
     * report an edge up to a window past the real changeover. One window of slack each way covers
     * that without letting the search wander into unrelated dialogue.
     */
    private const val BOUNDARY_SEARCH_SLACK_POINTS = WINDOW_POINT_COUNT

    /**
     * Score difference below which two candidate boundaries are treated as indistinguishable.
     *
     * A single target point contributes at most 1.0 to the boundary score, so a lead of a point or
     * two is noise: a translated track leaves ~15% of its cues with no reference counterpart at
     * all, and any of those can score zero on the correct side while coincidentally landing on the
     * wrong one. Two points' worth of slack turns the argmax into a plateau that [silenceAt] can
     * then resolve on physical grounds.
     */
    private const val BOUNDARY_SCORE_MARGIN = 2.0

    /**
     * Granularity at which two candidate boundaries count as sitting in equally long pauses.
     *
     * Within the plateau the tie is settled by preferring the widest silence, because an edit is a
     * splice and a splice lands between lines rather than inside one. Quantizing at four times
     * [MATCH_TOLERANCE_MS] keeps that from over-reading ordinary dialogue rhythm -- the two to four
     * second gaps between consecutive lines all fall in the same bucket, so only a genuine pause,
     * of the kind a removed ad break leaves behind, can win on this criterion.
     */
    private const val BOUNDARY_SILENCE_UNIT_MS = 4L * MATCH_TOLERANCE_MS
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
        val segments = buildSegments(acceptedGroups, targetEndMs, referencePoints, targetPoints, scratch)
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
        private var boundaryScores = DoubleArray(0)
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

        fun ensureBoundaryScores(size: Int): DoubleArray {
            if (boundaryScores.size < size) boundaryScores = DoubleArray(size)
            return boundaryScores
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

    private fun buildSegments(
        groups: List<Group>,
        targetEndMs: Long,
        reference: List<Long>,
        target: List<Long>,
        scratch: Scratch
    ): List<SubtitleSyncSegment> {
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
            val estimateMs = ((currentTargetEnd + nextTargetStart) / 2L)
                .coerceIn(minimumBoundary, targetEndMs)
            val sharedBoundary = refineBoundary(
                reference = reference,
                target = target,
                current = current,
                next = next,
                estimateMs = estimateMs,
                minimumMs = minimumBoundary,
                maximumMs = targetEndMs,
                scratch = scratch
            )
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

    /**
     * Locates the changeover between two adjacent timing regions by a bounded search over target
     * time, replacing the midpoint of the two groups' matching-window edges.
     *
     * The midpoint is a poor estimator because a window is [WINDOW_POINT_COUNT] cues long -- around
     * 167 seconds on a feature film -- so the group edges it is built from are only accurate to
     * roughly that, and every cue between the guess and the real edit is shifted by the entire
     * length of the edit.
     *
     * The search is a one dimensional argmax. Let `o1` and `o2` be the two offsets and let the
     * candidate split be a target index `k`, meaning "target points before `k` take `o1`, the rest
     * take `o2`". Scoring a point under an offset asks how close `point + offset` lands to a real
     * reference point, graded linearly to zero at [MATCH_TOLERANCE_MS]:
     *
     *     score(k) = sum(quality(t_i, o1) for i < k) + sum(quality(t_i, o2) for i >= k)
     *
     * Grading rather than counting matters here. Offsets differing by an edit length are separated
     * by minutes of dialogue, so at ~1 cue every 3 seconds and a 1.5 second tolerance the *wrong*
     * offset still lands within tolerance of some unrelated reference cue about half the time. A
     * plain match count would be swamped by that noise; a graded score is not, because a true match
     * sits tens of milliseconds out and scores ~1.0 while a coincidental one is uniformly spread
     * over the tolerance and averages ~0.25 after its 50% hit rate.
     *
     * Rewriting `score(k)` as `constant + sum(quality(t_i, o1) - quality(t_i, o2) for i < k)` turns
     * it into a prefix sum of a running difference, so the whole search is one forward pass over a
     * region bounded by the two group edges plus [BOUNDARY_SEARCH_SLACK_POINTS] either side, at
     * O(region * log(reference)) and no allocation beyond the reused [Scratch] buffer.
     *
     * The argmax is taken over a [BOUNDARY_SCORE_MARGIN] plateau rather than pointwise, and the
     * plateau is resolved towards the widest pause and then towards [estimateMs]. See those
     * constants: a bare argmax is decided by one or two unmatched cues and will happily place the
     * changeover mid-scene when a 90 second silence a few lines later fits just as well.
     */
    private fun refineBoundary(
        reference: List<Long>,
        target: List<Long>,
        current: Group,
        next: Group,
        estimateMs: Long,
        minimumMs: Long,
        maximumMs: Long,
        scratch: Scratch
    ): Long {
        val currentOffsetMs = current.offsetMs
        val nextOffsetMs = next.offsetMs
        // Identical offsets carry no signal, and the groups would have been merged anyway.
        if (currentOffsetMs == nextOffsetMs || reference.isEmpty() || target.isEmpty()) return estimateMs

        // The changeover lies between where the first group stops being supported and where the
        // second starts, both expressed in target time.
        val currentEdgeMs = current.referenceEndMs - currentOffsetMs
        val nextEdgeMs = next.referenceStartMs - nextOffsetMs
        val fromMs = minOf(currentEdgeMs, nextEdgeMs, estimateMs)
        val toMs = maxOf(currentEdgeMs, nextEdgeMs, estimateMs)

        val lowIndex = maxOf(
            (target.lowerBound(fromMs) - BOUNDARY_SEARCH_SLACK_POINTS).coerceAtLeast(0),
            target.lowerBound(minimumMs)
        )
        val highIndex = (target.lowerBound(toMs) + BOUNDARY_SEARCH_SLACK_POINTS)
            .coerceAtMost(target.size)
        if (highIndex - lowIndex < 2) return estimateMs

        // scores[n] is score(lowIndex + n) with the constant term dropped, which does not move the
        // argmax and keeps this to one running sum.
        val splitCount = highIndex - lowIndex + 1
        val scores = scratch.ensureBoundaryScores(splitCount)
        var running = 0.0
        scores[0] = 0.0
        for (index in lowIndex until highIndex) {
            val targetMs = target[index]
            running += reference.matchQuality(targetMs + currentOffsetMs) -
                reference.matchQuality(targetMs + nextOffsetMs)
            scores[index - lowIndex + 1] = running
        }
        var bestScore = 0.0
        for (n in 1 until splitCount) if (scores[n] > bestScore) bestScore = scores[n]

        val cutoff = bestScore - BOUNDARY_SCORE_MARGIN
        var chosenIndex = lowIndex
        var chosenSilence = -1L
        var chosenScore = Double.NEGATIVE_INFINITY
        var chosenDistanceMs = Long.MAX_VALUE
        var seen = false
        for (n in 0 until splitCount) {
            if (scores[n] < cutoff) continue
            val split = lowIndex + n
            val silence = silenceAt(target, split, lowIndex, highIndex) / BOUNDARY_SILENCE_UNIT_MS
            val distanceMs = abs(boundaryAt(target, split, lowIndex, highIndex) - estimateMs)
            val preferred = !seen ||
                silence > chosenSilence ||
                silence == chosenSilence && scores[n] > chosenScore ||
                silence == chosenSilence && scores[n] == chosenScore && distanceMs < chosenDistanceMs
            if (preferred) {
                seen = true
                chosenIndex = split
                chosenSilence = silence
                chosenScore = scores[n]
                chosenDistanceMs = distanceMs
            }
        }

        return boundaryAt(target, chosenIndex, lowIndex, highIndex).coerceIn(minimumMs, maximumMs)
    }

    /**
     * Target time for a split before index [splitIndex], placed in the silence between the last cue
     * of the first region and the first cue of the second so that neither can drift across it.
     */
    private fun boundaryAt(target: List<Long>, splitIndex: Int, lowIndex: Int, highIndex: Int): Long =
        when {
            splitIndex <= lowIndex -> target[lowIndex]
            splitIndex >= highIndex -> target[highIndex - 1] + 1L
            else -> target[splitIndex - 1] + (target[splitIndex] - target[splitIndex - 1] + 1L) / 2L
        }

    /** Width of the pause a split at [splitIndex] would sit in; zero at the ends of the region. */
    private fun silenceAt(target: List<Long>, splitIndex: Int, lowIndex: Int, highIndex: Int): Long =
        if (splitIndex <= lowIndex || splitIndex >= highIndex) 0L
        else target[splitIndex] - target[splitIndex - 1]

    /** 1.0 for an exact hit, falling linearly to 0.0 at [MATCH_TOLERANCE_MS] and beyond. */
    private fun List<Long>.matchQuality(valueMs: Long): Double {
        val index = lowerBound(valueMs)
        val after = if (index < size) this[index] - valueMs else Long.MAX_VALUE
        val before = if (index > 0) valueMs - this[index - 1] else Long.MAX_VALUE
        val distanceMs = minOf(before, after)
        if (distanceMs >= MATCH_TOLERANCE_MS) return 0.0
        return 1.0 - distanceMs.toDouble() / MATCH_TOLERANCE_MS
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
