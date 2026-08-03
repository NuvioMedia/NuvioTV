package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.repository.AnimeIdResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbServiceTest {

    @Test
    fun `arm mapping is limited to enrichment resolution`() = runTest {
        val animeIdResolver = mockk<AnimeIdResolver>()
        every { animeIdResolver.supports("mal:62322") } returns true
        coEvery { animeIdResolver.resolveTmdbId("mal:62322") } returns 298994
        val service = TmdbService(
            tmdbApi = mockk<TmdbApi>(relaxed = true),
            animeIdResolver = animeIdResolver
        )

        assertNull(service.ensureTmdbId("mal:62322", "series"))
        coVerify(exactly = 0) { animeIdResolver.resolveTmdbId(any()) }

        assertEquals("298994", service.ensureTmdbIdForEnrichment("mal:62322", "series"))
        coVerify(exactly = 1) { animeIdResolver.resolveTmdbId("mal:62322") }
    }
}
