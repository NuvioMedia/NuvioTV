package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
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

    @Test fun `an upload's episodes share a binge group, so the next episode keeps to it`() {
        fun group(title: String) = BuiltInUsenetService.bingeGroup(title)
        val e01 = group("Silo.S01E01.Freedom.Day.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-NTb")
        assertNotNull(e01)
        assertEquals(e01, group("Silo.S01E02.Holstons.Pick.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-NTb"))
        assertNotEquals(e01, group("Silo.S01E02.Holstons.Pick.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-FLUX"))
        assertNotEquals(e01, group("Silo.S02E01.The.Engineer.2160p.ATVp.WEB-DL.DDP5.1.HDR.H.265-NTb"))
        // A season pack plays every episode: it is its own group. Movies have none.
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
        assertNull(remux.behaviorHints)
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
        assertEquals("9.00 GB whole season (~1.00 GB per episode) • A", stream.description)
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
        assertEquals("34.00 GB whole pack (~1.00 GB per episode) • A", stream.description)
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
        assertEquals(listOf("4K • Usenet • A", "720p • Usenet • A", "Usenet • A"), names)
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
        assertEquals("2.00 GB • A", description(config.copy(showReleaseNames = false)))
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
        assertEquals(listOf("1080p • Usenet • A"), streams.map { it.name })
        coVerify { client.search(shared, any(), any()) }
        coVerify(exactly = 0) { client.capabilities(backup) }

        // A failed or empty higher priority falls through to the next one.
        coEvery { client.search(first, any(), any()) } throws IllegalStateException("down")
        val fallback = service.search(config, "movie", "tt1", null, null).toList()
        assertEquals(listOf("1080p • Usenet • C"), fallback.mapNotNull { it.group }.last().streams.map { it.name })
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
        assertEquals(listOf("1080p • Usenet • Ids"), results.mapNotNull { it.group }.last().streams.map { it.name })
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
        assertEquals(listOf("1080p • Usenet • Tvdb"), results.mapNotNull { it.group }.last().streams.map { it.name })
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
        coEvery { tmdbApi.getMovieDetails(10, any(), any()) } returns Response.success(mockk<TmdbDetailsResponse>(relaxed = true) {
            every { title } returns "The Movie"
            every { releaseDate } returns "2020-05-01"
        })
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
        fun details(original: String) = Response.success(mockk<TmdbDetailsResponse>(relaxed = true) {
            every { title } returns "Money Heist"
            every { originalTitle } returns original
            every { releaseDate } returns "2017-05-02"
        })
        coEvery { tmdbApi.getMovieDetails(10, any(), any()) } returns details("La casa de papel")
        val service = BuiltInUsenetService(client, tmdb, tmdbApi)
        val streams = service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams
        assertEquals(listOf("La.Casa.de.Papel.2017.1080p"), streams.map { it.title })
        // The English title is still accepted when the query used the original one.
        coVerify { client.search(indexer, match { it.imdbId == null && it.title == "Money Heist" }, any()) }
        coVerify { client.search(indexer, match { it.title == "La casa de papel" && it.originalTitle == "Money Heist" }, any()) }

        // Indexers search Latin text, so an original title in another script is not searched.
        coEvery { tmdbApi.getMovieDetails(10, any(), any()) } returns details("ペーパー・ハウス")
        assertTrue(service.search(config, "movie", "tt1", null, null).toList().none { it.group != null })
        coVerify(exactly = 0) { client.search(indexer, match { it.title == "ペーパー・ハウス" }, any()) }
    }
}
