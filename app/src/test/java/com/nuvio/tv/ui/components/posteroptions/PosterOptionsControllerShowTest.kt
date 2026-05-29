package com.nuvio.tv.ui.components.posteroptions

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PosterOptionsControllerShowTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `show with item already in library exposes isInLibrary = true on first state emit`() = runTest {
        val controller = newController(
            isInLibrary = true,
            isWatched = false
        )
        controller.bind(this)

        controller.show(samplePreview(), addonBaseUrl = null)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(true, state.isInLibrary)
        assertEquals(false, state.isWatched)
        // Sanity: target was set so the dialog will actually render.
        assertTrue("target should be non-null after show()", state.target != null)
    }

    @Test
    fun `show with item not in library exposes isInLibrary = false`() = runTest {
        val controller = newController(
            isInLibrary = false,
            isWatched = false
        )
        controller.bind(this)

        controller.show(samplePreview(), addonBaseUrl = null)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(false, state.isInLibrary)
        assertEquals(false, state.isWatched)
        assertTrue("target should be non-null after show()", state.target != null)
    }

    @Test
    fun `show with watched movie exposes isWatched = true`() = runTest {
        val controller = newController(
            isInLibrary = false,
            isWatched = true
        )
        controller.bind(this)

        controller.show(samplePreview(), addonBaseUrl = null)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(true, state.isWatched)
    }

    @Test
    fun `successive show calls leave state with the latest item only`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val libraryRepository = mockk<LibraryRepository>(relaxed = true) {
            every { sourceMode } returns flowOf(LibrarySourceMode.LOCAL)
            every { listTabs } returns flowOf(emptyList())
            every { isInLibrary(any(), any()) } returns flowOf(false)
        }
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
            every { isWatched(any(), any(), any(), any()) } returns flowOf(false)
        }
        val tmdbService = mockk<TmdbService>(relaxed = true) {
            coEvery { tmdbToImdb(111, "movie") } coAnswers {
                kotlinx.coroutines.delay(50)
                "tt0000001"
            }
            coEvery { tmdbToImdb(222, "movie") } returns "tt0000002"
        }
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val watchedSeriesStateHolder = mockk<WatchedSeriesStateHolder>(relaxed = true) {
            every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        }
        val controller = PosterOptionsController(
            appContext = context,
            libraryRepository = libraryRepository,
            watchProgressRepository = watchProgressRepository,
            metaRepository = metaRepository,
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            tmdbService = tmdbService
        )
        controller.bind(this)

        controller.show(samplePreview(id = "tmdb:111"), addonBaseUrl = null)
        controller.show(samplePreview(id = "tmdb:222"), addonBaseUrl = null)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals("tt0000002", state.target?.id)
    }

    @Test
    fun `show with TMDB-rooted item resolves library state against canonical IMDB id`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tmdbId = "tmdb:12345"
        val imdbId = "tt0987654"
        val libraryRepository = mockk<LibraryRepository>(relaxed = true) {
            every { sourceMode } returns flowOf(LibrarySourceMode.LOCAL)
            every { listTabs } returns flowOf(emptyList())
            // The item is stored under the canonical IMDB id; a query under the
            // raw TMDB id would miss.
            every { isInLibrary(tmdbId, any()) } returns flowOf(false)
            every { isInLibrary(imdbId, any()) } returns flowOf(true)
        }
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
            every { isWatched(any(), any(), any(), any()) } returns flowOf(false)
        }
        val tmdbService = mockk<TmdbService>(relaxed = true) {
            coEvery { tmdbToImdb(12345, "movie") } returns imdbId
        }
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val watchedSeriesStateHolder = mockk<WatchedSeriesStateHolder>(relaxed = true) {
            every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        }
        val controller = PosterOptionsController(
            appContext = context,
            libraryRepository = libraryRepository,
            watchProgressRepository = watchProgressRepository,
            metaRepository = metaRepository,
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            tmdbService = tmdbService
        )
        controller.bind(this)

        controller.show(samplePreview(id = tmdbId), addonBaseUrl = null)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(true, state.isInLibrary)
        assertEquals(imdbId, state.target?.id)
    }

    private fun newController(isInLibrary: Boolean, isWatched: Boolean): PosterOptionsController {
        val context = mockk<Context>(relaxed = true)
        val libraryRepository = mockk<LibraryRepository>(relaxed = true) {
            every { sourceMode } returns flowOf(LibrarySourceMode.LOCAL)
            every { listTabs } returns flowOf(emptyList())
            every { isInLibrary(any(), any()) } returns flowOf(isInLibrary)
        }
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
            every { isWatched(any(), any(), any(), any()) } returns flowOf(isWatched)
        }
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val watchedSeriesStateHolder = mockk<WatchedSeriesStateHolder>(relaxed = true) {
            every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        }
        return PosterOptionsController(
            appContext = context,
            libraryRepository = libraryRepository,
            watchProgressRepository = watchProgressRepository,
            metaRepository = metaRepository,
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            tmdbService = tmdbService
        )
    }

    private fun samplePreview(id: String = "tt1234567"): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = "Sample Movie",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
