package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktLastActivitiesMediaDto
import com.nuvio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktPlaybackItemDto
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class TraktProgressServiceRefreshTest {
    private val traktApi = mockk<TraktApi>()
    private val lastActivitiesCalls = AtomicInteger()
    private val playbackCalls = AtomicInteger()
    private val watchedMoviesCalls = AtomicInteger()
    private val watchedShowsCalls = AtomicInteger()

    @Volatile
    private var episodesWatchedAt = "2026-01-01T00:00:00.000Z"

    @Volatile
    private var lastActivitiesFails = false

    @Volatile
    private var playbackHangs = false

    @Test
    fun `watch action refetches only the changed watched list and the next poll skips it`() = runBlocking {
        val service = loadedService()

        episodesWatchedAt = "2026-01-02T00:00:00.000Z"
        service.refreshNow()
        waitUntil { playbackCalls.get() >= 2 }
        delay(SETTLE_MS)

        assertEquals(1, watchedShowsCalls.get())
        assertEquals(0, watchedMoviesCalls.get())

        resetCounters()
        service.refreshRemoteSnapshot()

        assertEquals(1, lastActivitiesCalls.get())
        assertEquals(0, playbackCalls.get())
        assertEquals(0, watchedShowsCalls.get())
    }

    @Test
    fun `playback is checked once more after the force window, then polls skip`() = runBlocking {
        val service = loadedService()
        service.forceRefreshWindowMs = 1_000L

        episodesWatchedAt = "2026-01-02T00:00:00.000Z"
        service.refreshNow()
        waitUntil { playbackCalls.get() >= 2 }
        delay(service.forceRefreshWindowMs + SETTLE_MS)

        resetCounters()
        service.refreshRemoteSnapshot()
        assertEquals(2, playbackCalls.get())
        assertEquals(0, watchedShowsCalls.get())
        assertEquals(0, watchedMoviesCalls.get())

        resetCounters()
        service.refreshRemoteSnapshot()
        assertEquals(0, playbackCalls.get())
    }

    @Test
    fun `forced refresh refetches both watched lists when last_activities fails`() = runBlocking {
        val service = loadedService()

        lastActivitiesFails = true
        service.refreshNow()
        waitUntil { playbackCalls.get() >= 2 }
        delay(SETTLE_MS)

        assertEquals(1, watchedShowsCalls.get())
        assertEquals(1, watchedMoviesCalls.get())
    }

    @Test
    fun `change seen by a cancelled refresh is fetched by the next poll`() = runBlocking {
        val service = loadedService()

        episodesWatchedAt = "2026-01-02T00:00:00.000Z"
        playbackHangs = true
        val cancelled = async { service.refreshRemoteSnapshot() }
        waitUntil { playbackCalls.get() >= 1 }
        cancelled.cancel()
        playbackHangs = false

        resetCounters()
        service.refreshRemoteSnapshot()

        assertEquals(2, playbackCalls.get())
    }

    private suspend fun loadedService(): TraktProgressService {
        val service = createService()
        withTimeout(TIMEOUT_MS) {
            service.observeAllProgress().first { items -> items.any { it.contentId == MOVIE_ID } }
        }
        delay(SETTLE_MS)
        resetCounters()
        return service
    }

    private fun resetCounters() {
        listOf(lastActivitiesCalls, playbackCalls, watchedMoviesCalls, watchedShowsCalls).forEach { it.set(0) }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(20)
        }
    }

    private fun createService(): TraktProgressService {
        coEvery { traktApi.getLastActivities(any()) } answers {
            lastActivitiesCalls.incrementAndGet()
            if (lastActivitiesFails) {
                Response.error(503, "".toResponseBody())
            } else {
                Response.success(
                    TraktLastActivitiesResponseDto(
                        movies = TraktLastActivitiesMediaDto(watchedAt = "2026-01-01T00:00:00.000Z"),
                        episodes = TraktLastActivitiesMediaDto(watchedAt = episodesWatchedAt)
                    )
                )
            }
        }
        coEvery { traktApi.getPlayback(any(), any(), any(), any()) } coAnswers {
            playbackCalls.incrementAndGet()
            if (playbackHangs) awaitCancellation()
            if (secondArg<String>() == "movies") {
                Response.success(
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
            } else {
                Response.success(emptyList())
            }
        }
        coEvery { traktApi.getEpisodeHistory(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { traktApi.getHiddenItems(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        coEvery { traktApi.getWatched(any(), any(), any(), any(), any()) } answers {
            watchedMoviesCalls.incrementAndGet()
            Response.success(emptyList())
        }
        coEvery { traktApi.getWatchedShows(any(), any(), any(), any()) } answers {
            watchedShowsCalls.incrementAndGet()
            Response.success(emptyList())
        }

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
        const val TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 500L
    }
}
