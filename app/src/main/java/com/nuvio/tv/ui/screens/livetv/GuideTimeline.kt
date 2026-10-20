package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.domain.model.LiveTvProgram

/** Builds a clipped, non-overlapping view of guide entries inside one fixed timeline window. */
internal fun guideSegments(
    programs: List<LiveTvProgram>,
    sourceId: String,
    tvgId: String,
    windowStartMillis: Long,
    windowEndMillis: Long,
): List<GuideSegment> {
    if (windowEndMillis <= windowStartMillis) return emptyList()
    var cursor = windowStartMillis
    return programs.asSequence()
        .filter { it.sourceId == sourceId && it.channelId == tvgId }
        .sortedBy(LiveTvProgram::startEpochMillis)
        .mapNotNull { program ->
            val clippedStart = maxOf(program.startEpochMillis, cursor, windowStartMillis)
            val clippedEnd = minOf(program.endEpochMillis, windowEndMillis)
            if (clippedEnd <= clippedStart) return@mapNotNull null
            val gapMinutes = ((clippedStart - cursor) / MILLIS_PER_MINUTE).toInt().coerceAtLeast(0)
            val durationMinutes = ((clippedEnd - clippedStart) / MILLIS_PER_MINUTE).toInt().coerceAtLeast(1)
            cursor = clippedEnd
            GuideSegment(program, clippedStart, clippedEnd, gapMinutes, durationMinutes)
        }
        .toList()
}

internal data class GuideSegment(
    val program: LiveTvProgram,
    val startMillis: Long,
    val endMillis: Long,
    val gapBeforeMinutes: Int,
    val durationMinutes: Int,
)

private const val MILLIS_PER_MINUTE = 60_000L
