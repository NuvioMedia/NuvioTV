package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCompletionRulesTest {

    private val shortEpisodeMs = 22 * 60_000L
    private val longEpisodeMs = 60 * 60_000L

    @Test
    fun `exit below the provider floor is not finished`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (shortEpisodeMs * 0.79).toLong(),
                durationMs = shortEpisodeMs
            )
        )
    }

    @Test
    fun `short episode in the provider band is finished on remaining time`() {
        // 85% of 22 minutes leaves 3m18s.
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (shortEpisodeMs * 0.85).toLong(),
                durationMs = shortEpisodeMs
            )
        )
    }

    @Test
    fun `long episode at the same percentage is not finished`() {
        // 85% of 60 minutes leaves 9 minutes.
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.85).toLong(),
                durationMs = longEpisodeMs
            )
        )
    }

    @Test
    fun `intro segments are ignored`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.85).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(startTime = 30.0, endTime = 90.0, type = "intro", provider = "introdb")
                )
            )
        )
    }

    @Test
    fun `just below the provider floor is not finished`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (shortEpisodeMs * 0.799).toLong(),
                durationMs = shortEpisodeMs
            )
        )
    }

    @Test
    fun `remaining time does not finish an item below the provider floor`() {
        // The floor is evaluated first. A long tail of runtime already consumed must not let the
        // remaining-time rule complete an item the provider still considers in progress.
        val durationMs = 3 * 60 * 60_000L
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (durationMs * 0.75).toLong(),
                durationMs = durationMs,
                skipIntervals = listOf(
                    SkipInterval(
                        startTime = (durationMs * 0.74) / 1_000.0,
                        endTime = durationMs / 1_000.0,
                        type = "outro",
                        provider = "introdb"
                    )
                )
            )
        )
    }

    // The remaining-time rule is only reachable below the fallback threshold, which needs a
    // runtime under 40 minutes: at four minutes remaining a longer episode is already past 90%.
    private val midEpisodeMs = 35 * 60_000L

    @Test
    fun `exactly the remaining time bound is finished`() {
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = midEpisodeMs - PlaybackCompletionRules.COMPLETION_REMAINING_MS,
                durationMs = midEpisodeMs
            )
        )
    }

    @Test
    fun `one millisecond short of the remaining time bound is not finished`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = midEpisodeMs - PlaybackCompletionRules.COMPLETION_REMAINING_MS - 1L,
                durationMs = midEpisodeMs
            )
        )
    }

    @Test
    fun `exactly the provider floor with runtime left is not finished`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.80).toLong(),
                durationMs = longEpisodeMs
            )
        )
    }

    @Test
    fun `just short of the fallback threshold is not finished`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.8999).toLong(),
                durationMs = longEpisodeMs
            )
        )
    }

    @Test
    fun `exactly the fallback threshold is finished`() {
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.90).toLong(),
                durationMs = longEpisodeMs
            )
        )
    }

    /** The longest a segment may run and still read as credits. */
    private val maxEndingSeconds = PlaybackCompletionRules.COMPLETION_REMAINING_MS / 1_000.0

    /**
     * A 90 second ED at 17:00 of a 22 minute episode, ending 2:30 before the file end.
     *
     * This is the shape the clause exists for. Entering it at the 80% floor leaves 4:24, so the
     * remaining-time rule does not yet apply and only the ending qualifies the exit.
     */
    private fun terminalEnding(type: String = "outro") = SkipInterval(
        startTime = 17 * 60.0,
        endTime = 18 * 60.0 + 30.0,
        type = type,
        provider = "aniskip"
    )

    /** The same shape on a 60 minute file: credits at 52:30 running to 56:20. */
    private fun longTerminalEnding(type: String = "outro") = SkipInterval(
        startTime = 52 * 60.0 + 30.0,
        endTime = 56 * 60.0 + 20.0,
        type = type,
        provider = "introdb"
    )

    @Test
    fun `crossing into a terminal ending finishes a short episode inside the band`() {
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (shortEpisodeMs * 0.80).toLong(),
                durationMs = shortEpisodeMs,
                skipIntervals = listOf(terminalEnding())
            )
        )
    }

    @Test
    fun `crossing into a terminal ending finishes a long episode inside the band`() {
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.88).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(longTerminalEnding())
            )
        )
    }

    @Test
    fun `crossing a terminal ending start is enough, without reaching its end`() {
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (shortEpisodeMs * 0.805).toLong(),
                durationMs = shortEpisodeMs,
                skipIntervals = listOf(terminalEnding())
            )
        )
    }

    @Test
    fun `a terminal ending that has not started yet does not finish the episode`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.85).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(longTerminalEnding())
            )
        )
    }

    @Test
    fun `an ending far from the end of the file does not finish the episode`() {
        // Credits at 48:00-49:30 of a 60 minute file leave over ten minutes of runtime, so
        // crossing them is not evidence the episode is over.
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.801).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(48 * 60.0, 49 * 60.0 + 30.0, "outro", "introdb")
                )
            )
        )
    }

    @Test
    fun `an ending too long to be credits does not finish the episode`() {
        // 42:00 to the end of a 60 minute file. The end is terminal, but eighteen minutes is not
        // a credits sequence, and entering it at 48:00 leaves twelve.
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.80).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(42 * 60.0, longEpisodeMs / 1_000.0, "outro", "introdb")
                )
            )
        )
    }

    @Test
    fun `an ending exactly at the maximum credits length qualifies`() {
        // Earliest terminal end, so the segment start stays below the 90% fallback and the
        // length bound is what decides.
        val endSeconds = (longEpisodeMs / 1_000.0) - maxEndingSeconds
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.87).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(endSeconds - maxEndingSeconds, endSeconds, "outro", "introdb")
                )
            )
        )
    }

    @Test
    fun `an ending one second longer than the maximum does not qualify`() {
        val endSeconds = (longEpisodeMs / 1_000.0) - maxEndingSeconds
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.87).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(
                        endSeconds - maxEndingSeconds - 1.0,
                        endSeconds,
                        "outro",
                        "introdb"
                    )
                )
            )
        )
    }

    @Test
    fun `ed and mixed-ed count as ending segments`() {
        listOf("ed", "mixed-ed").forEach { type ->
            assertTrue(
                type,
                PlaybackCompletionRules.isFinishedOnExit(
                    positionMs = (shortEpisodeMs * 0.80).toLong(),
                    durationMs = shortEpisodeMs,
                    skipIntervals = listOf(terminalEnding(type))
                )
            )
        }
    }

    @Test
    fun `a position beyond the duration is finished`() {
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = longEpisodeMs + 5_000L,
                durationMs = longEpisodeMs
            )
        )
    }

    @Test
    fun `an outro before the provider floor does not finish the episode`() {
        // A mid-episode ED cannot promote an exit that is below the provider floor.
        val startSeconds = (longEpisodeMs * 0.50) / 1_000.0

        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.70).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(startSeconds, startSeconds + 90.0, "ed", "aniskip")
                )
            )
        )
    }

    @Test
    fun `an ending exactly at the terminal window bound qualifies`() {
        // Earliest end the window accepts, with a credits-length segment so the end is what
        // decides.
        val endSeconds = (longEpisodeMs - PlaybackCompletionRules.COMPLETION_REMAINING_MS) / 1_000.0
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.885).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(endSeconds - 180.0, endSeconds, "outro", "introdb")
                )
            )
        )
    }

    @Test
    fun `an ending one millisecond outside the terminal window does not qualify`() {
        val endSeconds =
            (longEpisodeMs - PlaybackCompletionRules.COMPLETION_REMAINING_MS - 1L) / 1_000.0
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.885).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(endSeconds - 180.0, endSeconds, "outro", "introdb")
                )
            )
        )
    }

    @Test
    fun `a non-finite segment time is ignored`() {
        listOf(
            Double.NaN to 18 * 60.0 + 30.0,
            17 * 60.0 to Double.NaN,
            Double.NEGATIVE_INFINITY to 18 * 60.0 + 30.0,
            17 * 60.0 to Double.POSITIVE_INFINITY
        ).forEach { (start, end) ->
            assertFalse(
                "$start..$end",
                PlaybackCompletionRules.isFinishedOnExit(
                    positionMs = (shortEpisodeMs * 0.80).toLong(),
                    durationMs = shortEpisodeMs,
                    skipIntervals = listOf(SkipInterval(start, end, "outro", "aniskip"))
                )
            )
        }
    }

    @Test
    fun `a switch that keeps the current item never completes it`() {
        assertFalse(
            PlaybackCompletionRules.resolveExitCompletion(
                leavesCurrentItem = false,
                positionMs = (shortEpisodeMs * 0.99).toLong(),
                durationMs = shortEpisodeMs
            )
        )
    }

    @Test
    fun `an exit completes when the rule qualifies`() {
        assertTrue(
            PlaybackCompletionRules.resolveExitCompletion(
                leavesCurrentItem = true,
                positionMs = (shortEpisodeMs * 0.85).toLong(),
                durationMs = shortEpisodeMs
            )
        )
    }

    @Test
    fun `an unreported position never completes`() {
        assertFalse(
            PlaybackCompletionRules.resolveExitCompletion(
                leavesCurrentItem = true,
                positionMs = null,
                durationMs = shortEpisodeMs
            )
        )
    }

    @Test
    fun `the fallback tracks the existing completion threshold`() {
        assertEquals(90f, PlaybackCompletionRules.FALLBACK_COMPLETION_PERCENT, 0.001f)
        assertEquals(
            WatchProgress.COMPLETED_THRESHOLD,
            PlaybackCompletionRules.FALLBACK_COMPLETION_PERCENT / 100f,
            0.0001f
        )
    }

    @Test
    fun `at or under twenty minutes the remaining-time rule coincides with the floor`() {
        // Four minutes is 20% of a twenty minute runtime, so at that length and below there is no
        // 80-90% band left: clearing the floor already means four minutes or less remain.
        val twentyMinutesMs = 20 * 60_000L
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (twentyMinutesMs * 0.80).toLong() + 1_000L,
                durationMs = twentyMinutesMs
            )
        )

        val fifteenMinutesMs = 15 * 60_000L
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (fifteenMinutesMs * 0.80).toLong() + 1_000L,
                durationMs = fifteenMinutesMs
            )
        )
    }

    @Test
    fun `just over twenty minutes there is a band again`() {
        val twentyOneMinutesMs = 21 * 60_000L
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (twentyOneMinutesMs * 0.80).toLong() + 1_000L,
                durationMs = twentyOneMinutesMs
            )
        )
    }

    @Test
    fun `at forty minutes the remaining-time rule adds nothing`() {
        // Four minutes remaining lands exactly on the fallback threshold here, so the two rules
        // coincide and no band exists: 80% still leaves eight minutes.
        val fortyMinutesMs = 40 * 60_000L
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (fortyMinutesMs * 0.80).toLong(),
                durationMs = fortyMinutesMs
            )
        )
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = fortyMinutesMs - PlaybackCompletionRules.COMPLETION_REMAINING_MS,
                durationMs = fortyMinutesMs
            )
        )
    }

    @Test
    fun `just under forty minutes a band opens below the fallback`() {
        // 39 minutes: four minutes remaining is 89.74%, so there is a narrow band under 90%.
        val thirtyNineMinutesMs = 39 * 60_000L
        assertTrue(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = thirtyNineMinutesMs - PlaybackCompletionRules.COMPLETION_REMAINING_MS,
                durationMs = thirtyNineMinutesMs
            )
        )
    }

    @Test
    fun `just over forty minutes no band opens below the fallback`() {
        // 41 minutes: four minutes remaining is 90.24%, past the fallback, so nothing under 90%
        // can qualify on remaining time.
        val fortyOneMinutesMs = 41 * 60_000L
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (fortyOneMinutesMs * 0.899).toLong(),
                durationMs = fortyOneMinutesMs
            )
        )
    }

    @Test
    fun `the floor bounds the remaining time rule on very short runtimes`() {
        // Four minutes is most of a ten minute item. Without the floor the remaining-time rule
        // would complete it barely past the midpoint. This holds with no tracker connected.
        val tenMinutesMs = 10 * 60_000L
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = tenMinutesMs - PlaybackCompletionRules.COMPLETION_REMAINING_MS,
                durationMs = tenMinutesMs
            )
        )
    }

    @Test
    fun `an open-ended sentinel end does not qualify as terminal`() {
        // SkipIntroRepository gives the last animeskip timestamp endTime = Double.MAX_VALUE. Left
        // unguarded that passes the terminal window unconditionally, completing a mid-file ed.
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.85).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(
                        startTime = longEpisodeMs * 0.70 / 1_000.0,
                        endTime = Double.MAX_VALUE,
                        type = "ed",
                        provider = "animeskip"
                    )
                )
            )
        )
    }

    @Test
    fun `a segment with a negative start is ignored`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.85).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(-30.0, longEpisodeMs / 1_000.0, "outro", "introdb")
                )
            )
        )
    }

    @Test
    fun `a segment with its start after its end is ignored`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(
                positionMs = (longEpisodeMs * 0.85).toLong(),
                durationMs = longEpisodeMs,
                skipIntervals = listOf(
                    SkipInterval(
                        startTime = longEpisodeMs * 0.84 / 1_000.0,
                        endTime = longEpisodeMs * 0.10 / 1_000.0,
                        type = "outro",
                        provider = "introdb"
                    )
                )
            )
        )
    }

    @Test
    fun `a negative position is never finished`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(positionMs = -1L, durationMs = longEpisodeMs)
        )
    }

    @Test
    fun `unknown duration is never finished`() {
        assertFalse(
            PlaybackCompletionRules.isFinishedOnExit(positionMs = 1_000L, durationMs = 0L)
        )
    }
}
