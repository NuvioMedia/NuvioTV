package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [collectActiveSidecarCues] stops at the first cue starting after the playhead, so it depends on
 * the cue list being ordered by start time. These cover that contract and the parse path that
 * feeds it.
 */
class SidecarCueOrderingTest {

    private fun entry(startMs: Long, endMs: Long, text: String): CuesWithTiming {
        val startUs = startMs * 1_000L
        val endUs = endMs * 1_000L
        return CuesWithTiming(
            listOf(Cue.Builder().setText(text).build()),
            startUs,
            (endUs - startUs).coerceAtLeast(1L)
        )
    }

    private fun texts(cues: List<Cue>): List<String> = cues.map { it.text.toString() }

    @Test
    fun `lenient parse orders cues that appear out of order in the file`() {
        val srt = """
            1
            00:00:10,000 --> 00:00:12,000
            third

            2
            00:00:02,000 --> 00:00:04,000
            first

            3
            00:00:06,000 --> 00:00:08,000
            second
        """.trimIndent()

        val parsed = parseSidecarTimedCuesLenient(srt, "https://example.test/subs.srt")

        assertEquals(3, parsed.size)
        assertEquals(listOf(2_000_000L, 6_000_000L, 10_000_000L), parsed.map { it.startTimeUs })
        assertEquals(
            listOf("first", "second", "third"),
            parsed.map { it.cues.single().text.toString() }
        )
    }

    @Test
    fun `ordered cues are required for the early-break scan`() {
        // What the lenient parser produced before it sorted: the 2s cue sits after the 10s one.
        val unordered = listOf(
            entry(10_000, 12_000, "late"),
            entry(2_000, 4_000, "early")
        )

        // At 3s the second entry is active, but the scan stops at the first entry because it
        // starts later, so nothing is returned.
        assertEquals(emptyList<String>(), texts(collectActiveSidecarCues(unordered, 3_000_000L)))

        // Sorted, the same cue is found.
        val ordered = unordered.sortedBy { it.startTimeUs }
        assertEquals(listOf("early"), texts(collectActiveSidecarCues(ordered, 3_000_000L)))
    }

    @Test
    fun `ordered cues resolve by position`() {
        val cues = listOf(
            entry(1_000, 3_000, "a"),
            entry(4_000, 6_000, "b"),
            entry(7_000, 9_000, "c")
        )

        assertEquals(emptyList<String>(), texts(collectActiveSidecarCues(cues, 500_000L)))
        assertEquals(listOf("a"), texts(collectActiveSidecarCues(cues, 2_000_000L)))
        assertEquals(emptyList<String>(), texts(collectActiveSidecarCues(cues, 3_500_000L)))
        assertEquals(listOf("b"), texts(collectActiveSidecarCues(cues, 5_000_000L)))
        assertEquals(listOf("c"), texts(collectActiveSidecarCues(cues, 8_000_000L)))
        assertEquals(emptyList<String>(), texts(collectActiveSidecarCues(cues, 20_000_000L)))
    }

    @Test
    fun `a long cue stays active while later cues come and go`() {
        // A sign or caption spanning the whole scene, with dialogue inside it.
        val cues = listOf(
            entry(1_000, 30_000, "sign"),
            entry(5_000, 7_000, "dialogue")
        )

        assertEquals(listOf("sign"), texts(collectActiveSidecarCues(cues, 3_000_000L)))
        assertEquals(listOf("sign", "dialogue"), texts(collectActiveSidecarCues(cues, 6_000_000L)))
        assertEquals(listOf("sign"), texts(collectActiveSidecarCues(cues, 10_000_000L)))
        assertEquals(emptyList<String>(), texts(collectActiveSidecarCues(cues, 31_000_000L)))
    }

    @Test
    fun `overlapping cues out of order are all found once sorted`() {
        // A long caption listed before a dialogue line that starts earlier. Overlap plus
        // inversion, which is the shape a merged or hand-edited file tends to have.
        val unordered = listOf(
            entry(10_000, 20_000, "long"),
            entry(5_000, 15_000, "early")
        )

        // At 7s only "early" is active, but the scan stops on "long" because it starts later,
        // so "early" is never reached.
        assertEquals(emptyList<String>(), texts(collectActiveSidecarCues(unordered, 7_000_000L)))

        val ordered = unordered.sortedBy { it.startTimeUs }
        assertEquals(listOf("early"), texts(collectActiveSidecarCues(ordered, 7_000_000L)))
        // At 12s the two overlap and both have to come back.
        assertEquals(listOf("early", "long"), texts(collectActiveSidecarCues(ordered, 12_000_000L)))
    }

    @Test
    fun `strip sdh changes the signature so toggling it redraws`() {
        val cues = listOf(Cue.Builder().setText("[door creaks] hello").build())

        assertNotEquals(
            activeCueSignature(cues, stripSdh = false),
            activeCueSignature(cues, stripSdh = true)
        )
        assertNotEquals(
            activeCueSignature(emptyList(), stripSdh = false),
            activeCueSignature(emptyList(), stripSdh = true)
        )
    }

    @Test
    fun `cue end is exclusive so adjacent cues do not overlap`() {
        val cues = listOf(
            entry(1_000, 2_000, "a"),
            entry(2_000, 3_000, "b")
        )

        assertEquals(listOf("b"), texts(collectActiveSidecarCues(cues, 2_000_000L)))
    }
}
