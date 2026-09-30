package com.nuvio.tv.ui.screens.detail

import android.content.Context
import com.nuvio.tv.data.local.EpisodeShuffleProfile
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.EpisodeShuffle
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailEpisodeShuffleTest {
    private val context = mockk<Context>(relaxed = true)
    private val videos = (1..5).map { Video("show:1:$it", "Episode $it", "2020-01-01", null,
        season = 1, episode = it, overview = null) }
    private val meta = mockk<Meta> {
        every { id } returns "show"
        every { imdbId } returns "tt123"
        every { apiType } returns "series"
        every { type } returns ContentType.SERIES
        every { behaviorHints } returns null
        every { videos } returns this@DetailEpisodeShuffleTest.videos
    }
    private val native = NextToWatch(null, false, videos.first().id, 1, 1, "Play")
    private val state = MetaDetailsUiState(meta = meta, nextToWatch = native)
    private val profile = EpisodeShuffleProfile(1, true, mapOf("show" to EpisodeShuffleSettings(true, false)))
    private val shuffle = EpisodeShuffle()

    @Test
    fun `normal play returns when a show or global shuffle is disabled`() {
        assertEquals(native, project(profile = profile.copy(available = false)).nextToWatch)
        assertEquals(native, project(profile = profile.copy(shows = emptyMap())).nextToWatch)
    }

    @Test
    fun `a random main play target stays stable during detail updates`() {
        val first = project().nextToWatch
        repeat(20) { assertEquals(first, project().nextToWatch) }
        assertNotEquals(first?.nextVideoId, project(visit = 2).nextToWatch?.nextVideoId)
    }

    @Test
    fun `latest incomplete continue watching episode wins over stale native resume`() {
        val stale = state.copy(nextToWatch = native.copy(isResume = true))
        val latest = progress(4).copy(lastWatched = 10)
        val result = project(stale, listOf(progress(2), latest))
        assertEquals(latest.videoId, result.nextToWatch?.nextVideoId)
        assertEquals(latest, result.nextToWatch?.watchProgress)
        assertTrue(result.nextToWatch!!.isResume)
    }

    @Test
    fun `removing resume does not resurrect cached progress`() {
        val stale = state.copy(nextToWatch = native.copy(isResume = true, watchProgress = progress(1)))
        assertFalse(project(stale).nextToWatch!!.isResume)
        assertFalse(project(stale, listOf(progress(1).copy(progressPercent = 100f))).nextToWatch!!.isResume)
    }

    @Test
    fun `resume uses a precise saved video id even when catalogue metadata differs`() {
        val saved = progress(3).copy(videoId = "addon-specific-video")
        val next = project(resume = listOf(saved)).nextToWatch
        val video = resolveHeroPlaybackVideo(meta, next, videos)
        assertEquals(saved.videoId, video?.id)
        assertEquals(saved.season, video?.season)
        assertEquals(saved.episode, video?.episode)
    }

    @Test
    fun `an exhausted pool has no playback target but incomplete resume remains available`() {
        val complete = state.copy(watchedEpisodes = videos.map { it.season!! to it.episode!! }.toSet())
        assertTrue(project(complete).shufflePoolEmpty)
        assertNull(project(complete).nextToWatch?.nextVideoId)
        val resumed = project(complete, listOf(progress(3)))
        assertFalse(resumed.shufflePoolEmpty)
        assertEquals("show:1:3", resumed.nextToWatch?.nextVideoId)
    }

    @Test
    fun `imdb alias resume is accepted and other shows are excluded`() {
        val alias = progress(2).copy(contentId = "tt123")
        assertEquals(alias, project(resume = listOf(alias)).nextToWatch?.watchProgress)
        assertFalse(project(resume = listOf(alias.copy(contentId = "other"))).nextToWatch!!.isResume)
    }

    @Test
    fun `tiny precise positions remain resumable without duration metadata`() {
        val saved = progress(2).copy(progressPercent = null, position = 600, duration = 0)
        assertTrue(project(resume = listOf(saved)).nextToWatch!!.isResume)
    }

    @Test
    fun `enabling shuffle cannot change a movies play target`() {
        every { meta.apiType } returns "movie"
        val result = project()
        assertFalse(result.episodeShuffle.enabled)
        assertEquals(native, result.nextToWatch)
    }

    private fun project(
        input: MetaDetailsUiState = state,
        resume: List<WatchProgress> = emptyList(),
        profile: EpisodeShuffleProfile = this.profile,
        visit: Long = 1
    ) = applyDetailShuffle(input, profile, resume, shuffle, visit, context)

    private fun progress(number: Int) = WatchProgress(
        "show", "series", "Show", null, null, null, "show:1:$number", 1, number,
        "Episode $number", 100, 1000, 1, progressPercent = 50f
    )
}
