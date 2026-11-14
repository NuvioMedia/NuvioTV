package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AddonEventDispatcherTest {

    @Test
    fun `event url encodes ids and extras and keeps the base query`() {
        val url = buildAddonEventUrl(
            baseUrl = "https://addon.example/token?lang=pl",
            resource = ADDON_LIBRARY_RESOURCE,
            type = "series",
            id = "tt0898266",
            extra = listOf("action" to "watched", "videoId" to "tt0898266:1:1,tt0898266:1:2")
        )

        assertEquals(
            "https://addon.example/token/library/series/tt0898266/" +
                "action=watched&videoId=tt0898266%3A1%3A1%2Ctt0898266%3A1%3A2.json?lang=pl",
            url
        )
    }

    @Test
    fun `player event url encodes the video id`() {
        val url = buildAddonEventUrl(
            baseUrl = "https://addon.example/",
            resource = ADDON_PLAYER_RESOURCE,
            type = "series",
            id = "kitsu:48239:1",
            extra = listOf("action" to "start", "currentTime" to "0", "duration" to "1465319")
        )

        assertEquals(
            "https://addon.example/player/series/kitsu%3A48239%3A1/action=start&currentTime=0&duration=1465319.json",
            url
        )
    }

    @Test
    fun `video ids are deduplicated and chunked by 100`() {
        val ids = (1..250).map { "tt1:1:$it" } + listOf("tt1:1:1", "", " ")

        val chunks = libraryVideoIdChunks(ids)

        assertEquals(listOf(100, 100, 50), chunks.map { it.size })
        assertEquals("tt1:1:1", chunks.first().first())
        assertEquals("tt1:1:250", chunks.last().last())
    }

    @Test
    fun `events go only to enabled addons declaring the resource and matching the id`() = runBlocking {
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val api = recordingApi(delivered)
        val dispatcher = AddonEventDispatcher(
            api,
            repositoryWith(
                trackingAddon("https://all.example"),
                trackingAddon("https://tt-only.example", idPrefixes = listOf("tt")),
                trackingAddon("https://disabled.example", enabled = false),
                trackingAddon("https://player-only.example", resources = listOf(ADDON_PLAYER_RESOURCE))
            )
        )

        dispatcher.library("series", "kitsu:48239", AddonLibraryAction.LIBRARY_ADD)
        dispatcher.library("series", "srv1:conn:item", AddonLibraryAction.LIBRARY_ADD)

        coVerify(timeout = 2_000) { api.sendEvent(match { it.startsWith("https://all.example/") }) }
        Thread.sleep(200)
        assertEquals(listOf("https://all.example/library/series/kitsu%3A48239/action=libraryAdd.json"), delivered)
    }

    @Test
    fun `a slow addon does not delay events for other addons`() = runBlocking {
        val fastDelivered = CompletableDeferred<Unit>()
        val api = mockk<AddonApi>()
        coEvery { api.sendEvent(match { it.startsWith("https://slow.example/") }) } coAnswers { awaitCancellation() }
        coEvery { api.sendEvent(match { it.startsWith("https://fast.example/") }) } coAnswers {
            fastDelivered.complete(Unit)
            Response.success(ResponseBody.create(null, "{}"))
        }
        val dispatcher = AddonEventDispatcher(
            api,
            repositoryWith(trackingAddon("https://slow.example"), trackingAddon("https://fast.example"))
        )

        dispatcher.player(
            AddonPlayerEventTarget("movie", "tt1254207"),
            AddonPlayerAction.START,
            currentTimeMs = 0L,
            durationMs = 1_000L
        )
        dispatcher.player(
            AddonPlayerEventTarget("movie", "tt1254207"),
            AddonPlayerAction.PAUSE,
            currentTimeMs = 500L,
            durationMs = 1_000L
        )

        withTimeout(2_000) { fastDelivered.await() }
        coVerify(timeout = 2_000) { api.sendEvent(match { it.startsWith("https://fast.example/") && it.contains("action=pause") }) }
    }

    @Test
    fun `events for one addon keep their order`() = runBlocking {
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val api = recordingApi(delivered)
        val dispatcher = AddonEventDispatcher(api, repositoryWith(trackingAddon("https://addon.example")))
        val target = AddonPlayerEventTarget("series", "tt1:1:7")

        listOf(AddonPlayerAction.START, AddonPlayerAction.PAUSE, AddonPlayerAction.START, AddonPlayerAction.STOP)
            .forEachIndexed { index, action ->
                dispatcher.player(target, action, currentTimeMs = index * 1_000L, durationMs = 10_000L)
            }

        coVerify(timeout = 2_000, exactly = 4) { api.sendEvent(any()) }
        assertEquals(
            listOf("start", "pause", "start", "stop"),
            delivered.map { it.substringAfter("action=").substringBefore('&') }
        )
    }

    @Test
    fun `hasEnabledAddonFor only counts enabled addons declaring the resource`() = runBlocking {
        val dispatcher = AddonEventDispatcher(
            mockk(relaxed = true),
            repositoryWith(
                trackingAddon("https://player.example", resources = listOf(ADDON_PLAYER_RESOURCE)),
                trackingAddon("https://library-off.example", resources = listOf(ADDON_LIBRARY_RESOURCE), enabled = false)
            )
        )

        assertTrue(dispatcher.hasEnabledAddonFor(ADDON_PLAYER_RESOURCE))
        assertFalse(dispatcher.hasEnabledAddonFor(ADDON_LIBRARY_RESOURCE))
    }

    private fun recordingApi(delivered: MutableList<String>): AddonApi = mockk<AddonApi>().also { api ->
        coEvery { api.sendEvent(any()) } coAnswers {
            delivered += firstArg<String>()
            Response.success(ResponseBody.create(null, "{}"))
        }
    }

    private fun repositoryWith(vararg addons: Addon): AddonRepository = mockk<AddonRepository>().also { repository ->
        every { repository.getInstalledAddons() } returns flowOf(addons.toList())
    }

    private fun trackingAddon(
        baseUrl: String,
        resources: List<String> = listOf(ADDON_PLAYER_RESOURCE, ADDON_LIBRARY_RESOURCE),
        idPrefixes: List<String> = emptyList(),
        enabled: Boolean = true
    ) = Addon(
        id = baseUrl,
        name = baseUrl,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = listOf("movie", "series"),
        resources = resources.map { AddonResource(name = it, types = listOf("movie", "series"), idPrefixes = null) },
        idPrefixes = idPrefixes,
        enabled = enabled
    )
}
