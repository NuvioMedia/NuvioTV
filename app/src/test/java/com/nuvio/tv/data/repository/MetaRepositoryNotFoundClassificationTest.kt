package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * A meta lookup that produced no result has to say why. An addon answering "no such item" is a
 * final answer and callers cache it; an addon that could not be reached is not, and callers retry.
 * Both used to arrive as the same codeless Error, so Home marked an item enriched after a failed
 * request and never asked again for the rest of the session.
 */
class MetaRepositoryNotFoundClassificationTest {

    private val contentId = "tt0944947"

    @Test
    fun `every addon reporting the item missing is final`() = runTest {
        val repository = newRepository(apiAnswering { notFound() }, addon("a"), addon("b"))

        val result = repository.getMetaFromAllAddons("movie", contentId).last()

        assertEquals(NetworkResult.META_NOT_FOUND_CODE, (result as NetworkResult.Error).code)
    }

    @Test
    fun `an unreachable addon is retryable`() = runTest {
        val repository = newRepository(apiAnswering { throw IOException("offline") }, addon("a"))

        val result = repository.getMetaFromAllAddons("movie", contentId).last()

        assertNull(
            "a transport failure must stay retryable, not be cached as a final answer",
            (result as NetworkResult.Error).code
        )
    }

    @Test
    fun `one unreachable addon among missing ones is retryable`() = runTest {
        // Keyed on the requested URL rather than a call counter, so the outcome does not depend
        // on the order the repository happens to try the addons in.
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            if (firstArg<String>().startsWith("https://a.")) notFound() else throw IOException("offline")
        }
        val repository = newRepository(api, addon("a"), addon("b"))

        val result = repository.getMetaFromAllAddons("movie", contentId).last()

        // The reachable addon genuinely does not have it, but the other one was never asked
        // successfully, so the lookup is not final.
        assertNull((result as NetworkResult.Error).code)
    }

    @Test
    fun `a single addon answering successfully with no meta is final`() = runTest {
        // The only attempt produced an empty body. That has to be recorded as a missing result,
        // or the lookup would reach the caller with no failures and be treated as retryable.
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns
            Response.success(com.nuvio.tv.data.remote.dto.MetaResponseDto(meta = null))
        val repository = newRepository(api, addon("a"))

        val result = repository.getMetaFromAllAddons("movie", contentId).last()

        assertEquals(NetworkResult.META_NOT_FOUND_CODE, (result as NetworkResult.Error).code)
    }

    @Test
    fun `a successful response carrying no meta counts as missing`() = runTest {
        // The addon answered and has nothing for this id, which is the same final answer as a 404.
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            if (firstArg<String>().startsWith("https://a.")) {
                Response.success(com.nuvio.tv.data.remote.dto.MetaResponseDto(meta = null))
            } else {
                notFound()
            }
        }
        val repository = newRepository(api, addon("a"), addon("b"))

        val result = repository.getMetaFromAllAddons("movie", contentId).last()

        assertEquals(NetworkResult.META_NOT_FOUND_CODE, (result as NetworkResult.Error).code)
    }

    @Test
    fun `a non-404 error response is retryable`() = runTest {
        // Only 404 counts as the addon answering "no such item". A 500 is the server failing, so
        // the lookup must stay retryable even though the addon did respond.
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns Response.error(
            500,
            "".toResponseBody("application/json".toMediaType())
        )
        val repository = newRepository(api, addon("a"))

        val result = repository.getMetaFromAllAddons("movie", contentId).last()

        assertNull((result as NetworkResult.Error).code)
    }

    @Test
    fun `no addon supporting the type is final`() = runTest {
        val repository = newRepository(
            apiAnswering { notFound() },
            addon("a", metaTypes = listOf("series"), rawTypes = listOf("series"))
        )

        val result = repository.getMetaFromAllAddons("movie", contentId).last()

        // Nothing was even eligible to ask, so retrying cannot reach anything new. This path
        // returns before the addon loop, so it needs its own classification.
        assertEquals(NetworkResult.META_NOT_FOUND_CODE, (result as NetworkResult.Error).code)
    }

    @Test
    fun `a successful lookup is not an error at all`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns Response.success(
            com.nuvio.tv.data.remote.dto.MetaResponseDto(
                meta = com.nuvio.tv.data.remote.dto.MetaDto(
                    id = contentId,
                    type = "movie",
                    name = "Test Meta"
                )
            )
        )
        val repository = newRepository(api, addon("a"))

        val result = repository.getMetaFromAllAddons("movie", contentId).last()

        assertTrue(result is NetworkResult.Success)
    }

    private fun notFound(): Response<com.nuvio.tv.data.remote.dto.MetaResponseDto> =
        Response.error(404, "".toResponseBody("application/json".toMediaType()))

    private fun apiAnswering(
        answer: suspend () -> Response<com.nuvio.tv.data.remote.dto.MetaResponseDto>
    ): AddonApi = mockk<AddonApi>().also { api ->
        coEvery { api.getMeta(any()) } coAnswers { answer() }
    }

    private fun addon(
        key: String,
        metaTypes: List<String> = listOf("movie", "series"),
        rawTypes: List<String> = listOf("movie", "series")
    ) = Addon(
        id = "test.addon.$key",
        name = "Test Addon $key",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://$key.addon.example",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = rawTypes,
        resources = listOf(AddonResource(name = "meta", types = metaTypes, idPrefixes = null)),
        idPrefixes = listOf("tt")
    )

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
