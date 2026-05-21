package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.build.TrailerPlaybackMode
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class TrailerServiceWebViewPlaybackModeTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        // Mock AppFeaturePolicy object to return WEB_VIEW mode
        mockkObject(AppFeaturePolicy)
        every { AppFeaturePolicy.trailerPlaybackMode } returns TrailerPlaybackMode.WEB_VIEW
    }

    @After
    fun tearDown() {
        unmockkObject(AppFeaturePolicy)
        unmockkStatic(Log::class)
    }

    @Test
    fun `getTrailerPlaybackSourceFromYouTubeUrl returns raw youtube url directly in WEB_VIEW mode`() = runTest {
        val trailerApi = mockk<TrailerApi>()
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(language = "en"))
        every { tmdbService.apiKey() } returns "tmdb-key"
        
        val service = TrailerService(trailerApi, tmdbApi, extractor, tmdbSettingsDataStore, tmdbService)

        val youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val source = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)

        assertNotNull("Playback source should not be null", source)
        assertEquals("Playback source videoUrl should be raw YouTube URL", youtubeUrl, source?.videoUrl)

        // Verify that extractor and backend trailer APIs were NEVER called in WEB_VIEW mode
        coVerify(exactly = 0) { extractor.extractPlaybackSource(any()) }
        coVerify(exactly = 0) { trailerApi.getTrailer(any(), any(), any()) }
    }
}
