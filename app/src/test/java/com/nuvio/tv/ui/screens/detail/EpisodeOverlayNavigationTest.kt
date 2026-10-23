package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent
import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeOverlayNavigationTest {

    private fun episode(n: Int) = Video(
        id = "ep$n",
        title = "Episode $n",
        released = null,
        thumbnail = null,
        season = 1,
        episode = n,
        overview = null
    )

    private val episodes = listOf(episode(1), episode(2), episode(3))

    @Test
    fun `steps forward and back within the list`() {
        assertEquals("ep3", adjacentEpisode(episodes, episode(2), 1)?.id)
        assertEquals("ep1", adjacentEpisode(episodes, episode(2), -1)?.id)
    }

    @Test
    fun `stops at either end instead of wrapping`() {
        assertNull(adjacentEpisode(episodes, episode(3), 1))
        assertNull(adjacentEpisode(episodes, episode(1), -1))
    }

    @Test
    fun `an episode missing from the list does not jump to the start`() {
        assertNull(adjacentEpisode(episodes, episode(9), 1))
    }

    @Test
    fun `right steps forward and left steps back`() {
        assertEquals(1, episodeStepForKey(KeyEvent.KEYCODE_DPAD_RIGHT, isRtl = false))
        assertEquals(-1, episodeStepForKey(KeyEvent.KEYCODE_DPAD_LEFT, isRtl = false))
    }

    @Test
    fun `rtl mirrors the direction`() {
        assertEquals(-1, episodeStepForKey(KeyEvent.KEYCODE_DPAD_RIGHT, isRtl = true))
        assertEquals(1, episodeStepForKey(KeyEvent.KEYCODE_DPAD_LEFT, isRtl = true))
    }

    @Test
    fun `other keys do not step`() {
        assertEquals(0, episodeStepForKey(KeyEvent.KEYCODE_DPAD_UP, isRtl = false))
        assertEquals(0, episodeStepForKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, isRtl = false))
    }
}
