package com.nuvio.tv.domain.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RandomEpisodePickerTest {
    @Test
    fun `eligible episodes exclude specials unavailable and future episodes`() {
        val eligible = episode("s1e1")
        val result = RandomEpisodePicker.eligibleEpisodes(
            listOf(
                eligible,
                episode("special", season = 0),
                episode("unavailable", available = false),
                episode("future", released = "2999-01-01T00:00:00Z")
            )
        )

        assertEquals(listOf(eligible), result)
    }

    @Test
    fun `selection avoids session history`() {
        val unplayed = episode("s1e3")
        val result = RandomEpisodePicker.pick(
            videos = listOf(episode("s1e1"), episode("s1e2"), unplayed),
            excludedVideoIds = setOf("s1e1", "s1e2"),
            random = Random(1)
        )

        assertEquals(unplayed, result)
    }

    @Test
    fun `new cycle does not immediately replay current episode`() {
        val result = RandomEpisodePicker.pick(
            videos = listOf(episode("s1e1"), episode("s1e2")),
            excludedVideoIds = setOf("s1e1", "s1e2"),
            currentVideoId = "s1e2",
            random = Random(1)
        )

        assertEquals("s1e1", result?.id)
    }

    @Test
    fun `playback availability is respected`() {
        val result = RandomEpisodePicker.pick(
            videos = listOf(episode("s1e1"), episode("s1e2")),
            canPlay = { it.id == "s1e2" },
            random = Random(1)
        )

        assertEquals("s1e2", result?.id)
    }

    @Test
    fun `returns null when no episode is playable`() {
        val result = RandomEpisodePicker.pick(
            videos = listOf(episode("s1e1")),
            canPlay = { false }
        )

        assertNull(result)
    }

    @Test
    fun `seeded selection can choose different eligible episodes`() {
        val episodes = (1..12).map { episode("s1e$it", episode = it) }
        val picks = (1..20).mapNotNull { seed ->
            RandomEpisodePicker.pick(episodes, random = Random(seed))?.id
        }.toSet()

        assertFalse(picks.size == 1)
    }

    @Test
    fun `starting a new session resets prior cycle history`() {
        val contentId = "shuffle-session-test"
        ContinuousShuffleSession.start(contentId, "s1e1")
        ContinuousShuffleSession.record(contentId, "s1e2")

        ContinuousShuffleSession.start(contentId, "s2e1")

        assertEquals(setOf("s2e1"), ContinuousShuffleSession.history(contentId))
    }

    @Test
    fun `restarting a cycle retains only the current episode`() {
        val contentId = "shuffle-cycle-test"
        ContinuousShuffleSession.start(contentId, "s1e1")
        ContinuousShuffleSession.record(contentId, "s1e2")

        ContinuousShuffleSession.restartCycle(contentId, "s1e2")

        assertEquals(setOf("s1e2"), ContinuousShuffleSession.history(contentId))
    }

    private fun episode(
        id: String,
        season: Int = 1,
        episode: Int = 1,
        released: String = "2020-01-01T00:00:00Z",
        available: Boolean = true
    ) = Video(
        id = id,
        title = id,
        released = released,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null,
        available = available
    )
}
