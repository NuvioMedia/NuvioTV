package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Home's long-press watched toggle and badges, for add-on types other than movie (here "youtube"). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomePosterWatchedTest {

    private val created = mutableListOf<HomeViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `marking a youtube video watched from Home stores it as one item of its own type`() {
        val repository = watchProgressRepository(watchedIds = emptySet())
        val viewModel = newViewModel(repository)

        viewModel.togglePosterMovieWatched(youtubeVideo())

        coVerify(timeout = 2_000, exactly = 1) {
            repository.markAsCompleted(
                match<WatchProgress> {
                    it.contentId == VIDEO_ID && it.contentType == "youtube" &&
                        it.season == null && it.episode == null
                },
                any<Boolean>()
            )
        }
    }

    @Test
    fun `Home reports a watched youtube video as watched, so its menu offers to unmark it`() {
        val viewModel = newViewModel(watchProgressRepository(watchedIds = setOf(VIDEO_ID)))

        viewModel.reconcilePosterStatusObserversPipeline(listOf(youtubeRow()))

        assertEquals(
            true,
            viewModel._uiState.value.movieWatchedStatus[homeItemStatusKey(VIDEO_ID, "youtube")]
        )
    }

    private fun watchProgressRepository(watchedIds: Set<String>) = mockk<WatchProgressRepository>(relaxed = true) {
        every { getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { observeWatchedMovieIds() } returns MutableStateFlow(watchedIds)
    }

    private fun youtubeVideo() = MetaPreview(
        id = VIDEO_ID,
        type = ContentType.UNKNOWN,
        rawType = "youtube",
        name = "Sample video",
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun youtubeRow() = CatalogRow(
        addonId = "youtube.addon",
        addonName = "YouTube Addon",
        addonBaseUrl = "https://youtube.example",
        catalogId = "latest",
        catalogName = "Latest",
        type = ContentType.UNKNOWN,
        rawType = "youtube",
        items = listOf(youtubeVideo())
    )

    private fun newViewModel(watchProgressRepository: WatchProgressRepository): HomeViewModel {
        val profileManager = mockk<com.nuvio.tv.core.profile.ProfileManager>(relaxed = true) {
            every { activeProfileReady } returns MutableStateFlow(false)
            every { activeProfileId } returns MutableStateFlow(1)
        }
        val cwEnrichmentCache =
            mockk<com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache>(relaxed = true) {
                every { cacheCleared } returns MutableStateFlow(0)
            }
        val viewModel = HomeViewModel(
            episodeShuffleStore = mockk(relaxed = true),
            episodeShuffle = com.nuvio.tv.domain.model.EpisodeShuffle(),
            appContext = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            startupSyncService = mockk(relaxed = true),
            catalogRepository = mockk(relaxed = true),
            watchProgressRepository = watchProgressRepository,
            libraryRepository = mockk(relaxed = true),
            metaRepository = mockk(relaxed = true),
            collectionsDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true),
            playerSettingsDataStore = mockk(relaxed = true),
            tmdbSettingsDataStore = mockk(relaxed = true),
            mdbListSettingsDataStore = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true),
            authSessionNoticeDataStore = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            tmdbMetadataService = mockk(relaxed = true),
            mdbListRepository = mockk(relaxed = true),
            imdbEpisodeRatingsRepository = mockk(relaxed = true),
            trailerService = mockk(relaxed = true),
            watchedSeriesStateHolder = mockk(relaxed = true),
            cwEnrichmentCache = cwEnrichmentCache,
            profileManager = profileManager,
            tvRecommendationManager = mockk(relaxed = true),
            serverCatalog = mockk(relaxed = true)
        )
        viewModel.startupGracePeriodActive = false
        viewModel.currentTmdbSettings = TmdbSettings(enabled = false)
        created += viewModel
        return viewModel
    }

    private companion object {
        const val VIDEO_ID = "yt_id:abc123"
    }
}
