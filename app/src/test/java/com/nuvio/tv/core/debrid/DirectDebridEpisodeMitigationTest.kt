package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.DebridSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDebridEpisodeMitigationTest {

    @Test
    fun `extractEmbeddedInfoHashFrom extracts 40-char BTIH from addon resolve URL and bingeGroup`() {
        val btih = "f03841b6b24585b1571df6b0c930d9946047058f"
        val resolveUrl = "https://animesub.duckdns.org/resolve/redactedToken/0/$btih/null/15/Frieren.mkv?fileIndex=15"
        assertEquals(btih, extractEmbeddedInfoHashFrom(url = resolveUrl, bingeGroup = null))

        val bingeGroup = "animesub_torbox_4f63ab726f514584597ee07b3fb794915838f48b"
        assertEquals(
            "4f63ab726f514584597ee07b3fb794915838f48b",
            extractEmbeddedInfoHashFrom(url = "https://example.com/stream.mkv", bingeGroup = bingeGroup)
        )
    }

    @Test
    fun `mitigateMismatchedEpisodeStream resolves requested episode via saved TorBox key even when resolve setting is disabled`() = runTest {
        val btih = "f03841b6b24585b1571df6b0c930d9946047058f"
        val settingsDataStore = mockk<DebridSettingsDataStore> {
            every { settings } returns flowOf(
                DebridSettings(
                    enabled = false,
                    torboxApiKey = "saved-torbox-key",
                    preferredResolverProviderId = DebridProviders.TORBOX_ID
                )
            )
        }
        val torboxResolver = mockk<TorboxDirectDebridResolver>()
        val realDebridResolver = mockk<RealDebridDirectDebridResolver>()
        val premiumizeResolver = mockk<PremiumizeDirectDebridResolver>()
        val localDebridService = mockk<LocalDebridService>()

        val expectedSuccess = DirectDebridResolveResult.Success(
            url = "https://store-027.tb-cdn.st/dl/corrected-s01e01",
            filename = "S01E01-The Journey's End [89B93EDF].mkv",
            videoSize = 609_455_144L
        )

        coEvery {
            torboxResolver.resolve(
                stream = match { s ->
                    s.infoHash == btih &&
                        s.fileIdx == null &&
                        s.behaviorHints?.filename == null &&
                        s.clientResolve?.filename == null
                },
                season = 1,
                episode = 1
            )
        } returns expectedSuccess

        val resolver = DirectDebridResolver(
            dataStore = settingsDataStore,
            torboxResolver = torboxResolver,
            realDebridResolver = realDebridResolver,
            premiumizeResolver = premiumizeResolver,
            localDebridService = localDebridService
        )

        assertTrue(resolver.hasSavedDebridKey())

        val mitigation = resolver.mitigateMismatchedEpisodeStream(
            infoHash = null,
            filenameHint = "S01E16-Long-Lived Friends [21E3FD7E].mkv",
            streamUrl = "https://animesub.duckdns.org/resolve/redactedToken/0/$btih/null/15/Frieren.mkv?fileIndex=15",
            bingeGroup = "animesub_torbox_$btih",
            title = "AnimeSub+ (TB) 1080p",
            addonName = "AnimeSub+",
            season = 1,
            episode = 1
        )

        assertTrue(mitigation is DirectDebridMitigationResult.Mitigated)
        val mitigated = mitigation as DirectDebridMitigationResult.Mitigated
        assertEquals("Torbox", mitigated.providerName)
        assertEquals("https://store-027.tb-cdn.st/dl/corrected-s01e01", mitigated.result.url)
        assertEquals("S01E01-The Journey's End [89B93EDF].mkv", mitigated.result.filename)
    }
}
