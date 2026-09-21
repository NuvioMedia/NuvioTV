package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoSyncCueProfileTest {
    @Test
    fun `pure sound descriptions are not dialogue`() {
        assertNull(
            SubtitleAutoSyncCueProfile.feature(
                SubtitleSyncCue(1_000L, 2_000L, "[door closes]")
            )
        )
    }

    @Test
    fun `mixed accessibility cue keeps the spoken text with reduced weight`() {
        val feature = SubtitleAutoSyncCueProfile.feature(
            SubtitleSyncCue(1_000L, 3_000L, "[door closes] Hello there")
        )

        requireNotNull(feature)
        assertEquals("Hello there", feature.normalizedText)
        assertTrue(feature.weight in 0.55..0.99)
    }

    @Test
    fun `positioned ass cue contributes less than ordinary dialogue`() {
        val ordinary = SubtitleAutoSyncCueProfile.feature(
            SubtitleSyncCue(1_000L, 2_000L, "Where are you?")
        )
        val positioned = SubtitleAutoSyncCueProfile.feature(
            SubtitleSyncCue(1_000L, 2_000L, "{\\an8}Where are you?")
        )

        requireNotNull(ordinary)
        requireNotNull(positioned)
        assertTrue(positioned.weight < ordinary.weight)
    }
}
