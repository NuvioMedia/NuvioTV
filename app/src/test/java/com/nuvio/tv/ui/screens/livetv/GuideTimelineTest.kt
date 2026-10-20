package com.nuvio.tv.ui.screens.livetv

import com.nuvio.tv.domain.model.LiveTvProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTimelineTest {
    @Test fun clipsWindowPreservesGapsAndSkipsOverlappingEntries() {
        val hour = 60L * 60_000L
        val entries = listOf(
            LiveTvProgram("s", "c", "Earlier", -hour / 2, hour / 2),
            LiveTvProgram("s", "c", "News", hour, hour * 2),
            LiveTvProgram("s", "c", "Overlap", hour + hour / 2, hour * 3),
            LiveTvProgram("other", "c", "Other source", hour * 2, hour * 3),
        )

        val segments = guideSegments(entries, "s", "c", 0, hour * 3)

        assertEquals(listOf("Earlier", "News", "Overlap"), segments.map { it.program.title })
        assertEquals(0, segments.first().gapBeforeMinutes)
        assertEquals(30, segments.first().durationMinutes)
        assertEquals(30, segments[1].gapBeforeMinutes)
        assertEquals(60, segments[1].durationMinutes)
        assertEquals(0, segments.last().gapBeforeMinutes)
        assertEquals(60, segments.last().durationMinutes)
        assertTrue(segments.all { it.endMillis <= hour * 3 })
    }

    @Test fun invalidWindowAndExpiredProgramsProduceNoCells() {
        val entry = LiveTvProgram("s", "c", "Expired", 0, 60_000)

        assertTrue(guideSegments(listOf(entry), "s", "c", 120_000, 60_000).isEmpty())
        assertTrue(guideSegments(listOf(entry), "s", "c", 60_000, 120_000).isEmpty())
    }
}
