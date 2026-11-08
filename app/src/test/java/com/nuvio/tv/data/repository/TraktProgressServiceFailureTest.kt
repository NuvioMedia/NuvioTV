package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktHiddenItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktLastActivitiesMediaDto
import com.nuvio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktPlaybackItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktProgressServiceFailureTest {
    private val traktApi = mockk<TraktApi>()

    @Test
    fun `failed playback fetch keeps the previous continue watching snapshot`() = runBlocking {
        val service = createService()
        withTimeout(TIMEOUT_MS) {
            service.observeAllProgress().first { items -> items.any { it.contentId == MOVIE_ID } }
        }

        val failed = CompletableDeferred<Unit>()
        coEvery { traktApi.getPlayback(any(), "movies", any(), any()) } answers {
            failed.complete(Unit)
            Response.error(503, "".toResponseBody())
        }
        service.refreshNow()
        withTimeout(TIMEOUT_MS) { failed.await() }
        delay(SETTLE_MS)

        val current = service.observeAllProgress().first()
        assertTrue(current.any { it.contentId == MOVIE_ID })
    }

    @Test
    fun `failed dropped-shows fetch keeps shows hidden from progress`() = runBlocking {
        val service = createService()
        withTimeout(TIMEOUT_MS) {
            while (!service.isShowHiddenFromProgress(DROPPED_SHOW_ID)) delay(20)
        }

        val failed = CompletableDeferred<Unit>()
        coEvery { traktApi.getHiddenItems(any(), any(), any(), any(), any()) } answers {
            failed.complete(Unit)
            Response.error(503, "".toResponseBody())
        }
        service.refreshNow()
        withTimeout(TIMEOUT_MS) { failed.await() }
        delay(SETTLE_MS)

        assertTrue(service.isShowHiddenFromProgress(DROPPED_SHOW_ID))
    }

    private fun createService(): TraktProgressService {
        coEvery { traktApi.getLastActivities(any()) } returns Response.success(
            TraktLastActivitiesResponseDto(
                movies = TraktLastActivitiesMediaDto(watchedAt = "2026-01-01T00:00:00.000Z"),
                episodes = TraktLastActivitiesMediaDto(watchedAt = "2026-01-01T00:00:00.000Z")
            )
        )
        coEvery { traktApi.getPlayback(any(), "movies", any(), any()) } returns Response.success(
            listOf(
                TraktPlaybackItemDto(
                    id = 1L,
                    type = "movie",
                    progress = 40f,
                    pausedAt = "2026-01-01T00:00:00.000Z",
                    movie = TraktMovieDto(title = "Movie", ids = TraktIdsDto(trakt = 1, imdb = MOVIE_ID))
                )
            )
        )
        coEvery { traktApi.getPlayback(any(), "episodes", any(), any()) } returns Response.success(emptyList())
        coEvery { traktApi.getEpisodeHistory(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { traktApi.getHiddenItems(any(), any(), any(), any(), any()) } returns Response.success(
            listOf(TraktHiddenItemDto(show = TraktShowDto(ids = TraktIdsDto(trakt = 2, imdb = DROPPED_SHOW_ID))))
        )
        coEvery { traktApi.getWatched(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { traktApi.getWatchedShows(any(), any(), any(), any()) } returns Response.success(emptyList())

        val authService = mockk<TraktAuthService>()
        every { authService.isCircuitClosed() } returns true
        coEvery { authService.executeAuthorizedRequest<Any?>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Any?>>().invoke("Bearer test")
        }

        val metaRepository = mockk<MetaRepository>()
        every { metaRepository.getMetaFromAllAddons(any(), any(), any()) } returns emptyFlow()
        val settings = mockk<TraktSettingsDataStore>()
        every { settings.continueWatchingDaysCap } returns
            flowOf(TraktSettingsDataStore.DEFAULT_CONTINUE_WATCHING_DAYS_CAP)
        val layout = mockk<LayoutPreferenceDataStore>()
        every { layout.nextUpFromFurthestEpisode } returns MutableStateFlow(false)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return TraktProgressService(
            appContext = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthService = authService,
            metaRepository = metaRepository,
            tmdbService = mockk<TmdbService>(relaxed = true),
            traktSettingsDataStore = settings,
            layoutPreferenceDataStore = layout,
            traktEpisodeMappingService = mockk(relaxed = true),
            profileManager = profileManager,
            watchedSeriesStateHolder = watchedSeries
        )
    }

    private companion object {
        const val MOVIE_ID = "tt0000001"
        const val DROPPED_SHOW_ID = "tt0000002"
        const val TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 500L
    }
}
