package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSkipIntervalsTest {

    private val durationMs = 24 * 60_000L // 24 minutes

    private fun chapters(vararg entries: Pair<Long, String?>): List<PlayerChapter> =
        PlayerChapters.normalize(entries.map { (ms, title) -> PlayerChapter(ms, title) })

    @Test
    fun `OP chapter produces intro interval`() {
        val chapters = chapters(
            0L to "Prologue",
            90_000L to "OP",
            180_000L to "Episode",
            1_320_000L to "ED"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        val opening = intervals.find { it.type == "intro" }
        assertEquals(90.0, opening!!.startTime, 0.01)
        assertEquals(180.0, opening.endTime, 0.01)
        assertEquals("chapters", opening.provider)
    }

    @Test
    fun `Opening chapter produces intro interval`() {
        val chapters = chapters(
            0L to "Cold Open",
            120_000L to "Opening",
            210_000L to "Part A"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        val opening = intervals.find { it.type == "intro" }
        assertEquals(120.0, opening!!.startTime, 0.01)
    }

    @Test
    fun `ED chapter produces outro interval`() {
        val chapters = chapters(
            0L to "Episode",
            1_300_000L to "ED",
            1_390_000L to "Preview"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        val ending = intervals.find { it.type == "outro" }
        assertEquals(1_300.0, ending!!.startTime, 0.01)
    }

    @Test
    fun `Recap chapter produces recap interval`() {
        val chapters = chapters(
            0L to "Previously on...",
            60_000L to "OP",
            150_000L to "Episode"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        val recap = intervals.find { it.type == "recap" }
        assertEquals(0.0, recap!!.startTime, 0.01)
    }

    @Test
    fun `Intro alone treated as opening when no OP exists`() {
        val chapters = chapters(
            0L to "Intro",
            90_000L to "Episode",
            1_320_000L to "Credits"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        val opening = intervals.find { it.type == "intro" }
        assertEquals(0.0, opening!!.startTime, 0.01)
    }

    @Test
    fun `Intro demoted when OP also exists`() {
        val chapters = chapters(
            0L to "Intro",
            60_000L to "OP",
            150_000L to "Episode",
            1_320_000L to "ED"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        val intros = intervals.filter { it.type == "intro" }
        assertEquals(1, intros.size)
        assertEquals(60.0, intros[0].startTime, 0.01)
    }

    @Test
    fun `chapters without known titles produce no intervals`() {
        val chapters = chapters(
            0L to "Chapter 1",
            300_000L to "Chapter 2",
            600_000L to "Chapter 3"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        assertTrue(intervals.isEmpty())
    }

    @Test
    fun `fewer than two chapters produce no intervals`() {
        val single = listOf(PlayerChapter(0L, "OP"))
        val intervals = ChapterSkipIntervals.fromChapters(single, durationMs)
        assertTrue(intervals.isEmpty())
    }

    @Test
    fun `case insensitive matching`() {
        val chapters = chapters(
            0L to "RECAP",
            60_000L to "opening",
            150_000L to "episode",
            1_300_000L to "Ending Credits"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        assertEquals(3, intervals.size)
    }

    @Test
    fun `very short chapters are filtered out`() {
        val chapters = chapters(
            0L to "Episode",
            1_300_000L to "OP",
            1_302_000L to "Part B"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        assertTrue(intervals.none { it.type == "intro" })
    }

    @Test
    fun `very long chapters are filtered out`() {
        // Chapter longer than 4 minutes (MAX_DURATION_SEC = 240)
        val chapters = chapters(
            0L to "OP",
            300_000L to "Episode"  // 5 minutes - too long for an opening
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        assertTrue(intervals.none { it.type == "intro" })
    }

    @Test
    fun `Post-Credits Scene chapter is excluded`() {
        val chapters = chapters(
            0L to "Movie",
            5_520_000L to "Credits",
            5_700_000L to "Post-Credits Scene"
        )
        val movieDuration = 6_000_000L
        val intervals = ChapterSkipIntervals.fromChapters(chapters, movieDuration)
        assertTrue(intervals.none { it.startTime == 5_700.0 })
        // "Credits" should still be matched (180s < 240s max)
        assertTrue(intervals.any { it.type == "outro" && it.startTime == 5_520.0 })
    }

    @Test
    fun `Mid-Credits chapter is excluded`() {
        val chapters = chapters(
            0L to "Movie",
            5_520_000L to "Credits",
            5_600_000L to "Mid-Credits Scene",
            5_700_000L to "More Credits"
        )
        val movieDuration = 6_000_000L
        val intervals = ChapterSkipIntervals.fromChapters(chapters, movieDuration)
        assertTrue(intervals.none { it.startTime == 5_600.0 })
    }

    @Test
    fun `scene-named chapters do not false-match`() {
        // Blu-ray rips with scene names like "The Opening" or "Alternate Ending"
        val chapters = chapters(
            0L to "The Opening",
            600_000L to "The Journey",
            1_200_000L to "The Ending",
            1_500_000L to "Alternate Ending"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, 1_800_000L)
        assertTrue(intervals.isEmpty())
    }

    @Test
    fun `standalone Ed does not match`() {
        // "Ed" as a character name, not ending
        val chapters = chapters(
            0L to "Ed arrives",
            300_000L to "The battle"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, 600_000L)
        assertTrue(intervals.isEmpty())
    }

    @Test
    fun `OP with number suffix matches`() {
        val chapters = chapters(
            0L to "Prologue",
            90_000L to "OP 2",
            180_000L to "Episode"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        assertTrue(intervals.any { it.type == "intro" })
    }

    @Test
    fun `Opening Theme matches`() {
        val chapters = chapters(
            0L to "Cold Open",
            90_000L to "Opening Theme",
            180_000L to "Episode"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        assertTrue(intervals.any { it.type == "intro" })
    }

    @Test
    fun `last chapter uses duration as end time`() {
        val chapters = chapters(
            0L to "Episode",
            1_200_000L to "ED"
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
        val ending = intervals.find { it.type == "outro" }
        // (1440 - 1200) = 240s = exactly at MAX_DURATION limit
        assertEquals(durationMs / 1000.0, ending!!.endTime, 0.01)
    }
}
