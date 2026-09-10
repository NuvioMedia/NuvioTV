package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeSwitchTargetTest {

    @Test
    fun `selecting a different episode leaves the current one`() {
        assertTrue(episodeSwitchLeavesEpisode(targetVideoId = "tt0944947:8:3", currentVideoId = "tt0944947:8:2"))
    }

    @Test
    fun `selecting another source for the episode already playing does not`() {
        assertFalse(episodeSwitchLeavesEpisode(targetVideoId = "tt0944947:8:2", currentVideoId = "tt0944947:8:2"))
    }

    @Test
    fun `an unresolved target stays on the current episode`() {
        assertFalse(episodeSwitchLeavesEpisode(targetVideoId = null, currentVideoId = "tt0944947:8:2"))
    }

    @Test
    fun `an unknown current episode stays conservative`() {
        assertFalse(episodeSwitchLeavesEpisode(targetVideoId = "tt0944947:8:3", currentVideoId = null))
    }
}
