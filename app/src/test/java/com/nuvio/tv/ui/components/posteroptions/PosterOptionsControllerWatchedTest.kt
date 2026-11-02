package com.nuvio.tv.ui.components.posteroptions

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The menu's single-item watched toggle, for add-on types other than movie (here "youtube"). */
class PosterOptionsControllerWatchedTest {

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
    fun `marking a youtube video watched stores it as one item of its own type`() = runTest {
        val repository = watchProgressRepository(isWatched = false)
        val controller = controller(repository)
        controller.bind(backgroundScope)
        controller.show(youtubeVideo(), addonBaseUrl = null)
        runCurrent()

        controller.toggleMovieWatched()
        runCurrent()

        verify(exactly = 1) { repository.applyOptimisticWatchedMovie(setOf(VIDEO_ID), add = true) }
        coVerify(exactly = 1) {
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
    fun `unmarking a watched youtube video removes it from history`() = runTest {
        val repository = watchProgressRepository(isWatched = true)
        val controller = controller(repository)
        controller.bind(backgroundScope)
        controller.show(youtubeVideo(), addonBaseUrl = null)
        runCurrent()

        controller.toggleMovieWatched()
        runCurrent()

        coVerify(exactly = 1) { repository.removeFromHistory(VIDEO_ID, null, null, null) }
        coVerify(exactly = 0) { repository.markAsCompleted(any<WatchProgress>(), any<Boolean>()) }
    }

    @Test
    fun `the single-item toggle leaves shows to the series toggle`() = runTest {
        val repository = watchProgressRepository(isWatched = false)
        val controller = controller(repository)
        controller.bind(backgroundScope)
        controller.show(youtubeVideo().copy(id = "tt0944947", type = ContentType.SERIES, rawType = "series"), null)
        runCurrent()

        controller.toggleMovieWatched()
        runCurrent()

        coVerify(exactly = 0) { repository.markAsCompleted(any<WatchProgress>(), any<Boolean>()) }
        coVerify(exactly = 0) { repository.removeFromHistory(any(), any(), any(), any()) }
    }

    private fun watchProgressRepository(isWatched: Boolean) = mockk<WatchProgressRepository>(relaxed = true) {
        every { isWatched(any(), any(), any(), any()) } returns flowOf(isWatched)
    }

    private fun controller(watchProgressRepository: WatchProgressRepository): PosterOptionsController {
        val libraryRepository = mockk<LibraryRepository>(relaxed = true) {
            every { sourceMode } returns flowOf(LibrarySourceMode.LOCAL)
            every { listTabs } returns flowOf(emptyList())
            every { membershipListTabs } returns flowOf(emptyList())
            every { isInLibrary(any(), any()) } returns flowOf(false)
        }
        val watchedSeriesStateHolder = mockk<WatchedSeriesStateHolder>(relaxed = true) {
            every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        }
        return PosterOptionsController(
            appContext = mockk<Context>(relaxed = true),
            libraryRepository = libraryRepository,
            watchProgressRepository = watchProgressRepository,
            metaRepository = mockk<MetaRepository>(relaxed = true),
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            tmdbService = mockk<TmdbService>(relaxed = true)
        )
    }

    /** A catalog item as the YouStream add-on serves it: type "youtube", id "yt_id:<video id>". */
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

    private companion object {
        const val VIDEO_ID = "yt_id:abc123"
    }
}
