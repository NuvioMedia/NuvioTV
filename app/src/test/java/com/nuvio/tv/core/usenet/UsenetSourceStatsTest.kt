package com.nuvio.tv.core.usenet

import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

internal class MemoryStatsStorage : UsenetStatsStorage {
    var value: String? = null
    var saves = 0
    val preferences = mutableMapOf<String, String>()
    override var generation = 0
    override fun load() = value
    override fun save(value: String) { this.value = value; saves++ }
    override fun clear() { value = null }
    override fun loadPreference(name: String) = preferences[name]
    override fun savePreference(name: String, value: String) { preferences[name] = value }
}

/** Statistics that save at once, on the calling thread. */
internal fun memoryStats() = UsenetSourceStats(MemoryStatsStorage(), background = { it.run() })

class UsenetSourceStatsTest {
    private val day = 24L * 3600 * 1000
    // Noon UTC, so a few hours either way stay on the same day.
    private var clock = 20_000L * day + 12 * 3600 * 1000
    private var zone: ZoneId = ZoneOffset.UTC
    private val storage = MemoryStatsStorage()
    private fun stats(storage: UsenetStatsStorage = this.storage) = UsenetSourceStats(storage, { clock }, { zone }) { it.run() }
    private val ALL = StatsContent.ALL

    @Test fun `requests and searches add up in the day and in total`() {
        val stats = stats()
        stats.indexerRequest("a", ContentKind.MOVIE, RequestOutcome.ANSWERED, 300)
        stats.indexerRequest("a", ContentKind.MOVIE, RequestOutcome.ANSWERED, 100)
        stats.indexerRequest("a", ContentKind.MOVIE, RequestOutcome.FAILED)
        stats.indexerRequest("a", ContentKind.MOVIE, RequestOutcome.LIMITED)
        stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.ANSWERED, 12)
        stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.ANSWERED, 0)
        stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.FAILED)
        stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.PAUSED)
        val expected = IndexerCounts(searches = 4, emptySearches = 1, failedSearches = 1, pausedSearches = 1, releases = 12,
            requests = 4, failedRequests = 1, limitedRequests = 1, answered = 2, responseMs = 400)
        StatsPeriod.entries.forEach { assertEquals(it.name, expected, stats.indexer("a", it, ALL)) }
        assertEquals(expected, stats.indexerToday("a", ALL))
        assertEquals(clock, stats.indexerSince("a"))
        assertNull(stats.indexer("b", StatsPeriod.ALL_TIME, ALL))
    }

    @Test fun `periods count their days and all time keeps what the days dropped`() {
        val stats = stats()
        val now = clock
        clock = now - 40 * day; stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.ANSWERED, 1)
        clock = now - 10 * day; stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.ANSWERED, 2)
        clock = now - 6 * day; stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.ANSWERED, 4)
        clock = now; stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.ANSWERED, 8)
        assertEquals(12L, stats.indexer("a", StatsPeriod.LAST_7_DAYS, ALL)?.releases)
        assertEquals(14L, stats.indexer("a", StatsPeriod.LAST_30_DAYS, ALL)?.releases)
        assertEquals(15L, stats.indexer("a", StatsPeriod.ALL_TIME, ALL)?.releases)
        assertEquals(8L, stats.indexerToday("a", ALL)?.releases)
        // Saved and loaded again: the day older than a month is gone, its count stays in the total.
        stats.flush()
        val reloaded = stats()
        assertEquals(14L, reloaded.indexer("a", StatsPeriod.LAST_30_DAYS, ALL)?.releases)
        assertEquals(15L, reloaded.indexer("a", StatsPeriod.ALL_TIME, ALL)?.releases)
        assertFalse(storage.value!!.contains("\"${20_000 - 40}\""))
        clock = now + day
        assertNull(reloaded.indexerToday("a", ALL))
    }

    @Test fun `the local day decides where an event counts`() {
        val stats = stats()
        // 23:30 UTC is already the next day two hours east.
        clock = 20_000L * day + 23 * 3600 * 1000 + 30 * 60 * 1000
        zone = ZoneOffset.ofHours(2)
        stats.indexerSearch("a", ContentKind.SHOW, SearchOutcome.ANSWERED, 1)
        zone = ZoneOffset.UTC
        assertNull(stats.indexerToday("a", ALL))
        zone = ZoneOffset.ofHours(2)
        assertEquals(1, stats.indexerToday("a", ALL)?.searches)
    }

    @Test fun `content splits movies, shows and requests that are neither`() {
        val stats = stats()
        stats.indexerRequest("a", ContentKind.MOVIE, RequestOutcome.ANSWERED, 10)
        stats.indexerRequest("a", ContentKind.SHOW, RequestOutcome.ANSWERED, 10)
        stats.indexerRequest("a", null, RequestOutcome.ANSWERED, 10)
        stats.indexerSearch("a", ContentKind.SHOW, SearchOutcome.ANSWERED, 3)
        assertEquals(3, stats.indexer("a", StatsPeriod.LAST_30_DAYS, StatsContent.ALL)?.requests)
        assertEquals(1, stats.indexer("a", StatsPeriod.LAST_30_DAYS, StatsContent.MOVIES)?.requests)
        assertEquals(1, stats.indexer("a", StatsPeriod.ALL_TIME, StatsContent.SHOWS)?.requests)
        assertEquals(1, stats.indexer("a", StatsPeriod.ALL_TIME, StatsContent.SHOWS)?.searches)
        assertEquals(0, stats.indexer("a", StatsPeriod.ALL_TIME, StatsContent.MOVIES)?.searches)
        assertEquals(1, stats.indexerToday("a", StatsContent.MOVIES)?.requests)
    }

    @Test fun `content kinds follow the search rule`() {
        assertEquals(ContentKind.MOVIE, contentKindOf("Movie"))
        listOf("series", "tv", "show").forEach { assertEquals(ContentKind.SHOW, contentKindOf(it)) }
        assertNull(contentKindOf(""))
        assertNull(contentKindOf(null))
    }

    @Test fun `downloads and playback count for the indexer and kind that listed the release`() {
        val stats = stats()
        stats.remember(listOf("https://a.test/1", "https://a.test/2"), "a", ContentKind.SHOW)
        stats.grabbed("https://a.test/1")
        stats.grabbed("https://a.test/1")
        stats.played("https://a.test/1")
        stats.unavailable("https://a.test/2")
        // A release no search listed counts for nothing.
        stats.grabbed("https://other.test/1")
        stats.played(null)
        val shows = stats.indexer("a", StatsPeriod.LAST_7_DAYS, StatsContent.SHOWS)
        assertEquals(2, shows?.grabs)
        assertEquals(1, shows?.played)
        assertEquals(1, shows?.unavailable)
        assertNull(stats.indexer("a", StatsPeriod.LAST_7_DAYS, StatsContent.MOVIES))
        assertEquals(2, stats.indexerToday("a", ALL)?.grabs)
    }

    @Test fun `a release's outcome counts once every twelve hours`() {
        val stats = stats()
        stats.remember(listOf("u"), "a", ContentKind.MOVIE)
        stats.played("u")
        stats.played("u")
        stats.unavailable("u")
        // Listed again by a later search: the last outcome is kept.
        stats.remember(listOf("u"), "a", ContentKind.MOVIE)
        stats.played("u")
        assertEquals(1, stats.indexer("a", StatsPeriod.ALL_TIME, ALL)?.played)
        assertEquals(0, stats.indexer("a", StatsPeriod.ALL_TIME, ALL)?.unavailable)
        clock += 12 * 3600 * 1000
        stats.unavailable("u")
        assertEquals(1, stats.indexer("a", StatsPeriod.ALL_TIME, ALL)?.unavailable)
    }

    @Test fun `saves are held back for five seconds and flushed`() {
        val stats = stats()
        stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.ANSWERED, 1)
        assertEquals(1, storage.saves)
        repeat(10) { stats.indexerRequest("a", ContentKind.MOVIE, RequestOutcome.ANSWERED, 5) }
        assertEquals(1, storage.saves)
        assertEquals(0, stats().indexer("a", StatsPeriod.ALL_TIME, ALL)?.requests)
        clock += UsenetSourceStats.SAVE_INTERVAL_MS
        stats.indexerRequest("a", ContentKind.MOVIE, RequestOutcome.ANSWERED, 5)
        assertEquals(2, storage.saves)
        assertEquals(11, stats().indexer("a", StatsPeriod.ALL_TIME, ALL)?.requests)
        stats.indexerRequest("a", ContentKind.MOVIE, RequestOutcome.ANSWERED, 5)
        stats.flush()
        assertEquals(3, storage.saves)
        assertEquals(12, stats().indexer("a", StatsPeriod.ALL_TIME, ALL)?.requests)
        // Nothing held back: nothing to write.
        stats.flush()
        assertEquals(3, storage.saves)
    }

    @Test fun `quiet indexers are forgotten and the number of sources is bounded`() {
        val stats = stats()
        stats.indexerSearch("old", ContentKind.MOVIE, SearchOutcome.ANSWERED, 1)
        clock += UsenetSourceStats.RETENTION_MS + 1
        (1..UsenetSourceStats.MAX_SOURCES + 1).forEach {
            clock += UsenetSourceStats.SAVE_INTERVAL_MS
            stats.indexerSearch("s$it", ContentKind.MOVIE, SearchOutcome.ANSWERED, 1)
        }
        assertNull(stats.indexer("old", StatsPeriod.ALL_TIME, ALL))
        assertNull(stats.indexer("s1", StatsPeriod.ALL_TIME, ALL))
        assertNotNull(stats.indexer("s2", StatsPeriod.ALL_TIME, ALL))
        assertNotNull(stats.indexer("s${UsenetSourceStats.MAX_SOURCES + 1}", StatsPeriod.ALL_TIME, ALL))
    }

    @Test fun `filters default, persist, and survive a reset but not an account reset`() {
        val stats = stats()
        stats.load()
        assertEquals(StatsPeriod.LAST_30_DAYS, stats.period.value)
        assertEquals(StatsContent.ALL, stats.content.value)
        stats.setPeriod(StatsPeriod.ALL_TIME)
        stats.setContent(StatsContent.SHOWS)
        stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.ANSWERED, 1)
        val reloaded = stats().also { it.load() }
        assertEquals(StatsPeriod.ALL_TIME, reloaded.period.value)
        assertEquals(StatsContent.SHOWS, reloaded.content.value)
        reloaded.reset()
        assertNull(reloaded.indexer("a", StatsPeriod.ALL_TIME, ALL))
        assertNull(storage.value)
        assertEquals(StatsPeriod.ALL_TIME, reloaded.period.value)
        assertNull(stats().indexer("a", StatsPeriod.ALL_TIME, ALL))
        // An unknown stored value falls back to the default.
        storage.preferences["period"] = "LAST_YEAR"
        assertEquals(StatsPeriod.LAST_30_DAYS, stats().also { it.load() }.period.value)
        // An account reset clears the storage from outside; a loaded copy is dropped.
        reloaded.indexerSearch("b", ContentKind.MOVIE, SearchOutcome.ANSWERED, 1)
        storage.value = null
        storage.preferences.clear()
        storage.generation += 1
        assertNull(reloaded.indexer("b", StatsPeriod.ALL_TIME, ALL))
        assertEquals(StatsContent.ALL, reloaded.content.value)
    }

    @Test fun `a reset writes after any save already queued`() {
        val queued = mutableListOf<Runnable>()
        val stats = UsenetSourceStats(storage, { clock }, { zone }) { queued += it }
        stats.indexerSearch("a", ContentKind.MOVIE, SearchOutcome.ANSWERED, 1)
        stats.reset()
        queued.forEach { it.run() }
        assertNull(storage.value)
    }

    private val primary = UsenetProvider(name = "Primary", host = "News.Example.com", username = "me", password = "one")
    private val backup = UsenetProvider(name = "Backup", host = "backup.test", port = 443, username = "", password = "")

    @Test fun `a closed session's reports count for the servers they name`() {
        val stats = stats()
        val servers = listOf(backup.serverUrl(), primary.serverUrl())
        // The engine lists providers by priority; each names its place in the session's servers.
        stats.providerSession(servers, ContentKind.SHOW, """{"providers":[
            {"server":1,"articles":5,"bytes":5120,"missing":1,"errors":0,"ttfbMs":42.6},
            {"server":0,"articles":1,"bytes":1024,"missing":0,"errors":2}]}""")
        stats.providerSession(servers, ContentKind.MOVIE, """{"providers":[{"server":1,"articles":3,"bytes":3,"ttfbMs":10}]}""")
        val key = primary.statsKey()!!
        assertEquals(ProviderCounts(sessions = 1, articles = 5, bytes = 5120, missing = 1, ttfbMs = 43, ttfbSessions = 1),
            stats.provider(key, StatsPeriod.LAST_7_DAYS, StatsContent.SHOWS))
        assertEquals(ProviderCounts(sessions = 2, articles = 8, bytes = 5123, missing = 1, ttfbMs = 53, ttfbSessions = 2),
            stats.provider(key, StatsPeriod.ALL_TIME, ALL))
        assertEquals(ProviderCounts(sessions = 1, articles = 1, bytes = 1024, errors = 2),
            stats.provider(backup.statsKey()!!, StatsPeriod.LAST_30_DAYS, ALL))
        assertEquals(clock, stats.providerSince(key))
    }

    @Test fun `an open refused for missing articles failed to open for the providers that lacked them`() {
        val stats = stats()
        val servers = listOf(primary.serverUrl(), backup.serverUrl())
        // The primary lacked articles; the backup served some but lacked none of them.
        stats.providerSession(servers, ContentKind.SHOW, """{"providers":[
            {"server":0,"articles":2,"missing":5},{"server":1,"articles":3}]}""", refused = true, failure = "missing-article")
        stats.providerSession(servers, ContentKind.SHOW, """{"providers":[{"server":0,"articles":40}]}""")
        assertEquals(ProviderCounts(sessions = 2, failedOpens = 1, articles = 42, missing = 5),
            stats.provider(primary.statsKey()!!, StatsPeriod.ALL_TIME, StatsContent.SHOWS))
        assertEquals(ProviderCounts(sessions = 1, articles = 3),
            stats.provider(backup.statsKey()!!, StatsPeriod.ALL_TIME, StatsContent.SHOWS))
        // Damaged articles and a passed hole limit are the provider's too.
        stats.providerSession(servers, ContentKind.SHOW, """{"providers":[{"server":0,"errors":1}]}""", refused = true, failure = "invalid-article")
        stats.providerSession(servers, ContentKind.SHOW, """{"providers":[{"server":0,"missing":3}]}""", refused = true, failure = "hole-limit")
        assertEquals(3, stats.provider(primary.statsKey()!!, StatsPeriod.ALL_TIME, StatsContent.SHOWS)?.failedOpens)
    }

    @Test fun `an open refused for the release is no provider's failure`() {
        val stats = stats()
        val servers = listOf(primary.serverUrl())
        // A password, a compressed archive or a blank release: the articles served still count.
        listOf(null, "blank-content", "provider").forEach {
            stats.providerSession(servers, ContentKind.MOVIE, """{"providers":[{"server":0,"articles":4,"errors":1}]}""",
                refused = true, failure = it)
        }
        // Refused before the provider was asked for anything, such as for an NZB too large to read.
        stats.providerSession(servers, ContentKind.MOVIE, """{"providers":[{"server":0,"articles":0}]}""", refused = true)
        assertEquals(ProviderCounts(sessions = 3, articles = 12, errors = 3),
            stats.provider(primary.statsKey()!!, StatsPeriod.ALL_TIME, StatsContent.MOVIES))
    }

    @Test fun `unreadable or foreign reports add nothing`() {
        val stats = stats()
        val servers = listOf(primary.serverUrl())
        listOf("", "not json", """{"providers":[{"server":3,"articles":9}]}""", """{"other":1}""").forEach {
            stats.providerSession(servers, ContentKind.MOVIE, it)
        }
        assertNull(stats.provider(primary.statsKey()!!, StatsPeriod.ALL_TIME, ALL))
        assertEquals(0, stats.revision.value)
    }

    @Test fun `one account listed twice in a session counts once`() {
        val stats = stats()
        // Another password does not make another account.
        val servers = listOf(primary.serverUrl(), primary.copy(password = "two", priority = 2).serverUrl())
        stats.providerSession(servers, ContentKind.MOVIE, """{"providers":[
            {"server":0,"articles":2,"ttfbMs":20},{"server":1,"articles":1,"ttfbMs":90}]}""")
        assertEquals(ProviderCounts(sessions = 1, articles = 3, ttfbMs = 20, ttfbSessions = 1),
            stats.provider(primary.statsKey()!!, StatsPeriod.ALL_TIME, ALL))
    }

    @Test fun `a provider is the same account whatever its password, connections or priority`() {
        val key = primary.statsKey()
        assertNotNull(key)
        assertEquals(key, primary.copy(password = "other", connections = 3, priority = 2, tls = true).statsKey())
        assertEquals(key, providerStatsKey("nntps://me:secret@news.example.com:563/50?priority=1"))
        assertNotEquals(key, primary.copy(username = "you").statsKey())
        assertNotEquals(key, primary.copy(port = 119).statsKey())
        // Encoded usernames and IPv6 addresses give the same key either way.
        val encoded = primary.copy(username = "a b+c@d")
        assertEquals(encoded.statsKey(), providerStatsKey(encoded.serverUrl()))
        assertNotEquals(encoded.statsKey(), primary.copy(username = "a b c@d").statsKey())
        val ipv6 = primary.copy(host = "2001:db8::1")
        assertNotNull(ipv6.statsKey())
        assertEquals(ipv6.statsKey(), ipv6.copy(host = "[2001:db8::1]").statsKey())
        assertNull(providerStatsKey("not a url"))
        assertNull(providerStatsKey(null))
    }

    @Test fun `a reset clears providers too`() {
        val stats = stats()
        stats.providerSession(listOf(primary.serverUrl()), ContentKind.MOVIE, """{"providers":[{"server":0,"articles":2}]}""")
        stats.reset()
        assertNull(stats.provider(primary.statsKey()!!, StatsPeriod.ALL_TIME, ALL))
    }

    @Test fun `unreadable saved counts start empty`() {
        storage.value = "not json"
        assertNull(stats().indexer("a", StatsPeriod.ALL_TIME, ALL))
    }
}
