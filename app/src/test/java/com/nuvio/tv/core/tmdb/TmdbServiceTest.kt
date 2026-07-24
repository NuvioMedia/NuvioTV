package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbFindResponse
import com.nuvio.tv.data.remote.api.TmdbFindResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class TmdbServiceTest {

    @Test
    fun `anime id uses addon imdb id for tmdb lookup`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.findByExternalId(
                externalId = "tt28254942",
                apiKey = any(),
                externalSource = "imdb_id"
            )
        } returns Response.success(
            TmdbFindResponse(tvResults = listOf(TmdbFindResult(id = 228234)))
        )

        val service = TmdbService(api)

        assertEquals(
            "228234",
            service.ensureTmdbId(
                videoId = "mal:49894",
                mediaType = "series",
                fallbackImdbId = "tt28254942"
            )
        )
        coVerify(exactly = 1) {
            api.findByExternalId("tt28254942", any(), "imdb_id")
        }
    }

    @Test
    fun `numeric tmdb id takes precedence over fallback imdb id`() = runTest {
        val api = mockk<TmdbApi>()
        val service = TmdbService(api)

        assertEquals(
            "228234",
            service.ensureTmdbId(
                videoId = "tmdb:228234",
                mediaType = "series",
                fallbackImdbId = "tt28254942"
            )
        )
        coVerify(exactly = 0) {
            api.findByExternalId(any(), any(), any())
        }
    }

    @Test
    fun `primary imdb id remains authoritative over fallback imdb id`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.findByExternalId(
                externalId = "tt0133093",
                apiKey = any(),
                externalSource = "imdb_id"
            )
        } returns Response.success(
            TmdbFindResponse(movieResults = listOf(TmdbFindResult(id = 603)))
        )

        val service = TmdbService(api)

        assertEquals(
            "603",
            service.ensureTmdbId(
                videoId = "tt0133093",
                mediaType = "movie",
                fallbackImdbId = "tt28254942"
            )
        )
        coVerify(exactly = 1) {
            api.findByExternalId("tt0133093", any(), "imdb_id")
        }
        coVerify(exactly = 0) {
            api.findByExternalId("tt28254942", any(), "imdb_id")
        }
    }

    @Test
    fun `unsupported id without imdb fallback remains unresolved`() = runTest {
        val api = mockk<TmdbApi>()
        val service = TmdbService(api)

        assertNull(service.ensureTmdbId("mal:51097", "series"))
        coVerify(exactly = 0) {
            api.findByExternalId(any(), any(), any())
        }
    }
}
