package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTimingAlignerTest {
    @Test
    fun `finds a constant offset across translated cue segmentation`() {
        val target = timeline(0L, 140, 3_200L)
        val reference = target.mapIndexedNotNull { index, cue ->
            if (index % 7 == 0) null else cue.copy(
                startMs = cue.startMs + 4_500L + (index % 3 - 1) * 120L,
                endMs = cue.endMs + 4_500L + (index % 3 - 1) * 120L
            )
        }

        val model = SubtitleTimingAligner.align(reference, target)
        assertNotNull(model)
        requireNotNull(model)

        assertEquals(1, model.segments.size)
        assertTrue(kotlin.math.abs(model.segments.single().offsetMs - 4_500L) <= 250L)
        assertTrue(model.confidence >= 0.4)
    }

    @Test
    fun `finds an offset when the translated track has fewer cues`() {
        val reference = timeline(0L, 180, 2_800L).map {
            it.copy(startMs = it.startMs + 6_000L, endMs = it.endMs + 6_000L)
        }
        val target = reference.filterIndexed { index, _ -> index % 2 == 0 }.map {
            it.copy(startMs = it.startMs - 6_000L, endMs = it.endMs - 6_000L)
        }

        val model = requireNotNull(SubtitleTimingAligner.align(reference, target))

        assertTrue(kotlin.math.abs(model.segments.single().offsetMs - 6_000L) <= 500L)
    }

    @Test
    fun `aligns a sparse full-duration subtitle index`() {
        val target = timeline(0L, 240, 3_000L)
        val reference = target.filterIndexed { index, _ -> index % 12 == 0 }.map {
            SrtCue(it.startMs + 3_500L, it.startMs + 4_500L, " ")
        }

        val model = requireNotNull(SubtitleTimingAligner.align(reference, target))

        assertTrue(model.toString(), kotlin.math.abs(model.segments.single().offsetMs - 3_500L) <= 500L)
    }

    @Test
    fun `finds an offset change caused by a video ad break`() {
        val target = timeline(0L, 220, 3_000L)
        val breakAt = 330_000L
        val reference = target.map { cue ->
            val offset = if (cue.startMs < breakAt) 2_000L else 32_000L
            cue.copy(startMs = cue.startMs + offset, endMs = cue.endMs + offset)
        }

        val model = SubtitleTimingAligner.align(reference, target)
        assertNotNull(model)
        requireNotNull(model)

        assertTrue(model.segments.size >= 2)
        assertTrue(model.segments.any { kotlin.math.abs(it.offsetMs - 2_000L) <= 500L })
        assertTrue(model.segments.any { kotlin.math.abs(it.offsetMs - 32_000L) <= 500L })
        val rewritten = model.rewrite(SrtDocument(target))
        assertTrue(rewritten.cues.first().startMs in 1_500L..2_500L)
        assertTrue(rewritten.cues.last().startMs - target.last().startMs in 31_500L..32_500L)
    }

    @Test
    fun `finds several independent timing regions`() {
        val target = timeline(0L, 300, 3_000L)
        val reference = target.map { cue ->
            val offset = when {
                cue.startMs < 300_000L -> 1_500L
                cue.startMs < 600_000L -> 31_500L
                else -> 46_500L
            }
            cue.copy(startMs = cue.startMs + offset, endMs = cue.endMs + offset)
        }

        val model = requireNotNull(SubtitleTimingAligner.align(reference, target))

        assertTrue(model.segments.size >= 3)
        assertTrue(model.segments.any { kotlin.math.abs(it.offsetMs - 1_500L) <= 500L })
        assertTrue(model.segments.any { kotlin.math.abs(it.offsetMs - 31_500L) <= 500L })
        assertTrue(model.segments.any { kotlin.math.abs(it.offsetMs - 46_500L) <= 500L })
    }

    @Test
    fun `keeps every cue when an inserted section is absent from the video`() {
        val target = timeline(0L, 240, 3_000L)
        val reference = target.map { cue ->
            val offset = if (cue.startMs < 360_000L) 32_000L else 2_000L
            cue.copy(startMs = cue.startMs + offset, endMs = cue.endMs + offset)
        }

        val model = requireNotNull(SubtitleTimingAligner.align(reference, target))
        val rewritten = model.rewrite(SrtDocument(target))

        assertTrue(model.segments.size >= 2)
        assertEquals(target.size, rewritten.cues.size)
        assertEquals(target.map(SrtCue::text).toSet(), rewritten.cues.map(SrtCue::text).toSet())
    }

    @Test
    fun `rejects sparse timelines`() {
        assertNull(SubtitleTimingAligner.align(timeline(0L, 5, 5_000L), timeline(0L, 5, 5_000L)))
    }

    @Test
    fun `rejects partial reference coverage for a full target subtitle`() {
        val target = timeline(0L, 220, 3_000L)
        val reference = target.take(35).map { cue ->
            cue.copy(startMs = cue.startMs + 2_000L, endMs = cue.endMs + 2_000L)
        }

        assertNull(SubtitleTimingAligner.align(reference, target))
    }

    @Test
    fun `rejects reference missing the end of the target timeline`() {
        val target = timeline(0L, 220, 3_000L)
        val reference = target.take(160).map { cue ->
            cue.copy(startMs = cue.startMs + 2_000L, endMs = cue.endMs + 2_000L)
        }

        assertNull(SubtitleTimingAligner.align(reference, target))
    }

    private fun timeline(startMs: Long, count: Int, spacingMs: Long): List<SrtCue> {
        var cueStart = startMs
        return List(count) { index ->
            cueStart += if (index == 0) 0L else spacingMs + ((index * 7_919L) % 1_700L) - 850L
            SrtCue(cueStart, cueStart + 1_400L + (index % 4) * 120L, "Line $index")
        }
    }
}
