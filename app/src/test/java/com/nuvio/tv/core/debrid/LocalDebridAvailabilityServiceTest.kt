package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalDebridAvailabilityServiceTest {
    @Test
    fun `markChecking marks local torrent streams when cache provider is active`() = runTest {
        val service = service()
        val direct = stream(url = "https://example.com/video.mkv", infoHash = null)
        val torrent = stream(infoHash = "ABC123")

        val result = service.markChecking(listOf(group(listOf(direct, torrent))))
        val streams = result.single().streams

        assertNull(streams[0].debridCacheStatus)
        assertEquals(StreamDebridCacheState.CHECKING, streams[1].debridCacheStatus?.state)
        assertEquals(DebridProviders.TORBOX_ID, streams[1].debridCacheStatus?.providerId)
    }

    @Test
    fun `annotateCachedAvailability marks cached and not cached local torrents`() = runTest {
        val localDebridService = mockk<LocalDebridService>()
        coEvery {
            localDebridService.checkCached(any(), any())
        } returns mapOf(
            "abc123" to LocalDebridCachedItem(name = "cached.mkv", size = 123L)
        )
        val service = service(localDebridService)
        val cached = stream(name = "Cached", infoHash = "ABC123")
        val notCached = stream(name = "Not cached", infoHash = "DEF456")

        val result = service.annotateCachedAvailability(listOf(group(listOf(cached, notCached))))
        val streams = result.single().streams

        assertEquals(StreamDebridCacheState.CACHED, streams[0].debridCacheStatus?.state)
        assertEquals("cached.mkv", streams[0].debridCacheStatus?.cachedName)
        assertEquals(123L, streams[0].debridCacheStatus?.cachedSize)
        assertEquals(StreamDebridCacheState.NOT_CACHED, streams[1].debridCacheStatus?.state)
    }

    @Test
    fun `annotateCachedAvailability marks unknown when provider check fails`() = runTest {
        val localDebridService = mockk<LocalDebridService>()
        coEvery {
            localDebridService.checkCached(any(), any())
        } returns null
        val service = service(localDebridService)

        val result = service.annotateCachedAvailability(listOf(group(listOf(stream(infoHash = "ABC123")))))
        val status = result.single().streams.single().debridCacheStatus

        assertEquals(StreamDebridCacheState.UNKNOWN, status?.state)
        assertEquals(DebridProviders.TORBOX_ID, status?.providerId)
    }

    private fun service(
        localDebridService: LocalDebridService = mockk(relaxed = true)
    ): LocalDebridAvailabilityService {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(
            DebridSettings(
                enabled = true,
                torboxApiKey = "tb_token"
            )
        )
        return LocalDebridAvailabilityService(dataStore, localDebridService)
    }

    private fun group(streams: List<Stream>): AddonStreams =
        AddonStreams(
            addonName = "Addon",
            addonLogo = null,
            streams = streams
        )

    private fun stream(
        name: String = "Torrent",
        url: String? = null,
        infoHash: String? = "ABC123"
    ): Stream =
        Stream(
            name = name,
            title = name,
            description = null,
            url = url,
            ytId = null,
            infoHash = infoHash,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = "$name.mkv"
            ),
            addonName = "Addon",
            addonLogo = null
        )
}
