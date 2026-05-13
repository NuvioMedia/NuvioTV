package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for issue #1647: the "Disable Trailers in TMDB Enrichment"
 * toggle in TMDB settings must short-circuit trailer lookups, not just skip
 * merging TMDB trailers into the metadata model.
 *
 * Before the fix, both [TrailerService.getTrailerPlaybackSource] and
 * [TrailerService.getExternalTrailerUrl] reached out to TMDB unconditionally
 * (the source even acknowledged it with a comment "independent of TMDB
 * enrichment settings"), so disabling the toggle had no observable effect.
 */
class TrailerServiceUseTrailersGateTest {

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
    fun `getTrailerPlaybackSource returns null and does not call TMDB when useTrailers is false`() = runTest {
        val service = newServiceWithUseTrailers(enabled = false)
        val (trailerApi, tmdbApi, extractor) = service.collaborators

        val result = service.target.getTrailerPlaybackSource(
            title = "Some Movie",
            year = "2024",
            tmdbId = "12345",
            type = "movie"
        )

        assertNull("Trailer lookup must return null when useTrailers is disabled", result)
        // No TMDB calls of any kind should fire when the user has opted out.
        coVerify(exactly = 0) { tmdbApi.getMovieVideos(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getTvVideos(any(), any(), any()) }
        coVerify(exactly = 0) { extractor.extractPlaybackSource(any()) }
        coVerify(exactly = 0) { trailerApi.getTrailer(any(), any(), any()) }
    }

    @Test
    fun `getExternalTrailerUrl returns null when useTrailers is false`() = runTest {
        val service = newServiceWithUseTrailers(enabled = false)
        val (_, tmdbApi, _) = service.collaborators

        val result = service.target.getExternalTrailerUrl(tmdbId = "12345", type = "movie")

        assertNull("External trailer URL must be null when useTrailers is disabled", result)
        coVerify(exactly = 0) { tmdbApi.getMovieVideos(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getTvVideos(any(), any(), any()) }
    }

    private data class HarnessedService(
        val target: TrailerService,
        val collaborators: Collaborators
    )

    private data class Collaborators(
        val trailerApi: TrailerApi,
        val tmdbApi: TmdbApi,
        val extractor: InAppYouTubeExtractor
    )

    private fun newServiceWithUseTrailers(enabled: Boolean): HarnessedService {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val extractor = mockk<InAppYouTubeExtractor>(relaxed = true)
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns flowOf(TmdbSettings(language = "en", useTrailers = enabled))
        }
        val tmdbService = mockk<TmdbService> {
            every { apiKey() } returns "tmdb-key"
        }
        val service = TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = extractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            tmdbService = tmdbService
        )
        return HarnessedService(
            target = service,
            collaborators = Collaborators(trailerApi, tmdbApi, extractor)
        )
    }
}
