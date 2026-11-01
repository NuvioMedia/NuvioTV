package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.segmentCategory

/**
 * Derive skip intervals from container chapters (MKV/Matroska).
 *
 * Chapter titles are matched case-insensitively. Only titles that are essentially
 * the segment label itself (with an optional number suffix) are matched, to avoid
 * false positives on scene-named chapters like "The Opening" or "Alternate Ending".
 *
 * Matched types: OP/Opening, ED/Ending/Credits, Recap/Previously, Intro.
 * "Intro" is promoted to opening only when no dedicated OP/Opening chapter exists.
 *
 * Titles containing "post-credits", "mid-credits", or "after-credits" are excluded
 * to avoid skipping post-credits scenes.
 */
internal object ChapterSkipIntervals {

    private const val PROVIDER = "chapters"

    private const val MIN_DURATION_SEC = 5.0
    private const val MAX_DURATION_SEC = 240.0 // 4 minutes max for OP/ED/recap

    // Strict: title must be essentially the label, optionally followed by a number.
    // e.g. "OP", "OP 2", "Opening", "Opening Credits", but NOT "The Opening Scene".
    private val OPENING_PATTERN = Regex("^\\s*(op(ening)?)(\\s*(credits|theme))?\\s*\\d*\\s*$", RegexOption.IGNORE_CASE)
    private val ENDING_PATTERN = Regex("^\\s*(ed|ending)(\\s*(credits|theme))?\\s*\\d*\\s*$", RegexOption.IGNORE_CASE)
    private val CREDITS_PATTERN = Regex("^\\s*(end|closing)?\\s*credits\\s*$", RegexOption.IGNORE_CASE)
    private val RECAP_PATTERN = Regex("^\\s*(recap|previously(\\s+on.*)?)\\s*$", RegexOption.IGNORE_CASE)
    private val INTRO_PATTERN = Regex("^\\s*intro\\s*$", RegexOption.IGNORE_CASE)

    private val POST_CREDITS_PATTERN = Regex("(post|mid|after)[\\s-]credits", RegexOption.IGNORE_CASE)

    fun fromChapters(
        chapters: List<PlayerChapter>,
        durationMs: Long
    ): List<SkipInterval> {
        if (chapters.size < 2 || durationMs <= 0L) return emptyList()

        val classified = chapters.mapIndexed { index, chapter ->
            val endMs = if (index + 1 < chapters.size) {
                chapters[index + 1].startMs
            } else {
                durationMs
            }
            ClassifiedChapter(
                chapter = chapter,
                endMs = endMs,
                type = classifyTitle(chapter.title)
            )
        }

        val hasExplicitOpening = classified.any { it.type == ChapterType.OPENING }
        val resolved = if (hasExplicitOpening) {
            classified.map { c ->
                if (c.type == ChapterType.INTRO) c.copy(type = null) else c
            }
        } else {
            classified.map { c ->
                if (c.type == ChapterType.INTRO) c.copy(type = ChapterType.OPENING) else c
            }
        }

        return resolved.mapNotNull { c ->
            val type = c.type ?: return@mapNotNull null
            // Post-credits scenes are classified but excluded from skip intervals.
            if (type == ChapterType.POST_CREDITS) return@mapNotNull null
            val skipType = type.skipIntervalType
            val startSec = c.chapter.startMs / 1000.0
            val endSec = c.endMs / 1000.0
            val duration = endSec - startSec
            if (duration < MIN_DURATION_SEC) return@mapNotNull null
            if (duration > MAX_DURATION_SEC) return@mapNotNull null
            SkipInterval(
                startTime = startSec,
                endTime = endSec,
                type = skipType,
                provider = PROVIDER
            )
        }
    }

    private fun classifyTitle(title: String?): ChapterType? {
        if (title.isNullOrBlank()) return null
        // Check post-credits first (before credits pattern would match)
        if (POST_CREDITS_PATTERN.containsMatchIn(title)) return ChapterType.POST_CREDITS
        if (OPENING_PATTERN.matches(title)) return ChapterType.OPENING
        if (ENDING_PATTERN.matches(title)) return ChapterType.ENDING
        if (CREDITS_PATTERN.matches(title)) return ChapterType.ENDING
        if (RECAP_PATTERN.matches(title)) return ChapterType.RECAP
        if (INTRO_PATTERN.matches(title)) return ChapterType.INTRO
        return null
    }

    private enum class ChapterType(val skipIntervalType: String) {
        OPENING("intro"),
        ENDING("outro"),
        RECAP("recap"),
        INTRO("intro"),
        POST_CREDITS("post-credits"),
    }

    private data class ClassifiedChapter(
        val chapter: PlayerChapter,
        val endMs: Long,
        val type: ChapterType?
    )
}

internal fun mergeSkipIntervals(
    apiIntervals: List<SkipInterval>,
    chapterIntervals: List<SkipInterval>,
    isMovie: Boolean = false
): List<SkipInterval> {
    if (chapterIntervals.isEmpty()) return apiIntervals
    if (apiIntervals.isEmpty()) return chapterIntervals
    // For movies: prefer API entirely when it has data (handles post-credits trimming)
    if (isMovie && apiIntervals.isNotEmpty()) return apiIntervals
    val chosen = LinkedHashMap<String, SkipInterval>()
    for (interval in chapterIntervals) {
        val category = segmentCategory(interval.type) ?: continue
        chosen.putIfAbsent(category, interval)
    }
    for (interval in apiIntervals) {
        val category = segmentCategory(interval.type) ?: continue
        chosen.putIfAbsent(category, interval)
    }
    val uncategorized = (chapterIntervals + apiIntervals)
        .filter { segmentCategory(it.type) == null }
    return chosen.values.toList() + uncategorized
}
