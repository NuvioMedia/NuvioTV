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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/**
 * A meta candidate must only ever be built with a type the addon advertises.
 * Nuvio uses "tv" internally for episodic content while addons declare "series",
 * so a "tv" request against a series-only addon must go out as "series" and never
 * as "tv", which returns null meta and leaves the seasons list empty.
 */
class MetaRepositoryCandidateTypeTest {

    private val contentId = "tt0944947"

    @Test
    fun `tv request against a series-only addon is sent as series`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("movie", "series")))

        repository.getMetaFromAllAddons("tv", contentId).last()

        assertEquals("https://addon.example/meta/series/$contentId.json", capturedUrl(api))
    }

    @Test
    fun `tv request is sent as tv when the addon actually declares tv`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("series", "tv")))

        repository.getMetaFromAllAddons("tv", contentId).last()

        assertEquals("https://addon.example/meta/tv/$contentId.json", capturedUrl(api))
    }

    @Test
    fun `uppercase TV is canonicalized to series`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("series")))

        repository.getMetaFromAllAddons("TV", contentId).last()

        assertEquals("https://addon.example/meta/series/$contentId.json", capturedUrl(api))
    }

    @Test
    fun `addon supporting neither requested nor inferred type is never requested`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("movie")))

        repository.getMetaFromAllAddons("tv", contentId).last()

        coVerify(exactly = 0) { api.getMeta(any()) }
    }

    @Test
    fun `addon declaring no meta types still receives the request`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = emptyList()))

        repository.getMetaFromAllAddons("tv", contentId).last()

        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    @Test
    fun `movie requests are unaffected`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("movie", "series")))

        repository.getMetaFromAllAddons("movie", contentId).last()

        assertEquals("https://addon.example/meta/movie/$contentId.json", capturedUrl(api))
    }

    @Test
    fun `tv in the id is inferred as series when the type is not canonical`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(
            api,
            addon(metaTypes = listOf("series"), idPrefixes = listOf("tmdb:"))
        )

        repository.getMetaFromAllAddons("unknown", "tmdb:tv:1399").last()

        assertEquals(
            "https://addon.example/meta/series/tmdb%3Atv%3A1399.json",
            capturedUrl(api)
        )
    }

    @Test
    fun `primary addon selection skips an addon that supports neither type`() = runTest {
        val api = apiReturningMeta()
        val repository = newRepository(api, addon(metaTypes = listOf("movie")))

        repository.getMetaFromPrimaryAddon("tv", contentId).last()

        coVerify(exactly = 0) { api.getMeta(any()) }
    }

    private fun addon(
        metaTypes: List<String>,
        idPrefixes: List<String> = listOf("tt")
    ) = Addon(
        id = "test.addon",
        name = "Test Addon",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.example",
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
        coVerify { api.getMeta(capture(url)) }
        return url.captured
    }

    private fun newRepository(api: AddonApi, vararg addons: Addon): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "No supported addon"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(addons.toList())
        }
        return MetaRepositoryImpl(context = context, api = api, addonRepository = addonRepository)
    }
}
