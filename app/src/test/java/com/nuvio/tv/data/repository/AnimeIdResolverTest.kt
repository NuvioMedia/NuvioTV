package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.AnimeIdCacheStore
import com.nuvio.tv.data.local.CachedAnimeTmdbMapping
import com.nuvio.tv.data.remote.api.ArmApi
import com.nuvio.tv.data.remote.api.ArmEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class AnimeIdResolverTest {

    @Test
    fun `mal id resolves directly to arm themoviedb mapping`() = runTest {
        val api = mockk<ArmApi>()
        val cache = mockk<AnimeIdCacheStore>(relaxed = true)
        every { cache.get(any(), any(), any()) } returns null
        coEvery {
            api.resolveId(source = "myanimelist", id = "62322", include = "themoviedb")
        } returns Response.success(ArmEntry(themoviedb = 298994))
        val resolver = AnimeIdResolver(api, cache)

        val tmdbId = resolver.resolveTmdbId("mal:62322:1:4")

        assertEquals(298994, tmdbId)
        coVerify(exactly = 1) {
            api.resolveId(source = "myanimelist", id = "62322", include = "themoviedb")
        }
    }

    @Test
    fun `cached arm mapping avoids a network request`() = runTest {
        val api = mockk<ArmApi>()
        val cache = mockk<AnimeIdCacheStore>(relaxed = true)
        every { cache.get("kitsu", "3505", any()) } returns CachedAnimeTmdbMapping(
            tmdbId = 24835,
            expiresAtMs = Long.MAX_VALUE,
            missCount = 0
        )
        val resolver = AnimeIdResolver(api, cache)

        assertEquals(24835, resolver.resolveTmdbId("kitsu:3505"))
        coVerify(exactly = 0) { api.resolveId(any(), any(), any()) }
    }

    @Test
    fun `prefetch resolves supported anime ids`() = runTest {
        val api = mockk<ArmApi>()
        val cache = mockk<AnimeIdCacheStore>(relaxed = true)
        every { cache.get(any(), any(), any()) } returns null
        coEvery {
            api.resolveId("myanimelist", "62322", "themoviedb")
        } returns Response.success(ArmEntry(themoviedb = 298994))
        coEvery {
            api.resolveId("anilist", "4181", "themoviedb")
        } returns Response.success(ArmEntry(themoviedb = 24835))
        val resolver = AnimeIdResolver(api, cache)

        resolver.prefetchTmdbIds(listOf("mal:62322", "anilist:4181"))

        coVerify(exactly = 1) {
            api.resolveId("myanimelist", "62322", "themoviedb")
            api.resolveId("anilist", "4181", "themoviedb")
        }
    }

    @Test
    fun `prefetch shares an in flight request with direct resolution`() = runTest {
        val api = mockk<ArmApi>()
        val cache = mockk<AnimeIdCacheStore>(relaxed = true)
        val cacheChecks = AtomicInteger()
        val secondCacheCheck = CompletableDeferred<Unit>()
        every { cache.get(any(), any(), any()) } answers {
            if (cacheChecks.incrementAndGet() >= 2) secondCacheCheck.complete(Unit)
            null
        }
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        coEvery {
            api.resolveId("myanimelist", "62322", "themoviedb")
        } coAnswers {
            requestStarted.complete(Unit)
            releaseRequest.await()
            Response.success(ArmEntry(themoviedb = 298994))
        }
        val resolver = AnimeIdResolver(api, cache)

        val directLookup = async { resolver.resolveTmdbId("mal:62322") }
        requestStarted.await()
        val prefetch = async { resolver.prefetchTmdbIds(listOf("mal:62322")) }
        secondCacheCheck.await()
        releaseRequest.complete(Unit)

        assertEquals(298994, directLookup.await())
        prefetch.await()
        coVerify(exactly = 1) {
            api.resolveId("myanimelist", "62322", "themoviedb")
        }
    }

    @Test
    fun `one cancelled caller does not cancel a shared mapping request`() = runTest {
        val api = mockk<ArmApi>()
        val cache = mockk<AnimeIdCacheStore>(relaxed = true)
        val cacheChecks = AtomicInteger()
        val secondCacheCheck = CompletableDeferred<Unit>()
        every { cache.get(any(), any(), any()) } answers {
            if (cacheChecks.incrementAndGet() >= 2) secondCacheCheck.complete(Unit)
            null
        }
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        coEvery {
            api.resolveId("kitsu", "3505", "themoviedb")
        } coAnswers {
            requestStarted.complete(Unit)
            releaseRequest.await()
            Response.success(ArmEntry(themoviedb = 24835))
        }
        val resolver = AnimeIdResolver(api, cache)

        val firstCaller = async { resolver.resolveTmdbId("kitsu:3505") }
        requestStarted.await()
        firstCaller.cancel()
        val secondCaller = async { resolver.resolveTmdbId("kitsu:3505") }
        secondCacheCheck.await()
        releaseRequest.complete(Unit)

        assertEquals(24835, secondCaller.await())
        coVerify(exactly = 1) {
            api.resolveId("kitsu", "3505", "themoviedb")
        }
    }

    @Test
    fun `imdb ids are not treated as arm anime aliases`() {
        val api = mockk<ArmApi>()
        val resolver = AnimeIdResolver(armApi = api, cacheStore = mockk(relaxed = true))

        assertNull(resolver.parseArmRequest("tt1118804"))
        assertEquals("myanimelist", resolver.parseArmRequest("mal:4181")?.source)
        assertEquals("4181", resolver.parseArmRequest("mal:4181")?.id)
    }

    @Test
    fun `prefetch ignores non anime ids without contacting arm`() = runTest {
        val api = mockk<ArmApi>()
        val resolver = AnimeIdResolver(armApi = api, cacheStore = mockk(relaxed = true))

        resolver.prefetchTmdbIds(listOf("tt1118804", "tmdb:24835", "24835"))

        coVerify(exactly = 0) { api.resolveId(any(), any(), any()) }
    }
}
