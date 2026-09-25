package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.EpisodeShuffleProfile
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.EpisodeShuffle
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEpisodeShuffleTest {
    private val videos = (1..5).map { Video("show:1:$it", "Episode $it", "2020-01-01", "image-$it",
        season = 1, episode = it, overview = "Description $it") }
    private val upcoming = ContinueWatchingItem.NextUp(NextUpInfo(
        "show", "series", "Show", null, null, null, "show:2:1", 2, 1, "Upcoming",
        thumbnail = null, released = "2999-01-01", hasAired = false, airDateLabel = "Soon",
        lastWatched = 100, sortTimestamp = 100, isReleaseAlert = true, isNewSeasonRelease = true
    ))
    private val state = HomeUiState(upcomingItems = listOf(upcoming))
    private val profile = EpisodeShuffleProfile(1, true, mapOf("show" to EpisodeShuffleSettings(true, false)))
    private val shuffle = EpisodeShuffle()

    @Test
    fun `card details and playback id describe the same aired random episode`() {
        val card = project().continueWatchingItems.single() as ContinueWatchingItem.NextUp
        val episode = videos.single { it.id == card.info.videoId }
        assertEquals(episode.title, card.info.episodeTitle)
        assertEquals(episode.overview, card.info.episodeDescription)
        assertEquals(episode.thumbnail, card.info.thumbnail)
        assertEquals(episode.episode, card.info.episode)
        assertTrue(card.info.hasAired)
        assertTrue(card.shufflePlayback)
        assertFalse(card.info.isReleaseAlert)
        assertFalse(card.info.isNewSeasonRelease)
        assertTrue(project().upcomingItems.isEmpty())
    }

    @Test
    fun `disabling shuffle restores native upcoming data`() {
        project()
        assertEquals(state, project(profile = profile.copy(available = false)))
        assertEquals(state, project(profile = profile.copy(shows = emptyMap())))
    }

    @Test
    fun `home emissions preserve selection and a new visit advances it`() {
        val first = project().continueWatchingItems.single()
        repeat(20) { assertEquals(first, project().continueWatchingItems.single()) }
        assertNotEquals(first, project(visit = 2).continueWatchingItems.single())
    }

    @Test
    fun `focus identity follows the show across random targets and resume`() {
        val first = project().continueWatchingItems.single()
        val second = project(visit = 2).continueWatchingItems.single()
        val resume = ContinueWatchingItem.InProgress(progress(), shufflePlayback = true)
        assertEquals(first.shuffleFocusKey, second.shuffleFocusKey)
        assertEquals(first.shuffleFocusKey, resume.shuffleFocusKey)
        assertEquals(continueWatchingItemKey(first), continueWatchingItemKey(second))
    }

    @Test
    fun `continue watching keeps the exact incomplete episode and position`() {
        val resume = ContinueWatchingItem.InProgress(progress())
        val projected = project(input = HomeUiState(continueWatchingItems = listOf(resume)))
        val card = projected.continueWatchingItems.single() as ContinueWatchingItem.InProgress
        assertEquals(resume.progress, card.progress)
        assertTrue(card.shufflePlayback)
    }

    @Test
    fun `latest resume replaces transient next up and older progress without duplicate focus keys`() {
        val older = ContinueWatchingItem.InProgress(progress())
        val latest = ContinueWatchingItem.InProgress(progress().copy(videoId = "show:1:4", episode = 4, lastWatched = 200))
        val input = HomeUiState(continueWatchingItems = listOf(older, latest), upcomingItems = listOf(upcoming))
        val output = project(input)
        assertEquals(listOf(latest.copy(shufflePlayback = true)), output.continueWatchingItems)
        assertTrue(output.upcomingItems.isEmpty())
    }

    @Test
    fun `exhausted unwatched pool removes random next up without replacing resume`() {
        val watched = videos.map { it.season!! to it.episode!! }.toSet()
        assertTrue(project(watched = mapOf("show" to watched)).continueWatchingItems.isEmpty())
        val resume = ContinueWatchingItem.InProgress(progress())
        val input = HomeUiState(continueWatchingItems = listOf(resume))
        assertEquals(resume.progress, (project(input, watched = mapOf("show" to watched))
            .continueWatchingItems.single() as ContinueWatchingItem.InProgress).progress)
    }

    @Test
    fun `late metadata makes a hidden random card available`() {
        assertTrue(project(catalogue = null).continueWatchingItems.isEmpty())
        assertEquals(1, project().continueWatchingItems.size)
    }

    @Test
    fun `removing a native card cannot be undone by the shuffle projection`() {
        project()
        assertTrue(project(HomeUiState()).continueWatchingItems.isEmpty())
    }

    @Test
    fun `movies and disabled shows preserve their native targets`() {
        val movie = ContinueWatchingItem.InProgress(progress().copy(contentType = "movie"))
        assertFalse((project(HomeUiState(continueWatchingItems = listOf(movie)))
            .continueWatchingItems.single()).shufflePlayback)
        val other = upcoming.copy(info = upcoming.info.copy(contentId = "other"))
        assertEquals(listOf(other), project(HomeUiState(upcomingItems = listOf(other))).upcomingItems)
    }

    @Test
    fun `completed watched ids exclude a target while all mode retains it`() {
        val allWatched = mapOf("show" to videos.map { it.season!! to it.episode!! }.toSet())
        val all = profile.copy(shows = mapOf("show" to EpisodeShuffleSettings(true, true)))
        assertEquals(1, project(profile = all, watched = allWatched).continueWatchingItems.size)
    }

    private fun project(
        input: HomeUiState = state,
        profile: EpisodeShuffleProfile = this.profile,
        watched: Map<String, Set<Pair<Int, Int>>> = emptyMap(),
        visit: Long = 1,
        catalogue: List<Video>? = videos
    ) = applyHomeShuffle(input, profile, watched, shuffle, visit, ContinueWatchingSortMode.SPLIT_UPCOMING) { _, _ -> catalogue }

    private fun progress() = WatchProgress("show", "series", "Show", null, null, null,
        "show:1:3", 1, 3, "Episode 3", 34000, 100000, 100, progressPercent = 34f)
}
