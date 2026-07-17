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
    private const val WINDOW_POINT_COUNT = 32
    private const val WINDOW_STEP_POINTS = 16
    private const val MIN_SEGMENT_WINDOWS = 1
    private const val OFFSET_MERGE_TOLERANCE_MS = 1_500L
    private const val MIN_REFERENCE_COVERAGE = 0.55
    private const val MAX_UNSUPPORTED_GAP_MS = 3L * 60L * 1000L

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
        val firstSupportedTargetMs = acceptedGroups.minOf { it.referenceStartMs - it.offsetMs }
        val lastSupportedTargetMs = acceptedGroups.maxOf { it.referenceEndMs - it.offsetMs }
        if (firstSupportedTargetMs > targetPoints.first() + COVERAGE_PADDING_MS ||
            lastSupportedTargetMs < targetPoints.last() - COVERAGE_PADDING_MS
        ) return null
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
        if (coverageRatio < MIN_REFERENCE_COVERAGE) return null
        if (coverageIntervals.zipWithNext().any { (before, after) ->
                after.first - before.last > MAX_UNSUPPORTED_GAP_MS
            }
        ) return null

        val totalMatched = acceptedGroups.sumOf(Group::matchedCueCount)
        val confidence = acceptedGroups
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
                if (best.matches >= MIN_WINDOW_CUES && best.score >= 0.32) {
                    result += WindowMatch(
                        referenceStartMs = localReference.first(),
                        referenceEndMs = localReference.last() + 1L,
                        offsetMs = refineOffset(localReference, target, best.offsetMs),
                        confidence = confidence,
                        matchedCueCount = best.matches
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
        if (localTarget.isEmpty()) return WindowScore(offsetMs, 0, 0.0)

        val forward = match(reference, localTarget) { it - offsetMs }
        val reverse = match(localTarget, reference) { it + offsetMs }
        val coverage = if (reference.size * 3 < localTarget.size) {
            // A Matroska subtitle index may contain only sparse timing landmarks.
            forward.ratio
        } else if (forward.ratio + reverse.ratio == 0.0) {
            0.0
        } else {
            2.0 * forward.ratio * reverse.ratio / (forward.ratio + reverse.ratio)
        }
        val precision = (forward.precision + reverse.precision) / 2.0
        return WindowScore(offsetMs, forward.matches, coverage * 0.75 + precision * 0.25)
    }

    private fun match(source: List<Long>, destination: List<Long>, transform: (Long) -> Long): MatchStats {
        var matches = 0
        var totalError = 0L
        source.forEach { sourceMs ->
            val expectedMs = transform(sourceMs)
            val nearestIndex = nearestIndex(destination, expectedMs) ?: return@forEach
            val error = abs(destination[nearestIndex] - expectedMs)
            if (error <= MATCH_TOLERANCE_MS) {
                matches++
                totalError += error
            }
        }
        val ratio = matches.toDouble() / source.size.coerceAtLeast(1)
        val precision = if (matches == 0) 0.0 else 1.0 -
            (totalError.toDouble() / matches / MATCH_TOLERANCE_MS).coerceIn(0.0, 1.0)
        return MatchStats(matches, ratio, precision)
    }

    private fun refineOffset(reference: List<Long>, target: List<Long>, coarseOffsetMs: Long): Long {
        val differences = reference.mapNotNull { referenceMs ->
            val expectedTargetMs = referenceMs - coarseOffsetMs
            val nearestIndex = nearestIndex(target, expectedTargetMs)
                ?: return@mapNotNull null
            val nearest = target[nearestIndex]
            if (abs(nearest - expectedTargetMs) <= MATCH_TOLERANCE_MS) {
                referenceMs - nearest
            } else {
                null
            }
        }.sorted()
        return differences.getOrNull(differences.size / 2) ?: coarseOffsetMs
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

    private fun nearestIndex(sorted: List<Long>, value: Long): Int? {
        if (sorted.isEmpty()) return null
        val index = sorted.binarySearch(value)
        if (index >= 0) return index
        val insertion = if (index >= 0) index else -index - 1
        val beforeIndex = (insertion - 1).takeIf { it >= 0 }
        val afterIndex = insertion.takeIf { it < sorted.size }
        return when {
            beforeIndex == null -> afterIndex
            afterIndex == null -> beforeIndex
            value - sorted[beforeIndex] <= sorted[afterIndex] - value -> beforeIndex
            else -> afterIndex
        }
    }

    private data class MatchStats(val matches: Int, val ratio: Double, val precision: Double)
    private data class WindowScore(val offsetMs: Long, val matches: Int, val score: Double)
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
}
