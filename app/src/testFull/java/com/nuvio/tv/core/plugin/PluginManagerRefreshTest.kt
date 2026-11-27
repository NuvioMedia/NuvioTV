package com.nuvio.tv.core.plugin

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionRunner
import com.nuvio.tv.core.plugin.cloudstream.ExternalRepoParser
import com.nuvio.tv.core.sync.PluginSyncService
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.domain.model.PluginRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginManagerRefreshTest {
    @Test
    fun `startup refresh re-downloads manifest and scraper code for every repository`() = runBlocking {
        val server = MockWebServer()
        val code = "module.exports = { updated: true }"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"name":"Updated repo","version":"2","scrapers":[{"id":"scraper","name":"Scraper","version":"2","filename":"scraper.js"}]}""",
                ),
        )
        server.enqueue(MockResponse().setHeader("Content-Length", code.toByteArray().size))
        server.enqueue(MockResponse().setBody(code))
        server.start()

        val repo = PluginRepository(
            id = "repo-1",
            name = "Cached repo",
            url = server.url("/manifest.json").toString(),
        )
        val dataStore = mockk<PluginDataStore>(relaxed = true)
        every { dataStore.repositories } returns flowOf(listOf(repo))
        every { dataStore.scrapers } returns flowOf(emptyList())

        val manager = PluginManager(
            dataStore = dataStore,
            runtime = mockk(relaxed = true),
            pluginSyncService = mockk(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true),
            externalRepoParser = mockk<ExternalRepoParser>(relaxed = true),
            externalExtensionLoader = mockk<ExternalExtensionLoader>(relaxed = true),
            externalExtensionRunner = mockk<ExternalExtensionRunner>(relaxed = true),
        )

        manager.refreshAllRepositories()

        coVerify {
            dataStore.updateRepository(
                match { it.name == "Updated repo" && it.scraperCount == 1 },
            )
        }
        coVerify { dataStore.saveScraperCode("repo-1:scraper", code) }
        assertEquals(3, server.requestCount)
        repeat(3) {
            assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
        }
        server.shutdown()
    }

    @Test
    fun `startup refresh with no repositories performs no network work`() = runBlocking {
        val dataStore = mockk<PluginDataStore>(relaxed = true)
        every { dataStore.repositories } returns flowOf(emptyList())

        val manager = PluginManager(
            dataStore = dataStore,
            runtime = mockk(relaxed = true),
            pluginSyncService = mockk(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true),
            externalRepoParser = mockk<ExternalRepoParser>(relaxed = true),
            externalExtensionLoader = mockk<ExternalExtensionLoader>(relaxed = true),
            externalExtensionRunner = mockk<ExternalExtensionRunner>(relaxed = true),
        )

        manager.refreshAllRepositories()

        coVerify(exactly = 0) { dataStore.updateRepository(any()) }
    }
}
