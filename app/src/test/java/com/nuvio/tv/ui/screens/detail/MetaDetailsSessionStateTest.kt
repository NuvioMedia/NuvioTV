package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.NextToWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaDetailsSessionStateTest {
    @Test
    fun `next to watch cache normalizes content keys`() {
        val state = MetaDetailsSessionState()
        val nextToWatch = nextToWatch("Resume S1E2")

        state.putNextToWatch(
            profileId = 2,
            progressSource = "trakt",
            contentId = " TT123 ",
            contentType = "Series",
            nextToWatch = nextToWatch
        )

        assertEquals(
            nextToWatch,
            state.getNextToWatch(
                profileId = 2,
                progressSource = "TRAKT",
                contentId = "tt123",
                contentType = "series"
            )
        )
    }

    @Test
    fun `next to watch cache is isolated by profile and source`() {
        val state = MetaDetailsSessionState()

        state.putNextToWatch(
            profileId = 1,
            progressSource = "TRAKT",
            contentId = "tt123",
            contentType = "series",
            nextToWatch = nextToWatch("Play S1E1")
        )

        assertNull(state.getNextToWatch(2, "TRAKT", "tt123", "series"))
        assertNull(state.getNextToWatch(1, "SIMKL", "tt123", "series"))
    }

    private fun nextToWatch(label: String) = NextToWatch(
        watchProgress = null,
        isResume = false,
        nextVideoId = "tt123:1:2",
        nextSeason = 1,
        nextEpisode = 2,
        displayText = label
    )
}
