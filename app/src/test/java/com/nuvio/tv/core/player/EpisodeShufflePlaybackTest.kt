package com.nuvio.tv.core.player

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.EpisodeShuffleProfile
import com.nuvio.tv.data.local.EpisodeShuffleStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.EpisodeShuffle
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeShufflePlaybackTest {
    private val show = "tmdb:123"
    private val episodes = (1..5).map { Video("$show:1:$it", "Episode $it", "2020-01-01", null,
        season = 1, episode = it, overview = null) }
    private val settings = MutableStateFlow(EpisodeShuffleProfile(1, true,
        mapOf(show to EpisodeShuffleSettings(true, false))))
    private val progress = MutableStateFlow<Map<Pair<Int, Int>, WatchProgress>>(emptyMap())
    private val watched = MutableStateFlow<Set<Pair<Int, Int>>>(emptySet())
    private val active = MutableStateFlow(1)
    private val store = mockk<EpisodeShuffleStore> {
        every { observeProfile(any()) } returns settings
    }
    private val repository = mockk<WatchProgressRepository> {
        every { getAllEpisodeProgress(any(), any()) } returns progress
        every { isWatchedByVideoId(any(), any()) } returns false
    }
    private val watchedPreferences = mockk<WatchedItemsPreferences> {
        every { getWatchedEpisodesForContent(any(), any()) } returns watched
    }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
    private fun service() = EpisodeShufflePlayback(store, EpisodeShuffle(), repository, watchedPreferences, profiles)
    private val service = service()
    private val metadata = ExternalPlaybackMetadata(show, "series", "Show", null, null, null,
        "$show:1:1", 1, 1, null, null, 1)

    @Test
    fun `namespaced content ids are passed intact to progress sources`() = runTest {
        service.observe(1, show, "tv").first()
        verify(exactly = 1) { repository.getAllEpisodeProgress("tmdb:123", 1) }
        verify(exactly = 1) { watchedPreferences.getWatchedEpisodesForContent("tmdb:123", 1) }
    }

    @Test
    fun `native next target survives external player handoff`() = runTest {
        val state = service.observe(1, show, "series").first()
        val native = service.nextEpisode(1, show, episodes, 1, 1, state)!!
        val external = service.externalSnapshot(metadata, episodes)
        assertEquals(native.id, external.nextVideoId)
        assertEquals(native.episode, external.nextEpisode)
        assertTrue(external.shufflePlayback)
        assertNotEquals(metadata.videoId, external.nextVideoId)
    }

    @Test
    fun `persisted external target is restored after service recreation`() = runTest {
        val saved = service.externalSnapshot(metadata, episodes)
        val restored = service().externalSnapshot(metadata, episodes, saved.nextVideoId)
        assertEquals(saved, restored)
    }

    @Test
    fun `completed target is replaced when returning from external playback`() = runTest {
        val initial = service.externalSnapshot(metadata, episodes)
        watched.value = setOf(initial.nextSeason!! to initial.nextEpisode!!)
        val updated = service.externalSnapshot(metadata, episodes, initial.nextVideoId)
        assertNotEquals(initial.nextVideoId, updated.nextVideoId)
        assertTrue(updated.hasNextEpisode == true)
    }

    @Test
    fun `unwatched exhaustion produces an authoritative empty snapshot`() = runTest {
        watched.value = (2..5).map { 1 to it }.toSet()
        val snapshot = service.externalSnapshot(metadata, episodes)
        assertTrue(snapshot.metadataResolved)
        assertTrue(snapshot.shufflePlayback)
        assertFalse(snapshot.hasNextEpisode!!)
        assertNull(snapshot.nextVideoId)
    }

    @Test
    fun `missing launch season uses the current video to prevent replaying itself`() = runTest {
        settings.value = settings.value.copy(shows = mapOf(show to EpisodeShuffleSettings(true, true)))
        assertFalse(service.externalSnapshot(metadata.copy(season = null), episodes.take(1)).hasNextEpisode!!)
    }

    @Test
    fun `disabled shuffle restores the sequential successor`() = runTest {
        settings.value = settings.value.copy(shows = mapOf(show to EpisodeShuffleSettings(false, true)))
        assertEquals(2, service.externalSnapshot(metadata, episodes).nextEpisode)
        assertFalse(service.externalSnapshot(metadata, episodes).shufflePlayback)
    }

    @Test
    fun `global off and movies retain normal playback`() = runTest {
        settings.value = settings.value.copy(available = false)
        assertEquals(2, service.externalSnapshot(metadata, episodes).nextEpisode)
        settings.value = settings.value.copy(available = true)
        assertFalse(service.externalSnapshot(metadata.copy(contentType = "movie"), episodes).shufflePlayback)
    }

    @Test
    fun `remote watched results are included for the captured profile`() = runTest {
        every { repository.isWatchedByVideoId(any(), any()) } returns true
        assertFalse(service.externalSnapshot(metadata, episodes).hasNextEpisode!!)
    }

    @Test
    fun `another active profiles remote cache cannot filter this playback`() = runTest {
        active.value = 2
        every { repository.isWatchedByVideoId(any(), any()) } returns true
        assertTrue(service.externalSnapshot(metadata, episodes).hasNextEpisode!!)
        verify(exactly = 0) { repository.isWatchedByVideoId(any(), any()) }
    }

    @Test
    fun `all mode can revisit completed episodes`() = runTest {
        settings.value = settings.value.copy(shows = mapOf(show to EpisodeShuffleSettings(true, true)))
        watched.value = (1..5).map { 1 to it }.toSet()
        assertTrue(service.externalSnapshot(metadata, episodes).hasNextEpisode!!)
    }
}
