package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.data.remote.api.TrailerResponse
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * When YouTube starts refusing requests, every extra request keeps the block going. These
 * tests pin down that a lookup backs off instead of working through every candidate, and
 * that a miss caused by the block isn't remembered as "this title has no trailer".
 */
class TrailerServiceRateLimitTest {

    private val trailerApi = mockk<TrailerApi>()
    private val tmdbApi = mockk<TmdbApi>()
    private val extractor = mockk<InAppYouTubeExtractor>()
    private val service = TrailerService(
        trailerApi = trailerApi,
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = extractor,
        tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "en", useTrailers = true))
        },
        tmdbService = mockk<TmdbService> { every { apiKey() } returns "tmdb-key" }
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        coEvery { trailerApi.getTrailer(any(), any(), any()) } returns Response.success(TrailerResponse(url = null))
        coEvery { tmdbApi.getMovieVideos(any(), any(), any()) } returns Response.success(
            TmdbVideosResponse(id = 123, results = (0 until 8).map { trailer(it) })
        )
        every { extractor.isRateLimited() } returns false
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `stops trying candidates once youtube is rate limiting`() = runTest {
        var rateLimited = false
        every { extractor.isRateLimited() } answers { rateLimited }
        coEvery { extractor.extractPlaybackSource(any(), any()) } answers {
            rateLimited = true
            null
        }

        assertNull(lookup())

        coVerify(exactly = 1) { extractor.extractPlaybackSource(any(), any()) }
    }

    @Test
    fun `tries only the best few candidates when they all fail`() = runTest {
        coEvery { extractor.extractPlaybackSource(any(), any()) } returns null

        assertNull(lookup())

        coVerify(exactly = 3) { extractor.extractPlaybackSource(any(), any()) }
    }

    @Test
    fun `a miss while rate limited is not cached as no trailer`() = runTest {
        every { extractor.isRateLimited() } returns true
        coEvery { extractor.extractPlaybackSource(any(), any()) } returns null
        assertNull(lookup())

        // The pause is over and YouTube answers again.
        every { extractor.isRateLimited() } returns false
        coEvery { extractor.extractPlaybackSource(any(), any()) } returns
            TrailerPlaybackSource(videoUrl = "https://cdn.example/trailer.mp4")

        assertEquals("https://cdn.example/trailer.mp4", lookup()?.videoUrl)
    }

    @Test
    fun `a miss without rate limiting is still cached`() = runTest {
        coEvery { extractor.extractPlaybackSource(any(), any()) } returns null
        assertNull(lookup())
        coEvery { extractor.extractPlaybackSource(any(), any()) } returns
            TrailerPlaybackSource(videoUrl = "https://cdn.example/trailer.mp4")

        assertNull(lookup())
        coVerify(exactly = 3) { extractor.extractPlaybackSource(any(), any()) }
    }

    @Test
    fun `concurrent requests for the same video extract it once`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { extractor.extractPlaybackSource(any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            TrailerPlaybackSource(videoUrl = "https://cdn.example/trailer.mp4")
        }

        val first = async { service.getTrailerPlaybackSourceFromYouTubeUrl(VIDEO_URL) }
        started.await()
        val second = async { service.getTrailerPlaybackSourceFromYouTubeUrl(VIDEO_URL) }
        waitForSecondCaller()
        release.complete(Unit)

        assertEquals("https://cdn.example/trailer.mp4", first.await()?.videoUrl)
        assertEquals("https://cdn.example/trailer.mp4", second.await()?.videoUrl)
        coVerify(exactly = 1) { extractor.extractPlaybackSource(any(), any()) }
    }

    @Test
    fun `a waiting caller takes over when the extracting caller is cancelled`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { extractor.extractPlaybackSource(any(), any()) } coAnswers {
            calls++
            if (calls == 1) {
                firstStarted.complete(Unit)
                CompletableDeferred<Unit>().await() // hangs until cancelled
            }
            TrailerPlaybackSource(videoUrl = "https://cdn.example/trailer.mp4")
        }

        val first = async { service.getTrailerPlaybackSourceFromYouTubeUrl(VIDEO_URL) }
        firstStarted.await()
        val second = async { service.getTrailerPlaybackSourceFromYouTubeUrl(VIDEO_URL) }
        waitForSecondCaller()
        first.cancel()

        val result = second.await()
        assertNotNull("The waiting caller must not inherit the other caller's cancellation", result)
        assertEquals("https://cdn.example/trailer.mp4", result?.videoUrl)
    }

    /** The service runs on Dispatchers.IO, so this waits in real time, not virtual time. */
    private suspend fun waitForSecondCaller() {
        withContext(Dispatchers.Default) { delay(200) }
    }

    private suspend fun lookup(): TrailerPlaybackSource? = service.getTrailerPlaybackSource(
        title = "Some Movie",
        year = "2024",
        tmdbId = "123",
        type = "movie"
    )

    private fun trailer(index: Int) = TmdbVideoResult(
        iso6391 = "en",
        name = "Trailer $index",
        key = "trailer%04d".format(index).padEnd(11, 'x'),
        site = "YouTube",
        type = "Trailer",
        official = true
    )

    private companion object {
        const val VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    }
}
