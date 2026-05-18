package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayTriggerConditionTest {

    @Test
    fun `auto-advance when only next-episode toggle is enabled`() {
        assertTrue(
            shouldAutoAdvanceAtEndOfEpisode(
                streamAutoPlayNextEpisodeEnabled = true,
                streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            )
        )
    }

    @Test
    fun `auto-advance when stream auto-select is FIRST_STREAM and toggle is off`() {
        assertTrue(
            shouldAutoAdvanceAtEndOfEpisode(
                streamAutoPlayNextEpisodeEnabled = false,
                streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            )
        )
    }

    @Test
    fun `auto-advance when stream auto-select is REGEX_MATCH and toggle is off`() {
        assertTrue(
            shouldAutoAdvanceAtEndOfEpisode(
                streamAutoPlayNextEpisodeEnabled = false,
                streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH,
            )
        )
    }

    @Test
    fun `auto-advance when both are enabled`() {
        assertTrue(
            shouldAutoAdvanceAtEndOfEpisode(
                streamAutoPlayNextEpisodeEnabled = true,
                streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            )
        )
    }

    @Test
    fun `no auto-advance when toggle off and mode is MANUAL`() {
        assertFalse(
            shouldAutoAdvanceAtEndOfEpisode(
                streamAutoPlayNextEpisodeEnabled = false,
                streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            )
        )
    }
}
