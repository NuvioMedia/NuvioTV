package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbAlternativeTitlesResponse
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbMovieNamesResponse
import com.nuvio.tv.data.remote.api.TmdbTranslationsResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import com.nuvio.tv.domain.model.StreamBadge
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class BuiltInUsenetServiceTest {
    @Test fun `each indexer uses its own fetch limit and the combined list keeps its display limit`() = runTest {
        val hydra = UsenetIndexer(id = "hydra", name = "Hydra", apiUrl = "https://hydra.test/api", fetchResultsLimit = 0)
        val direct = UsenetIndexer(id = "direct", name = "Direct", apiUrl = "https://direct.test/api", fetchResultsLimit = 100)
        val config = UsenetSourceConfiguration(enabled = true, maxResults = 1,
            providers = listOf(UsenetProvider(name = "News", host = "news.test")), indexers = listOf(hydra, direct))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        for (indexer in config.indexers) {
            coEvery { client.search(indexer, any(), any(), config.indexerResults(indexer)) } returns listOf(
                UsenetRelease("Show.S01E03.1080p", "https://${indexer.id}.test/nzb/1", indexerId = indexer.id))
        }
        val groups = BuiltInUsenetService(client, mockk(), mockk()).search(config, "series", "tt123:1:3", null, null)
            .toList().mapNotNull { it.group }
        assertEquals(1, groups.last().streams.size)
        coVerify { client.search(hydra, any(), any(), 0) }
        coVerify { client.search(direct, any(), any(), 100) }
    }

    @Test fun `failed indexer does not cancel good results and episode suffixes are resolved`() = runTest {
        val good = UsenetIndexer(id = "good", name = "Good", apiUrl = "https://good.test/api")
        val bad = UsenetIndexer(id = "bad", name = "Bad", apiUrl = "https://bad.test/api")
        val config = UsenetSourceConfiguration(enabled = true,
            providers = listOf(UsenetProvider(name = "News", host = "news.test", username = "user", password = "pass")),
            indexers = listOf(good, bad))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        coEvery { client.capabilities(good) } returns NewznabCapabilities()
        coEvery { client.capabilities(bad) } throws IllegalStateException("secret-api-key")
        coEvery { client.search(good, any(), any()) } returns listOf(
            UsenetRelease("Show.S01E03.1080p", "https://good.test/nzb/1", indexerId = good.id, indexerName = good.name))
        val service = BuiltInUsenetService(client, tmdb, mockk<TmdbApi>())
        val results = service.search(config, "series", "tt123:1:3", null, null).toList()
        assertEquals(1, results.count { it.failure != null })
        assertFalse(results.mapNotNull { it.failure }.joinToString().contains("secret-api-key"))
        val stream = results.mapNotNull { it.group }.single().streams.single()
        assertTrue(stream.isUsenet())
        assertEquals(listOf("nntps://user:pass@news.test:563/20?priority=1"), stream.servers)
        coVerify { client.search(good, match { it.season == 1 && it.episode == 3 && it.imdbId == "tt123" }, any()) }
        coVerify(exactly = 0) { tmdb.ensureTmdbId(any(), any()) }
    }

    @Test fun `a reused release opens with today's providers`() {
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(
            UsenetProvider(name = "News", host = "news.test", username = "user", password = "new"),
            UsenetProvider(name = "Off", host = "off.test", enabled = false)))
        val stream = BuiltInUsenetService.restoredStream("https://a.test/nzb/1", "4K • Usenet • A",
            "Show.S01E03.2160p-GRP", 9_000_000_000, 1_600_000_000_000, config)!!
        assertTrue(stream.isUsenet())
        assertEquals(BuiltInUsenetService.GROUP_NAME, stream.addonName)
        assertEquals(listOf("nntps://user:new@news.test:563/20?priority=1"), stream.servers)
        assertEquals(9_000_000_000, stream.behaviorHints?.videoSize)
        assertEquals(1_600_000_000_000, stream.publishedAt)
        // Its next episode keeps to its upload, as a listed result's does.
        assertEquals(BuiltInUsenetService.bingeGroup("Show.S01E03.2160p-GRP"), stream.behaviorHints?.bingeGroup)
        // With no provider enabled there is nothing to open it with.
        assertNull(BuiltInUsenetService.restoredStream("https://a.test/nzb/1", "A", null, null, null,
            config.copy(providers = config.providers.map { it.copy(enabled = false) })))
    }

    @Test fun `a kept release link holds no API key and opens with today's`() {
        val indexer = UsenetIndexer(name = "A", apiUrl = "https://a.test", apiKey = "0123456789abcdef")
        val config = UsenetSourceConfiguration(enabled = true, indexers = listOf(indexer),
            providers = listOf(UsenetProvider(name = "News", host = "news.test")))
        val link = "https://a.test/api?t=get&id=1&apikey=0123456789abcdef"
        val kept = BuiltInUsenetService.withoutApiKeys(link, config)
        assertFalse(kept.contains("0123456789abcdef"))
        assertEquals(link, BuiltInUsenetService.withApiKeys(kept, config))
        // A key changed since is the one used; wherever the indexer put it in the link.
        val renewed = config.copy(indexers = listOf(indexer.copy(apiKey = "fedcba9876543210")))
        assertEquals("https://a.test/api?t=get&id=1&apikey=fedcba9876543210",
            BuiltInUsenetService.restoredStream(kept, "A", null, null, null, renewed)?.nzbUrl)
        assertEquals("https://a.test/fedcba9876543210/1.nzb", BuiltInUsenetService.withApiKeys(
            BuiltInUsenetService.withoutApiKeys("https://a.test/0123456789abcdef/1.nzb", config), renewed))
        // Its indexer removed, or left without a key: the list shows instead.
        assertNull(BuiltInUsenetService.restoredStream(kept, "A", null, null, null, config.copy(indexers = emptyList())))
        assertNull(BuiltInUsenetService.withApiKeys(kept, config.copy(indexers = listOf(indexer.copy(apiKey = "")))))
        // A link kept before, or one that holds no known key, is unchanged.
        assertEquals("https://b.test/nzb/1", BuiltInUsenetService.withApiKeys(
            BuiltInUsenetService.withoutApiKeys("https://b.test/nzb/1", config), config))
    }

    @Test fun `built-in badges are shown translated and other badges by their name`() {
        assertEquals(com.nuvio.tv.R.string.usenet_badge_unavailable to null,
            BuiltInUsenetService.badgeText(BuiltInUsenetService.UNAVAILABLE_LABEL))
        assertEquals(com.nuvio.tv.R.string.usenet_badge_episode_failed to 2,
            BuiltInUsenetService.badgeText(BuiltInUsenetService.siblingBadge(SiblingOutcome(2, failed = true)).name))
        assertEquals(com.nuvio.tv.R.string.usenet_badge_episode_played to 12,
            BuiltInUsenetService.badgeText(BuiltInUsenetService.siblingBadge(SiblingOutcome(12, failed = false)).name))
        assertNull(BuiltInUsenetService.badgeText("BD 50"))
    }

    @Test fun `an upload's episodes share a binge group, so the next episode keeps to it`() {
        fun group(title: String) = BuiltInUsenetService.bingeGroup(title)
        val e01 = group("Silo.S01E01.Freedom.Day.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-NTb")
        assertNotNull(e01)
        assertEquals(e01, group("Silo.S01E02.Holstons.Pick.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-NTb"))
        assertNotEquals(e01, group("Silo.S01E02.Holstons.Pick.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-FLUX"))
        assertNotEquals(e01, group("Silo.S02E01.The.Engineer.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-NTb"))
        // A season pack plays every episode: it is its own group. An episode-less series release has none.
        assertEquals(group("Silo.S01.2160p.ATVp.WEB-DL-NTb"), group("silo.s01.2160p.atvp.web-dl-ntb"))
        assertNull(group("Movie.2020.1080p.WEB-DL-GRP"))

        fun stream(title: String) = with(BuiltInUsenetService) {
            com.nuvio.tv.domain.model.Stream(name = "4K • Usenet • A", title = title, description = null, url = null,
                ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
                behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(null, bingeGroup(title), null, null),
                addonName = GROUP_NAME, addonLogo = null, nzbUrl = "https://a.test/$title", servers = listOf(provider.serverUrl()))
        }
        val next = listOf(stream("Silo.S01E02.Holstons.Pick.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-FLUX"),
            stream("Silo.S01E02.Holstons.Pick.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-NTb"))
        val picked = com.nuvio.tv.core.player.StreamAutoPlaySelector.selectAutoPlayStream(next,
            com.nuvio.tv.data.local.StreamAutoPlayMode.MANUAL, "", com.nuvio.tv.data.local.StreamAutoPlaySource.ALL_SOURCES,
            emptySet(), emptySet(), emptySet(), preferredBingeGroup = e01, preferBingeGroupInSelection = true, bingeGroupOnly = true)
        assertEquals(next[1], picked)
    }

    private val provider = UsenetProvider(name = "News", host = "news.test")
    private fun release(indexer: UsenetIndexer, title: String = "Movie.2020.1080p") =
        UsenetRelease(title, "${indexer.apiUrl}/nzb/$title", indexerId = indexer.id, indexerName = indexer.name)
    private fun showSeasons(vararg episodes: Pair<Int, Int>) = TmdbDetailsResponse(id = 42,
        seasons = episodes.map { (season, count) -> com.nuvio.tv.data.remote.api.TmdbSeasonSummary(season, count) })

    @Test fun `every indexer is searched by default and repeated releases are kept unless hidden`() = runTest {
        val first = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api", priority = 1)
        val backup = UsenetIndexer(id = "c", name = "C", apiUrl = "https://c.test/api", priority = 2)
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(first, backup))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(first, any(), any()) } returns listOf(release(first, "Movie.2020.1080p-GRP"))
        coEvery { client.search(backup, any(), any()) } returns
            listOf(release(backup, "Movie 2020 1080p-GRP"), release(backup, "Movie.2020.720p-GRP"))
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        suspend fun names(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams.map { it.title }
        assertEquals(listOf("Movie.2020.1080p-GRP", "Movie 2020 1080p-GRP", "Movie.2020.720p-GRP"), names(config))
        coVerify { client.search(backup, any(), any()) }
        // Hidden repeats keep the higher-priority indexer's copy.
        assertEquals(listOf("Movie.2020.1080p-GRP", "Movie.2020.720p-GRP"), names(config.copy(hideDuplicates = true)))
    }

    @Test fun `each indexer's search counts, and its releases count for it once played`() = runTest {
        val found = UsenetIndexer(id = "found", name = "Found", apiUrl = "https://found.test/api")
        val empty = UsenetIndexer(id = "empty", name = "Empty", apiUrl = "https://empty.test/api")
        val failed = UsenetIndexer(id = "failed", name = "Failed", apiUrl = "https://failed.test/api")
        val paused = UsenetIndexer(id = "paused", name = "Paused", apiUrl = "https://paused.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider),
            indexers = listOf(found, empty, failed, paused))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(found, any(), any()) } returns listOf(release(found, "Show.S01E02.1080p"), release(found, "Show.S01E02.720p"))
        coEvery { client.search(empty, any(), any()) } returns emptyList()
        coEvery { client.search(failed, any(), any()) } throws IllegalStateException("down")
        coEvery { client.search(paused, any(), any()) } throws IndexerCooldownException(System.currentTimeMillis() + 60_000)
        val stats = memoryStats()
        BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>(), stats = stats)
            .search(config, "series", "tt1:1:2", null, null).toList()
        fun shows(indexer: UsenetIndexer) = stats.indexer(indexer.id, StatsPeriod.LAST_30_DAYS, StatsContent.SHOWS)
        assertEquals(IndexerCounts(searches = 1, releases = 2), shows(found))
        assertEquals(IndexerCounts(searches = 1, emptySearches = 1), shows(empty))
        assertEquals(IndexerCounts(searches = 1, failedSearches = 1), shows(failed))
        assertEquals(IndexerCounts(searches = 1, pausedSearches = 1), shows(paused))
        assertNull(stats.indexer(found.id, StatsPeriod.LAST_30_DAYS, StatsContent.MOVIES))
        stats.played(release(found, "Show.S01E02.720p").nzbUrl)
        assertEquals(1, shows(found)?.played)
    }

    @Test fun `a kept link names the indexer that listed it`() {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api", apiKey = "abcdefgh12345")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val kept = BuiltInUsenetService.withoutApiKeys("https://a.test/api?t=get&id=1&apikey=abcdefgh12345", config)
        assertEquals(indexer, BuiltInUsenetService.keptLinkIndexer(kept, config))
        assertNull(BuiltInUsenetService.keptLinkIndexer(kept, config.copy(indexers = emptyList())))
        assertNull(BuiltInUsenetService.keptLinkIndexer("https://a.test/api?t=get&id=1", config))
    }

    @Test fun `a whole Blu-ray carries its label, and every result its size`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer),
            excludeFullDisc = false)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns listOf(
            UsenetRelease("Movie.2019.COMPLETE.UHD.BLURAY-GRP", "https://a.test/nzb/1", size = 60L shl 30, indexerId = "a", indexerName = "A"),
            UsenetRelease("Movie.2019.2160p.BluRay.REMUX.HEVC-GRP", "https://a.test/nzb/2", indexerId = "a", indexerName = "A"))
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        val streams = service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams
        val disc = streams.single { it.title!!.contains("COMPLETE") }
        assertEquals(listOf("UHD BLU-RAY"), disc.badges.map { it.name })
        assertEquals(StreamBadge.STYLE_LABEL, disc.badges.single().tagStyle)
        assertEquals(60L shl 30, disc.behaviorHints?.videoSize)
        val remux = streams.single { it.title!!.contains("REMUX") }
        assertTrue(remux.badges.isEmpty())
        // No size known: no size badge.
        assertNull(remux.behaviorHints?.videoSize)
    }

    @Test fun `a release that failed for good goes last, labelled, and stays selectable`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns
            listOf(release(indexer, "Movie.2020.2160p-GRP"), release(indexer, "Movie.2020.1080p-GRP"))
        val unplayable = UnplayableReleases(object : UnplayableReleaseStorage {
            var value: String? = null
            override fun load() = value
            override fun save(value: String) { this.value = value }
        })
        unplayable.record("https://a.test/api/nzb/Movie.2020.2160p-GRP", null, null,
            config.providers.map { it.serverUrl() }, "missing-article")
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>(), unplayable)
        val streams = service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams
        assertEquals(listOf("Movie.2020.1080p-GRP", "Movie.2020.2160p-GRP"), streams.map { it.title })
        assertEquals(listOf(BuiltInUsenetService.UNAVAILABLE_LABEL), streams.last().badges.map { it.name })
        assertTrue(streams.first().badges.isEmpty())
    }

    @Test fun `a release that played recently is listed first, labelled, when chosen`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer),
            availableFirst = true)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns
            listOf(release(indexer, "Movie.2020.2160p-GRP"), release(indexer, "Movie.2020.720p-GRP"))
        val outcomes = UnplayableReleases(object : UnplayableReleaseStorage {
            var value: String? = null
            override fun load() = value
            override fun save(value: String) { this.value = value }
        })
        outcomes.recordPlayed("https://a.test/api/nzb/Movie.2020.720p-GRP", null, null, config.providers.map { it.serverUrl() })
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>(), outcomes)
        suspend fun streams(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams
        val first = streams(config)
        assertEquals(listOf("Movie.2020.720p-GRP", "Movie.2020.2160p-GRP"), first.map { it.title })
        assertEquals(listOf(BuiltInUsenetService.AVAILABLE_LABEL), first.first().badges.map { it.name })
        val sorted = streams(config.copy(availableFirst = false))
        assertEquals(listOf("Movie.2020.2160p-GRP", "Movie.2020.720p-GRP"), sorted.map { it.title })
        assertEquals(listOf(BuiltInUsenetService.AVAILABLE_LABEL), sorted.last().badges.map { it.name })
    }

    @Test fun `a season pack is labelled with its whole size and an episode's share`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer),
            maxSizeGb = 5, showReleaseNames = false)
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns listOf(
            UsenetRelease("Show.S01.1080p-GRP", "https://a.test/nzb/pack", size = 9L shl 30, indexerId = "a", indexerName = "A"))
        coEvery { tmdb.ensureTmdbId(any(), any()) } returns "42"
        coEvery { tmdbApi.getTvDetails(42, any(), any()) } returns Response.success(showSeasons(1 to 9))
        val service = BuiltInUsenetService(client, tmdb, tmdbApi)
        val stream = service.search(config, "series", "tt1:1:2", null, null).toList().mapNotNull { it.group }.last().streams.single()
        assertEquals(listOf(BuiltInUsenetService.SEASON_PACK_LABEL), stream.badges.map { it.name })
        assertEquals("9.00 GB whole season (~1.00 GB per episode) • A • GRP", stream.description)
        // The size badge shows an episode's share once the season's episode count is known.
        assertEquals(1L shl 30, stream.behaviorHints?.videoSize)
        coVerify(exactly = 1) { tmdbApi.getTvDetails(42, any(), any()) }
    }

    @Test fun `a pack of several seasons shares its size among all their episodes`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer),
            showReleaseNames = false)
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns listOf(
            UsenetRelease("Show.S01-S03.1080p-GRP", "https://a.test/nzb/pack", size = 34L shl 30, indexerId = "a", indexerName = "A"))
        coEvery { tmdb.ensureTmdbId(any(), any()) } returns "42"
        coEvery { tmdbApi.getTvDetails(42, any(), any()) } returns Response.success(showSeasons(0 to 4, 1 to 10, 2 to 12, 3 to 12))
        val stream = BuiltInUsenetService(client, tmdb, tmdbApi)
            .search(config, "series", "tt1:1:2", null, null).toList().mapNotNull { it.group }.last().streams.single()
        assertEquals("34.00 GB whole pack (~1.00 GB per episode) • A • GRP", stream.description)
        assertEquals(1L shl 30, stream.behaviorHints?.videoSize)
    }

    @Test fun `result names carry the resolution`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns
            listOf(release(indexer, "Movie.2020.2160p-GRP"), release(indexer, "Movie.2020.720p-GRP"), release(indexer, "Movie.2020-GRP"))
        val names = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
            .search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams.map { it.name }
        assertEquals(listOf("4K", "720p", "Usenet"), names)
    }

    @Test fun `results wait for every indexer, briefly, unless shown progressively`() = runTest {
        val fast = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val slow = UsenetIndexer(id = "b", name = "B", apiUrl = "https://b.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(fast, slow))
        val client = mockk<NewznabClient>()
        var slowDelay = 2_000L
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(fast, any(), any()) } returns listOf(release(fast, "Movie.2020.1080p-A"))
        coEvery { client.search(slow, any(), any()) } coAnswers { kotlinx.coroutines.delay(slowDelay); listOf(release(slow, "Movie.2020.720p-B")) }
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        suspend fun lists(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.map { it.streams.size }
        // Both answer within the wait: one complete list.
        assertEquals(listOf(2), lists(config))
        // Progressive: each answer shows.
        assertEquals(listOf(1, 2), lists(config.copy(progressiveResults = true)))
        // A slow indexer does not hold the list back past the wait.
        slowDelay = BuiltInUsenetService.COMPLETE_RESULTS_WAIT_MS * 3
        assertEquals(listOf(1, 2), lists(config))
    }

    @Test fun `a slow season lookup never holds the list back`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer), maxSizeGb = 5)
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns listOf(
            UsenetRelease("Show.S01.1080p-GRP", "https://a.test/nzb/pack", size = 9L shl 30, indexerId = "a", indexerName = "A"),
            UsenetRelease("Show.S01E02.1080p-GRP", "https://a.test/nzb/ep", size = 2L shl 30, indexerId = "a", indexerName = "A"))
        coEvery { tmdb.ensureTmdbId(any(), any()) } returns "42"
        coEvery { tmdbApi.getTvDetails(42, any(), any()) } coAnswers {
            kotlinx.coroutines.delay(60_000); Response.success(showSeasons())
        }
        val shownAt = mutableListOf<Long>()
        val lists = BuiltInUsenetService(client, tmdb, tmdbApi).search(config, "series", "tt1:1:2", null, null)
            .onEach { if (it.group != null) shownAt += testScheduler.currentTime }.toList().mapNotNull { it.group }
        // Shown at once; the lookup timing out later changes nothing.
        assertEquals(listOf(0L), shownAt)
        // Without the count the 9 GB pack is over the 5 GB limit; the episode shows.
        assertEquals(listOf("Show.S01E02.1080p-GRP"), lists.last().streams.map { it.title })
    }

    @Test fun `the list is arranged again when the episode count comes`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer), maxSizeGb = 5)
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns listOf(
            UsenetRelease("Show.S01.1080p-GRP", "https://a.test/nzb/pack", size = 9L shl 30, indexerId = "a", indexerName = "A"),
            UsenetRelease("Show.S01E02.1080p-GRP", "https://a.test/nzb/ep", size = 2L shl 30, indexerId = "a", indexerName = "A"))
        coEvery { tmdb.ensureTmdbId(any(), any()) } returns "42"
        coEvery { tmdbApi.getTvDetails(42, any(), any()) } coAnswers {
            kotlinx.coroutines.delay(1_000)
            Response.success(showSeasons(1 to 9))
        }
        val shownAt = mutableListOf<Long>()
        val lists = BuiltInUsenetService(client, tmdb, tmdbApi).search(config, "series", "tt1:1:2", null, null)
            .onEach { if (it.group != null) shownAt += testScheduler.currentTime }.toList().mapNotNull { it.group }
        assertEquals(listOf(0L, 1_000L), shownAt)
        assertEquals(listOf("Show.S01E02.1080p-GRP"), lists.first().streams.map { it.title })
        // 1 GB per episode: the pack joins the list once the count came.
        assertEquals(setOf("Show.S01E02.1080p-GRP", "Show.S01.1080p-GRP"), lists.last().streams.map { it.title }.toSet())
    }

    @Test fun `results show their release name unless turned off`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns listOf(
            UsenetRelease("Movie.2020.1080p-GRP", "https://a.test/nzb/1", size = 2L shl 30, indexerId = indexer.id, indexerName = indexer.name))
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        suspend fun description(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams.single().description
        assertEquals("Movie.2020.1080p-GRP\n2.00 GB • A", description(config))
        // Without the release name, its group ends the details.
        assertEquals("2.00 GB • A • GRP", description(config.copy(showReleaseNames = false)))
    }

    @Test fun `results show what the release name says, the bitrate and the quality tier`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer),
            ranking = com.nuvio.tv.core.streams.StreamRankingSettings(com.nuvio.tv.core.streams.StreamRankingPreset.BEST_QUALITY))
        val title = "The.Movie.2026.2160p.UHD.BluRay.REMUX.DV.HDR10Plus.HEVC.TrueHD.Atmos.7.1-FraMeSToR"
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns listOf(UsenetRelease(title, "https://a.test/nzb/1",
            size = 60L shl 30, publishedAt = System.currentTimeMillis() - 3 * 86_400_000L - 60_000, indexerId = "a", indexerName = "A"))
        // The runtime comes from the meta the details page holds.
        val meta = mockk<com.nuvio.tv.domain.model.Meta> {
            every { videos } returns emptyList()
            every { runtime } returns "120 min"
        }
        val metas = mockk<com.nuvio.tv.domain.repository.MetaRepository>()
        every { metas.getCachedMeta("movie", "tt1") } returns meta
        val service = BuiltInUsenetService(client, mockk(), mockk(), metas = metas)
        suspend fun stream(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams.single()
        val shown = stream(config)
        assertEquals("4K Remux • DV HDR10+ • HEVC • T1", shown.name)
        assertEquals("$title\nTrueHD 7.1 Atmos • 60.00 GB • 72 Mbps • 3d • A", shown.description)
        assertEquals("TrueHD 7.1 Atmos • 60.00 GB • 72 Mbps • 3d • A • FraMeSToR",
            stream(config.copy(showReleaseNames = false)).description)
    }

    @Test fun `with size badges shown, only a pack's details keep its size`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer),
            showReleaseNames = false)
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns listOf(
            UsenetRelease("Show.S01E02.1080p-GRP", "https://a.test/nzb/episode", size = 2L shl 30, indexerId = "a", indexerName = "A"),
            UsenetRelease("Show.S01.1080p-GRP", "https://a.test/nzb/pack", size = 9L shl 30, indexerId = "a", indexerName = "A"))
        coEvery { tmdb.ensureTmdbId(any(), any()) } returns "42"
        coEvery { tmdbApi.getTvDetails(42, any(), any()) } returns Response.success(showSeasons(1 to 9))
        fun badges(sizes: Boolean) = mockk<com.nuvio.tv.data.local.StreamBadgeSettingsDataStore> {
            every { settings } returns kotlinx.coroutines.flow.flowOf(
                com.nuvio.tv.core.streams.StreamBadgeSettings(showFileSizeBadges = sizes))
        }
        suspend fun details(sizes: Boolean) = BuiltInUsenetService(client, tmdb, tmdbApi, badgeSettings = badges(sizes))
            .search(config, "series", "tt1:1:2", null, null).toList().mapNotNull { it.group }.last().streams
            .associate { it.title to it.description }
        assertEquals(mapOf("Show.S01E02.1080p-GRP" to "A • GRP",
            "Show.S01.1080p-GRP" to "9.00 GB whole season (~1.00 GB per episode) • A • GRP"), details(sizes = true))
        assertEquals("2.00 GB • A • GRP", details(sizes = false)["Show.S01E02.1080p-GRP"])
    }

    @Test fun `with fallback on lower priority indexers are only searched when higher ones find nothing`() = runTest {
        val first = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api", priority = 1)
        val shared = UsenetIndexer(id = "b", name = "B", apiUrl = "https://b.test/api", priority = 1)
        val backup = UsenetIndexer(id = "c", name = "C", apiUrl = "https://c.test/api", priority = 2)
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(backup, first, shared),
            indexerFallback = true)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(first, any(), any()) } returns listOf(release(first))
        coEvery { client.search(shared, any(), any()) } returns emptyList()
        coEvery { client.search(backup, any(), any()) } returns listOf(release(backup))
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        val streams = service.search(config, "movie", "tt1", null, null).toList().last().group!!.streams
        assertEquals(listOf("Movie.2020.1080p\nA"), streams.map { it.description })
        coVerify { client.search(shared, any(), any()) }
        coVerify(exactly = 0) { client.capabilities(backup) }

        // A failed or empty higher priority falls through to the next one.
        coEvery { client.search(first, any(), any()) } throws IllegalStateException("down")
        val fallback = service.search(config, "movie", "tt1", null, null).toList()
        assertEquals(listOf("Movie.2020.1080p\nC"), fallback.mapNotNull { it.group }.last().streams.map { it.description })
    }

    @Test fun `failed metadata lookup only fails indexers that need a title search`() = runTest {
        val ids = UsenetIndexer(id = "ids", name = "Ids", apiUrl = "https://ids.test/api")
        val titles = UsenetIndexer(id = "titles", name = "Titles", apiUrl = "https://titles.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(titles, ids))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        coEvery { client.capabilities(ids) } returns NewznabCapabilities()
        coEvery { client.capabilities(titles) } returns NewznabCapabilities(movieParams = setOf("q"))
        coEvery { client.search(ids, any(), any()) } returns listOf(release(ids))
        coEvery { tmdb.ensureTmdbId(any(), any()) } throws IllegalStateException("tmdb down")
        val results = BuiltInUsenetService(client, tmdb, mockk<TmdbApi>()).search(config, "movie", "tt1", null, null).toList()
        assertEquals(listOf("Titles: indexer search failed. Check the API URL, key and limits."), results.mapNotNull { it.failure })
        assertEquals(listOf("Movie.2020.1080p\nIds"), results.mapNotNull { it.group }.last().streams.map { it.description })
    }

    @Test fun `tvdb id is looked up for indexers that only search tv by tvdb`() = runTest {
        val tvdbOnly = UsenetIndexer(id = "tvdb", name = "Tvdb", apiUrl = "https://tvdb.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(tvdbOnly))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(tvdbOnly) } returns NewznabCapabilities(tvParams = setOf("tvdbid", "season", "ep"))
        coEvery { client.search(tvdbOnly, any(), any()) } returns listOf(release(tvdbOnly, "Show.S01E02.1080p"))
        coEvery { tmdb.ensureTmdbId(any(), any()) } returns "1399"
        coEvery { tmdbApi.getTvDetails(1399, any(), any()) } throws java.io.IOException("offline")
        coEvery { tmdbApi.getTvExternalIds(1399, any()) } returns
            retrofit2.Response.success(com.nuvio.tv.data.remote.api.TmdbExternalIdsResponse(1399, "tt0944947", 121361))
        val results = BuiltInUsenetService(client, tmdb, tmdbApi).search(config, "series", "tt0944947:1:2", null, null).toList()
        assertEquals(listOf("Show.S01E02.1080p\nTvdb"), results.mapNotNull { it.group }.last().streams.map { it.description })
        coVerify { client.search(tvdbOnly, match { it.tvdbId == "121361" && it.episode == 2 }, any()) }
    }

    @Test fun `disabled configuration never contacts indexers`() = runTest {
        val client = mockk<NewznabClient>()
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        assertTrue(service.search(UsenetSourceConfiguration(), "movie", "tt123", null, null).toList().isEmpty())
        coVerify(exactly = 0) { client.capabilities(any()) }
    }

    @Test fun `an empty id search is asked again by title`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities(movieParams = setOf("imdbid", "q"))
        coEvery { client.search(indexer, match { it.imdbId != null }, any()) } returns emptyList()
        coEvery { client.search(indexer, match { it.imdbId == null }, any()) } returns listOf(release(indexer, "The.Movie.2020.1080p"))
        coEvery { tmdb.ensureTmdbId("tt1", "movie") } returns "10"
        coEvery { tmdbApi.getMovieNames(10, any()) } returns
            Response.success(TmdbMovieNamesResponse(title = "The Movie", releaseDate = "2020-05-01"))
        val service = BuiltInUsenetService(client, tmdb, tmdbApi)
        val streams = service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams
        assertEquals(listOf("The.Movie.2020.1080p"), streams.map { it.title })
        coVerify { client.search(indexer, match { it.imdbId == null && it.tmdbId == null && it.title == "The Movie" && it.year == 2020 }, any()) }

        // A title search that also finds nothing is not repeated (the original title equals it here).
        coEvery { client.search(indexer, match { it.imdbId == null }, any()) } returns emptyList()
        assertTrue(service.search(config, "movie", "tt1", null, null).toList().none { it.group != null })
        coVerify(exactly = 2) { client.search(indexer, match { it.imdbId == null }, any()) }
    }

    @Test fun `the original title is searched when the id and the title find nothing`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities(movieParams = setOf("imdbid", "q"))
        coEvery { client.search(indexer, any(), any()) } returns emptyList()
        coEvery { client.search(indexer, match { it.title == "La casa de papel" }, any()) } returns
            listOf(release(indexer, "La.Casa.de.Papel.2017.1080p"))
        coEvery { tmdb.ensureTmdbId("tt1", "movie") } returns "10"
        fun details(original: String) = Response.success(TmdbMovieNamesResponse(title = "Money Heist",
            originalTitle = original, releaseDate = "2017-05-02"))
        coEvery { tmdbApi.getMovieNames(10, any()) } returns details("La casa de papel")
        val service = BuiltInUsenetService(client, tmdb, tmdbApi)
        val streams = service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams
        assertEquals(listOf("La.Casa.de.Papel.2017.1080p"), streams.map { it.title })
        // The English title is still accepted when the query used the original one.
        coVerify { client.search(indexer, match { it.imdbId == null && it.title == "Money Heist" }, any()) }
        coVerify { client.search(indexer, match { it.title == "La casa de papel" && it.originalTitle == "Money Heist" }, any()) }

        // Indexers search Latin text, so an original title in another script is not searched.
        coEvery { tmdbApi.getMovieNames(10, any()) } returns details("ペーパー・ハウス")
        assertTrue(service.search(config, "movie", "tt1", null, null).toList().none { it.group != null })
        coVerify(exactly = 0) { client.search(indexer, match { it.title == "ペーパー・ハウス" }, any()) }
    }

    @Test fun `a movie's id results named after another year are dropped unless its year is unknown`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities(movieParams = setOf("imdbid", "q"))
        // An indexer filed Deathly Hallows Part 2 under the 2001 film.
        coEvery { client.search(indexer, match { it.imdbId != null }, any()) } returns listOf(
            release(indexer, "Harry.Potter.and.the.Sorcerers.Stone.2001.1080p"),
            release(indexer, "Harry.Potter.und.die.Heiligtuemer.des.Todes.Teil.2.2011.German.1080p"))
        coEvery { tmdb.ensureTmdbId("tt0241527", "movie") } returns "671"
        coEvery { tmdbApi.getMovieNames(671, any()) } returns Response.success(TmdbMovieNamesResponse(
            title = "Harry Potter and the Sorcerer's Stone", releaseDate = "2001-11-16"))
        val service = BuiltInUsenetService(client, tmdb, tmdbApi)
        suspend fun titles() = service.search(config, "movie", "tt0241527", null, null).toList()
            .mapNotNull { it.group }.lastOrNull()?.streams.orEmpty().map { it.title }.toSet()
        assertEquals(setOf("Harry.Potter.and.the.Sorcerers.Stone.2001.1080p"), titles())

        // Without the film's year, the indexer's tags are trusted.
        coEvery { tmdbApi.getMovieNames(671, any()) } throws IllegalStateException("offline")
        assertEquals(2, titles().size)
    }

    @Test fun `a movie's id results named after another film are dropped unless its names are unknown`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities(movieParams = setOf("imdbid", "q"))
        coEvery { client.search(indexer, match { it.imdbId != null }, any()) } returns listOf(
            release(indexer, "The.Lord.of.the.Rings.The.Two.Towers.2002.1080p"), release(indexer, "The.Ring.2002.1080p"))
        coEvery { tmdb.ensureTmdbId("tt0167261", "movie") } returns "121"
        coEvery { tmdbApi.getMovieNames(121, any()) } returns Response.success(TmdbMovieNamesResponse(
            title = "The Lord of the Rings: The Two Towers", releaseDate = "2002-12-18",
            alternativeTitles = TmdbAlternativeTitlesResponse(movieTitles = emptyList()),
            translations = TmdbTranslationsResponse(emptyList())))
        val service = BuiltInUsenetService(client, tmdb, tmdbApi)
        suspend fun titles() = service.search(config, "movie", "tt0167261", null, null).toList()
            .mapNotNull { it.group }.lastOrNull()?.streams.orEmpty().map { it.title }.toSet()
        assertEquals(setOf("The.Lord.of.the.Rings.The.Two.Towers.2002.1080p"), titles())

        // An ID search that found only other films is asked again by title.
        coEvery { client.search(indexer, match { it.imdbId != null }, any()) } returns listOf(release(indexer, "The.Ring.2002.1080p"))
        coEvery { client.search(indexer, match { it.imdbId == null }, any()) } returns
            listOf(release(indexer, "The.Lord.of.the.Rings.The.Two.Towers.2002.720p"))
        assertEquals(setOf("The.Lord.of.the.Rings.The.Two.Towers.2002.720p"), titles())

        // Without the film's names and year, the indexer's tags are trusted.
        coEvery { tmdbApi.getMovieNames(121, any()) } throws IllegalStateException("offline")
        assertEquals(setOf("The.Ring.2002.1080p"), titles())
    }

    @Test fun `a movie's release is its own binge group, so reusing the group opens it again`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any()) } returns listOf(
            release(indexer, "Movie.2020.2160p.WEB-DL-GRP"), release(indexer, "Movie.2020.1080p.BluRay-OTHER"))
        val streams = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
            .search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams
        val played = streams.single { it.title == "Movie.2020.1080p.BluRay-OTHER" }
        assertEquals("${BuiltInUsenetService.GROUP_NAME}|movie.2020.1080p.bluray-other", played.behaviorHints?.bingeGroup)
        // Opened again with the binge group reused, the release that played is picked, not the first listed.
        val picked = com.nuvio.tv.core.player.StreamAutoPlaySelector.selectAutoPlayStream(streams,
            com.nuvio.tv.data.local.StreamAutoPlayMode.MANUAL, "", com.nuvio.tv.data.local.StreamAutoPlaySource.ALL_SOURCES,
            emptySet(), emptySet(), emptySet(), preferredBingeGroup = played.behaviorHints?.bingeGroup,
            preferBingeGroupInSelection = true, bingeGroupOnly = true)
        assertEquals(played.title, picked?.title)
        // A kept link opened again carries the same group.
        assertEquals(played.behaviorHints?.bingeGroup, BuiltInUsenetService.restoredStream("https://a.test/nzb/1", "A",
            played.title, null, null, config, series = false)?.behaviorHints?.bingeGroup)
    }

    @Test fun `an anime catalog's episode is searched by the IMDb id and numbering its meta gives`() = runTest {
        val indexer = UsenetIndexer(id = "geek", name = "Geek", apiUrl = "https://geek.test/api")
        val config = UsenetSourceConfiguration(enabled = true,
            providers = listOf(UsenetProvider(name = "News", host = "news.test")), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S02E01.1080p", "https://geek.test/nzb/1", indexerId = indexer.id))
        val meta = mockk<com.nuvio.tv.domain.model.Meta> {
            every { imdbId } returns "tt22248376"
            every { videos } returns listOf(com.nuvio.tv.domain.model.Video(id = "kitsu:49240:1", title = "1",
                released = null, thumbnail = null, season = 1, episode = 1, overview = null, imdbSeason = 2, imdbEpisode = 1))
        }
        val metas = mockk<com.nuvio.tv.domain.repository.MetaRepository>()
        every { metas.getCachedMeta("series", "kitsu:49240") } returns meta
        // The catalog numbers the second season's first episode S1E1; the player passes that.
        val stream = BuiltInUsenetService(client, mockk(), mockk(), metas = metas)
            .search(config, "series", "kitsu:49240:1", 1, 1).toList().mapNotNull { it.group }.last().streams.single()
        coVerify { client.search(indexer, match { it.imdbId == "tt22248376" && it.season == 2 && it.episode == 1 }, any(), any()) }
        coVerify(exactly = 0) { client.search(indexer, match { it.season == 1 }, any(), any()) }
        // Its file in a pack is picked by the same numbering, whatever the player asks for.
        assertEquals(2 to 1, engineEpisode(stream, 1, 1))
    }

    @Test fun `an IMDb id is searched as before, without looking at metas`() = runTest {
        val indexer = UsenetIndexer(id = "geek", name = "Geek", apiUrl = "https://geek.test/api")
        val config = UsenetSourceConfiguration(enabled = true,
            providers = listOf(UsenetProvider(name = "News", host = "news.test")), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S01E03.1080p", "https://geek.test/nzb/1", indexerId = indexer.id))
        val metas = mockk<com.nuvio.tv.domain.repository.MetaRepository>()
        val simkl = mockk<com.nuvio.tv.data.repository.SimklIdResolver>()
        val stream = BuiltInUsenetService(client, mockk(), mockk(), metas = metas, simkl = simkl)
            .search(config, "series", "tt123:1:3", 1, 3, contentId = "tt123").toList().mapNotNull { it.group }.last().streams.single()
        coVerify { client.search(indexer, match { it.imdbId == "tt123" && it.season == 1 && it.episode == 3 }, any(), any()) }
        coVerify(exactly = 0) { metas.getCachedMeta(any(), any()) }
        coVerify(exactly = 0) { metas.getMetaFromAllAddons(any(), any(), any()) }
        coVerify(exactly = 0) { simkl.resolveIds(any(), any(), any()) }
        assertNull(stream.usenetSeason)
        assertNull(stream.usenetEpisode)
    }

    @Test fun `a catalog episode of a title with its own IMDb id is searched by that id and the title's numbering`() = runTest {
        val indexer = UsenetIndexer(id = "geek", name = "Geek", apiUrl = "https://geek.test/api")
        val config = UsenetSourceConfiguration(enabled = true,
            providers = listOf(UsenetProvider(name = "News", host = "news.test")), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S03E02.1080p", "https://geek.test/nzb/1", indexerId = indexer.id))
        val metas = mockk<com.nuvio.tv.domain.repository.MetaRepository>()
        every { metas.getCachedMeta(any(), any()) } returns null
        val simkl = mockk<com.nuvio.tv.data.repository.SimklIdResolver>()
        BuiltInUsenetService(client, mockk(), mockk(), metas = metas, simkl = simkl)
            .search(config, "series", "kitsu:9:40", 3, 2, contentId = "tt777").toList()
        coVerify { client.search(indexer, match { it.imdbId == "tt777" && it.season == 3 && it.episode == 2 }, any(), any()) }
        coVerify(exactly = 0) { metas.getMetaFromAllAddons(any(), any(), any()) }
        coVerify(exactly = 0) { simkl.resolveIds(any(), any(), any()) }
    }

    @Test fun `without IMDb numbering in the meta, Simkl's TVDB numbering is searched with the meta's IMDb id`() = runTest {
        val indexer = UsenetIndexer(id = "geek", name = "Geek", apiUrl = "https://geek.test/api")
        val config = UsenetSourceConfiguration(enabled = true,
            providers = listOf(UsenetProvider(name = "News", host = "news.test")), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S02E01.1080p", "https://geek.test/nzb/1", indexerId = indexer.id))
        val meta = mockk<com.nuvio.tv.domain.model.Meta> {
            every { imdbId } returns "tt22248376"
            every { videos } returns listOf(com.nuvio.tv.domain.model.Video(id = "mal:52991:1", title = "1",
                released = null, thumbnail = null, season = 1, episode = 1, overview = null))
        }
        val metas = mockk<com.nuvio.tv.domain.repository.MetaRepository>()
        every { metas.getCachedMeta("series", "mal:52991") } returns meta
        val simkl = mockk<com.nuvio.tv.data.repository.SimklIdResolver>()
        coEvery { simkl.resolveIds("mal", "52991", any()) } returns
            com.nuvio.tv.data.repository.SimklIdResolver.ResolvedIds(simklId = 7, type = "anime", imdb = "tt999")
        coEvery { simkl.getEpisodeMapping(7, "anime") } returns listOf(
            com.nuvio.tv.data.repository.SimklIdResolver.EpisodeMapping(animeEpisode = 1, tvdbSeason = 2, tvdbEpisode = 1))
        val stream = BuiltInUsenetService(client, mockk(), mockk(), metas = metas, simkl = simkl)
            .search(config, "series", "mal:52991:1", 1, 1, contentId = "mal:52991").toList()
            .mapNotNull { it.group }.last().streams.single()
        coVerify { client.search(indexer, match { it.imdbId == "tt22248376" && it.season == 2 && it.episode == 1 }, any(), any()) }
        assertEquals(2 to 1, engineEpisode(stream, 1, 1))
    }

    @Test fun `a meta without a usable IMDb id takes Simkl's`() = runTest {
        val indexer = UsenetIndexer(id = "geek", name = "Geek", apiUrl = "https://geek.test/api")
        val config = UsenetSourceConfiguration(enabled = true,
            providers = listOf(UsenetProvider(name = "News", host = "news.test")), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(indexer, any(), any(), any()) } returns listOf(
            UsenetRelease("Show.S01E05.1080p", "https://geek.test/nzb/1", indexerId = indexer.id))
        val meta = mockk<com.nuvio.tv.domain.model.Meta> {
            every { imdbId } returns "not-an-id"
            every { videos } returns emptyList()
        }
        val metas = mockk<com.nuvio.tv.domain.repository.MetaRepository>()
        every { metas.getCachedMeta("series", "kitsu:46474") } returns meta
        val simkl = mockk<com.nuvio.tv.data.repository.SimklIdResolver>()
        coEvery { simkl.resolveIds("kitsu", "46474", any()) } returns
            com.nuvio.tv.data.repository.SimklIdResolver.ResolvedIds(simklId = 7, type = "anime", imdb = "tt999")
        coEvery { simkl.getEpisodeMapping(7, "anime") } returns listOf(
            com.nuvio.tv.data.repository.SimklIdResolver.EpisodeMapping(animeEpisode = 5, tvdbSeason = 1, tvdbEpisode = 5))
        BuiltInUsenetService(client, mockk(), mockk(), metas = metas, simkl = simkl)
            .search(config, "series", "kitsu:46474:5", 1, 5).toList()
        coVerify { client.search(indexer, match { it.imdbId == "tt999" && it.season == 1 && it.episode == 5 }, any(), any()) }
    }

    @Test fun `a catalog episode nothing maps is not searched with the catalog's own numbers`() = runTest {
        val indexer = UsenetIndexer(id = "geek", name = "Geek", apiUrl = "https://geek.test/api")
        val config = UsenetSourceConfiguration(enabled = true,
            providers = listOf(UsenetProvider(name = "News", host = "news.test")), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        // The real client sends nothing for a request without an ID or a title.
        coEvery { client.search(indexer, any(), any(), any()) } returns emptyList()
        val tmdb = mockk<TmdbService>()
        coEvery { tmdb.ensureTmdbId(any(), any(), any()) } returns null
        val meta = mockk<com.nuvio.tv.domain.model.Meta> {
            every { imdbId } returns "tt22248376"
            every { videos } returns emptyList()
        }
        val metas = mockk<com.nuvio.tv.domain.repository.MetaRepository>()
        every { metas.getCachedMeta("series", "kitsu:49240") } returns meta
        val simkl = mockk<com.nuvio.tv.data.repository.SimklIdResolver>()
        coEvery { simkl.resolveIds("kitsu", "49240", any()) } returns
            com.nuvio.tv.data.repository.SimklIdResolver.ResolvedIds(simklId = 7, type = "anime", imdb = "tt22248376")
        coEvery { simkl.getEpisodeMapping(7, "anime") } returns emptyList()
        val results = BuiltInUsenetService(client, tmdb, mockk(), metas = metas, simkl = simkl)
            .search(config, "series", "kitsu:49240:1", 1, 1, contentId = "kitsu:49240").toList()
        assertTrue(results.mapNotNull { it.group }.isEmpty())
        coVerify(exactly = 0) { client.search(any(), match { it.imdbId != null || it.tvdbId != null || it.tmdbId != null }, any(), any()) }
        coVerify(exactly = 0) { client.search(any(), match { it.title != null }, any(), any()) }
    }
}
