package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The detail screen cancels the in-flight trailer job every time it starts a new one
 * (MetaDetailsViewModel.fetchTrailerUrl), so a cancelled TMDB lookup is routine, not
 * exceptional. It must not be reported as "this title has no trailer".
 */
class TrailerServiceCancellationTest {

    private val tmdbId = 123

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `a cancelled TMDB lookup propagates instead of reporting no trailer`() = runTest {
        val harness = newService()
        coEvery { harness.tmdbApi.getMovieVideos(any(), any(), any()) } throws
            CancellationException("job cancelled")

        val thrown = runCatching { harness.lookup() }.exceptionOrNull()

        assertTrue(
            "A cancelled lookup must surface as CancellationException, not a null trailer, " +
                "but was: $thrown",
            thrown is CancellationException
        )
    }

    @Test
    fun `a cancelled lookup does not hide the trailer from the next attempt`() = runTest {
        val harness = newService()

        coEvery { harness.tmdbApi.getMovieVideos(any(), any(), any()) } throws
            CancellationException("job cancelled")
        runCatching { harness.lookup() }

        // TMDB answers normally now, exactly as it would on the user's next visit.
        coEvery { harness.tmdbApi.getMovieVideos(any(), any(), any()) } returns Response.success(
            TmdbVideosResponse(
                id = tmdbId,
                results = listOf(
                    TmdbVideoResult(
                        iso6391 = "en",
                        name = "Official Trailer",
                        key = "abcdefghijk",
                        site = "YouTube",
                        type = "Trailer",
                        official = true
                    )
                )
            )
        )
        coEvery { harness.extractor.extractPlaybackSource(any()) } returns
            TrailerPlaybackSource(videoUrl = "https://example.test/trailer.mp4")

        val result = harness.lookup()

        assertNotNull(
            "The earlier cancellation must not be cached as 'no trailer' for this title",
            result
        )
        assertEquals("https://example.test/trailer.mp4", result?.videoUrl)
    }

    private class Harness(
        val target: TrailerService,
        val tmdbApi: TmdbApi,
        val extractor: InAppYouTubeExtractor
    ) {
        suspend fun lookup(): TrailerPlaybackSource? = target.getTrailerPlaybackSource(
            title = "Some Movie",
            year = "2024",
            tmdbId = "123",
            type = "movie"
        )
    }

    private fun newService(): Harness {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val extractor = mockk<InAppYouTubeExtractor>(relaxed = true)
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(
                TmdbSettings(language = "en", useTrailers = true)
            )
        }
        val tmdbService = mockk<TmdbService> {
            every { apiKey() } returns "tmdb-key"
        }
        return Harness(
            target = TrailerService(
                trailerApi = trailerApi,
                tmdbApi = tmdbApi,
                inAppYouTubeExtractor = extractor,
                tmdbSettingsDataStore = tmdbSettingsDataStore,
                tmdbService = tmdbService
            ),
            tmdbApi = tmdbApi,
            extractor = extractor
        )
    }
}
