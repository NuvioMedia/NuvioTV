package com.nuvio.tv.data.trailer

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class TrailerServiceCandidateRankingTest {

    @Test
    fun `higher resolution outranks official status within the same video type`() {
        val officialLowResolution = youtubeVideo(
            key = LOW_RESOLUTION_KEY,
            size = 480,
            official = true,
            publishedAt = "2026-08-01T00:00:00Z"
        )
        val unofficialHighResolution = youtubeVideo(
            key = HIGH_RESOLUTION_KEY,
            size = 2160,
            official = false,
            publishedAt = "2025-08-01T00:00:00Z"
        )

        val ranked = rankTmdbVideoCandidates(
            listOf(officialLowResolution, unofficialHighResolution)
        )

        assertEquals(
            listOf(HIGH_RESOLUTION_KEY, LOW_RESOLUTION_KEY),
            ranked.map { it.key }
        )
    }

    @Test
    fun `video type remains higher priority than resolution`() {
        val lowResolutionTrailer = youtubeVideo(
            key = LOW_RESOLUTION_KEY,
            type = "Trailer",
            size = 480,
            official = false
        )
        val highResolutionTeaser = youtubeVideo(
            key = HIGH_RESOLUTION_KEY,
            type = "Teaser",
            size = 2160,
            official = true
        )

        val ranked = rankTmdbVideoCandidates(
            listOf(highResolutionTeaser, lowResolutionTrailer)
        )

        assertEquals(
            listOf(LOW_RESOLUTION_KEY, HIGH_RESOLUTION_KEY),
            ranked.map { it.key }
        )
    }

    @Test
    fun `external trailer selection uses the highest resolution trailer candidate`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>(relaxed = true)
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "en", useTrailers = true))
        }
        val tmdbService = mockk<TmdbService> {
            every { apiKey() } returns "tmdb-key"
        }
        coEvery {
            tmdbApi.getMovieVideos(
                movieId = 123,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        } returns Response.success(
            TmdbVideosResponse(
                id = 123,
                results = listOf(
                    youtubeVideo(
                        key = LOW_RESOLUTION_KEY,
                        size = 480,
                        official = true,
                        publishedAt = "2026-08-01T00:00:00Z"
                    ),
                    youtubeVideo(
                        key = HIGH_RESOLUTION_KEY,
                        size = 2160,
                        official = false,
                        publishedAt = "2025-08-01T00:00:00Z"
                    )
                )
            )
        )
        val service = TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = extractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            tmdbService = tmdbService
        )

        val result = service.getExternalTrailerUrl(tmdbId = "123", type = "movie")

        assertEquals("https://www.youtube.com/watch?v=$HIGH_RESOLUTION_KEY", result)
        coVerify(exactly = 1) {
            tmdbApi.getMovieVideos(
                movieId = 123,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        }
    }

    private fun youtubeVideo(
        key: String,
        type: String = "Trailer",
        size: Int,
        official: Boolean,
        publishedAt: String = "2026-01-01T00:00:00Z"
    ): TmdbVideoResult {
        return TmdbVideoResult(
            key = key,
            site = "YouTube",
            size = size,
            type = type,
            official = official,
            publishedAt = publishedAt
        )
    }

    private companion object {
        const val LOW_RESOLUTION_KEY = "lowres00001"
        const val HIGH_RESOLUTION_KEY = "highres0001"
    }
}
