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
    fun `default english preference outranks higher resolution non-matching-language candidate`() {
        val higherResolutionForeign = youtubeVideo(
            key = FOREIGN_KEY,
            size = 2160,
            official = true,
            iso6391 = "hi"
        )
        val lowerResolutionEnglish = youtubeVideo(
            key = HIGH_RESOLUTION_KEY,
            size = 1080,
            official = true,
            iso6391 = "en"
        )

        val ranked = rankTmdbVideoCandidates(
            listOf(higherResolutionForeign, lowerResolutionEnglish)
        )

        assertEquals(
            listOf(HIGH_RESOLUTION_KEY, FOREIGN_KEY),
            ranked.map { it.key }
        )
    }

    @Test
    fun `ranking prefers the requested language over a higher resolution english upload`() {
        val matchingLanguage = youtubeVideo(
            key = FOREIGN_KEY,
            size = 1080,
            official = true,
            iso6391 = "hi"
        )
        val higherResolutionEnglish = youtubeVideo(
            key = HIGH_RESOLUTION_KEY,
            size = 2160,
            official = true,
            iso6391 = "en"
        )

        val ranked = rankTmdbVideoCandidates(
            listOf(higherResolutionEnglish, matchingLanguage),
            preferredLanguageCode = "hi"
        )

        assertEquals(
            listOf(FOREIGN_KEY, HIGH_RESOLUTION_KEY),
            ranked.map { it.key }
        )
    }

    @Test
    fun `trailer selection follows the tmdb enrichment language setting`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>(relaxed = true)
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "hi", useTrailers = true))
        }
        val tmdbService = mockk<TmdbService> {
            every { apiKey() } returns "tmdb-key"
        }
        coEvery {
            tmdbApi.getMovieVideos(movieId = 123, apiKey = "tmdb-key", language = "hi")
        } returns Response.success(
            TmdbVideosResponse(
                id = 123,
                results = listOf(
                    youtubeVideo(key = FOREIGN_KEY, size = 1080, official = true, iso6391 = "hi")
                )
            )
        )
        coEvery {
            tmdbApi.getMovieVideos(movieId = 123, apiKey = "tmdb-key", language = "en-US")
        } returns Response.success(
            TmdbVideosResponse(
                id = 123,
                results = listOf(
                    youtubeVideo(key = HIGH_RESOLUTION_KEY, size = 2160, official = true, iso6391 = "en")
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

        // The Hindi upload wins even though the English one is higher resolution,
        // because the user's TMDB enrichment language is set to Hindi.
        assertEquals("https://www.youtube.com/watch?v=$FOREIGN_KEY", result)
        coVerify(exactly = 1) {
            tmdbApi.getMovieVideos(movieId = 123, apiKey = "tmdb-key", language = "hi")
        }
    }

    @Test
    fun `falls back to english when the tmdb language setting has no trailer available`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>(relaxed = true)
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "hi", useTrailers = true))
        }
        val tmdbService = mockk<TmdbService> {
            every { apiKey() } returns "tmdb-key"
        }
        coEvery {
            tmdbApi.getMovieVideos(movieId = 123, apiKey = "tmdb-key", language = "hi")
        } returns Response.success(TmdbVideosResponse(id = 123, results = emptyList()))
        coEvery {
            tmdbApi.getMovieVideos(movieId = 123, apiKey = "tmdb-key", language = "en-US")
        } returns Response.success(
            TmdbVideosResponse(
                id = 123,
                results = listOf(
                    youtubeVideo(key = HIGH_RESOLUTION_KEY, size = 1080, official = true, iso6391 = "en")
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
    }

    private fun youtubeVideo(
        key: String,
        type: String = "Trailer",
        size: Int,
        official: Boolean,
        publishedAt: String = "2026-01-01T00:00:00Z",
        iso6391: String? = null
    ): TmdbVideoResult {
        return TmdbVideoResult(
            key = key,
            site = "YouTube",
            size = size,
            type = type,
            official = official,
            publishedAt = publishedAt,
            iso6391 = iso6391
        )
    }

    private companion object {
        const val LOW_RESOLUTION_KEY = "lowres00001"
        const val HIGH_RESOLUTION_KEY = "highres0001"
        const val FOREIGN_KEY = "foreign0001"
    }
}
