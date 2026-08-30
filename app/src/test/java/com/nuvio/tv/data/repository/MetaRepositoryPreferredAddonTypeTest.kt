package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * The details screen tries [MetaRepositoryImpl.getMeta] first, so it has to handle "tv"
 * the way the multi-addon path does, both in the URL it asks for and in the cache entry
 * it reads and writes. Otherwise opening a series burns a round trip on /meta/tv/ and
 * then walks past a warm entry filed under "series".
 */
class MetaRepositoryPreferredAddonTypeTest {

    private val contentId = "tt0944947"
    private val baseUrl = "https://addon.example"

    @Test
    fun `tv request to a series-only addon goes out as series`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("movie", "series")))

        repository.getMeta(baseUrl, "tv", contentId).last()

        assertEquals("$baseUrl/meta/series/$contentId.json", capturedUrl(api))
    }

    @Test
    fun `tv request goes out as tv when the addon declares tv`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("series", "tv")))

        repository.getMeta(baseUrl, "tv", contentId).last()

        assertEquals("$baseUrl/meta/tv/$contentId.json", capturedUrl(api))
    }

    @Test
    fun `type is used as given when the addon is not installed`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("series"), baseUrl = "https://other.example"))

        repository.getMeta(baseUrl, "tv", contentId).last()

        assertEquals("$baseUrl/meta/tv/$contentId.json", capturedUrl(api))
    }

    @Test
    fun `meta fetched by the multi-addon path is reused by a tv request for the same addon`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("series")))

        repository.getMetaFromAllAddons("tv", contentId).last()
        repository.getMeta(baseUrl, "tv", contentId).last()

        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    @Test
    fun `cached meta stored for a tv request is found under the series spelling`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("series")))

        repository.getMetaFromAllAddons("tv", contentId).last()

        assertNotNull(repository.getCachedMeta("series", contentId))
        assertNotNull(repository.getCachedMeta("tv", contentId))
    }

    @Test
    fun `the empty startup seed is not mistaken for an addon list`() = runTest {
        val api = apiReturningMeta()
        // What getInstalledAddons() looks like during a cold start: a StateFlow
        // seeded with emptyList(), filled in once manifests are loaded.
        val repository = newRepository(api, flowOf(emptyList(), listOf(addon(metaTypes = listOf("series")))))

        repository.getMeta(baseUrl, "tv", contentId).last()

        assertEquals("$baseUrl/meta/series/$contentId.json", capturedUrl(api))
    }

    @Test
    fun `an installed list that stays empty degrades to the type as given`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, flowOf(emptyList()))

        repository.getMeta(baseUrl, "tv", contentId).last()

        assertEquals("$baseUrl/meta/tv/$contentId.json", capturedUrl(api))
    }

    @Test
    fun `a warm cache hit does not wait on the installed addon list`() = runTest {
        val api = apiReturningMeta()
        val collections = AtomicInteger(0)
        // Emits once, enough to prime the cache, then hangs, standing in for a list
        // that never filled in. A getMeta that resolved the addon before checking its
        // cache would sit here for INSTALLED_ADDONS_WAIT_MS.
        val addonsFlow = flow<List<Addon>> {
            if (collections.getAndIncrement() == 0) {
                emit(listOf(addon(metaTypes = listOf("series"))))
            }
            awaitCancellation()
        }
        val repository = newRepository(api, addonsFlow)

        repository.getMetaFromAllAddons("tv", contentId).last()

        val elapsedMs = measureTimeMillis {
            repository.getMeta(baseUrl, "tv", contentId).last()
        }

        // Cached as "series", asked for as "tv". Both spellings get checked.
        coVerify(exactly = 1) { api.getMeta(any()) }
        assertTrue("warm hit took ${elapsedMs}ms, so it waited on the addon list", elapsedMs < 300L)
    }

    private fun addon(
        metaTypes: List<String>,
        baseUrl: String = this.baseUrl,
        idPrefixes: List<String> = listOf("tt")
    ) = Addon(
        id = "test.addon",
        name = "Test Addon",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = listOf("movie", "series"),
        resources = listOf(AddonResource(name = "meta", types = metaTypes, idPrefixes = null)),
        idPrefixes = idPrefixes
    )

    private fun apiReturningMeta(): AddonApi = mockk<AddonApi>().also { api ->
        coEvery { api.getMeta(any()) } returns Response.success(
            MetaResponseDto(meta = MetaDto(id = contentId, type = "series", name = "Test Meta"))
        )
    }

    private suspend fun capturedUrl(api: AddonApi): String {
        val url = slot<String>()
        coVerify(exactly = 1) { api.getMeta(capture(url)) }
        return url.captured
    }

    private fun newRepository(api: AddonApi, vararg addons: Addon): MetaRepositoryImpl =
        newRepository(api, flowOf(addons.toList()))

    private fun newRepository(api: AddonApi, addonsFlow: Flow<List<Addon>>): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "No supported addon"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns addonsFlow
        }
        return MetaRepositoryImpl(context = context, api = api, addonRepository = addonRepository)
    }
}
