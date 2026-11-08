package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dense dialogue where the embedded track holds each line until the next one starts, so a run of
 * neighbouring delays overlaps equally well. The right delay is the one where line starts meet.
 */
class AutoSyncActivityPlateauTest {
    @Test
    fun delayOnlyPicksLineStartsInsideOverlapPlateau() {
        for (delayMs in listOf(0L, 2_300L, -4_100L)) {
            val (reference, target) = plateauPair(delayMs)
            for (relaxed in listOf(false, true)) {
                val alignment = checkNotNull(
                    AutoSyncTimelineRetimer.findDelayOnlyAlignment(
                        reference,
                        target,
                        allowAmbiguousMargin = relaxed,
                    ),
                ) { "delay $delayMs relaxed=$relaxed found no alignment" }
                assertTrue(
                    "delay $delayMs relaxed=$relaxed picked ${alignment.offsetMs}",
                    abs(alignment.offsetMs - delayMs) <= 100.0,
                )
            }
        }
    }

    @Test
    fun retimeLandsOnLineStartsInsideOverlapPlateau() {
        for (delayMs in listOf(0L, 2_300L, -4_100L)) {
            val (reference, target) = plateauPair(delayMs)
            val result = checkNotNull(
                AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, discoverAlignment = true),
            ) { "delay $delayMs not retimed" }
            assertTrue("delay $delayMs not confident", result.confident)
            val shift = result.cues.first().startTimeMs - result.cues.first().originalStartTimeMs
            assertTrue("delay $delayMs shifted by $shift", abs(shift - delayMs) <= 100L)
        }
    }

    private fun plateauPair(
        delayMs: Long,
        seed: Int = 7,
    ): Pair<List<SubtitleSyncCue>, List<SubtitleSyncCue>> {
        val random = Random(seed)
        val reference = ArrayList<SubtitleSyncCue>()
        val target = ArrayList<SubtitleSyncCue>()
        var blockStart = 20_000L
        repeat(40) {
            reference += SubtitleSyncCue(blockStart - 1_500L, blockStart, "Hey!")
            val lines = 3 + random.nextInt(4)
            var start = blockStart
            for (i in 0 until lines) {
                val length = 1_600L + random.nextInt(1_400)
                val next = start + length + 300L + random.nextInt(500)
                target += SubtitleSyncCue(start - delayMs, start - delayMs + length, "line")
                reference += SubtitleSyncCue(start, if (i == lines - 1) start + length else next, "line")
                start = next
            }
            blockStart = start + 6_000L + random.nextInt(9_000)
        }
        return reference to target
    }
}
