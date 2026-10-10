package com.nuvio.tv.core.usenet

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class NewznabProtocolTest {
    private val indexer = UsenetIndexer(id = "one", name = "Indexer", apiUrl = "https://indexer.test/api?custom=value", apiKey = "secret &+key")

    @Test fun `provider credentials are encoded once and IPv6 is preserved`() {
        val provider = UsenetProvider(name = "News", host = "2001:db8::1", username = "a@b", password = "a:+ %/", connections = 30)
        provider.validate()
        val uri = URI(provider.serverUrl())
        assertEquals("nntps", uri.scheme)
        assertEquals("a@b:a:+ %/", uri.userInfo)
        assertEquals(563, uri.port)
        assertEquals("/30", uri.path)
        assertEquals("priority=1", uri.query)
        assertFalse(provider.toString().contains(provider.password))
    }

    @Test fun `hierarchy orders sources by priority and keeps user order within a priority`() {
        val providers = listOf(3, 1, 2, 1).mapIndexed { i, p -> UsenetProvider(id = "p$i", name = "P", host = "h", priority = p) }
        val indexers = listOf(2, 1).mapIndexed { i, p -> indexer.copy(id = "i$i", priority = p) }
        val normalized = UsenetSourceConfiguration(providers = providers, indexers = indexers).normalized()
        assertEquals(listOf("p1", "p3", "p2", "p0"), normalized.providers.map { it.id })
        assertEquals(listOf("i1", "i0"), normalized.indexers.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { providers[0].copy(priority = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { indexer.copy(priority = 6).validate() }
    }

    @Test fun `episode id search keeps season zero and encoded keys`() {
        val url = NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(imdbId = "tt123", series = true, season = 0, episode = 2), NewznabCapabilities())!!
        assertEquals("123", url.queryParameter("imdbid"))
        assertEquals("0", url.queryParameter("season"))
        assertEquals("2", url.queryParameter("ep"))
        assertEquals("tvsearch", url.queryParameter("t"))
        assertEquals("secret &+key", url.queryParameter("apikey"))
        assertEquals("value", url.queryParameter("custom"))
        assertNull(NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(imdbId = "tt123", series = true), NewznabCapabilities()))
    }

    @Test fun `capabilities select tmdb searches and respect disabled search types`() {
        val caps = NewznabProtocol.capabilities("""<caps><limits max="50"/><searching><movie-search available="yes" supportedParams="q,tmdbid,year"/><tv-search available="no"/><search available="yes"/></searching></caps>""")
        val url = NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(imdbId = "tt1", tmdbId = "20"), caps)!!
        assertEquals("20", url.queryParameter("tmdbid"))
        assertEquals("50", url.queryParameter("limit"))
        assertNull(NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(imdbId = "tt1", series = true, season = 1, episode = 1), caps))
        val fallback = NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(title = "A Show", series = true, season = 2, episode = 3), caps)!!
        assertEquals("search", fallback.queryParameter("t"))
        assertEquals("A Show S02E03", fallback.queryParameter("q"))
        val episodeFallback = NewznabProtocol.searchUrl(indexer,
            UsenetSearchRequest(imdbId = "tt1", title = "A Show", series = true, season = 2, episode = 3),
            caps.copy(tvParams = setOf("imdbid", "q")))!!
        assertNull(episodeFallback.queryParameter("imdbid"))
        assertEquals("A Show S02E03", episodeFallback.queryParameter("q"))
    }

    @Test fun `namespaced rss yields playable releases and rejects non nzb enclosures`() {
        val page = NewznabProtocol.releases("""
            <rss xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/"><channel>
            <newznab:response offset="0" total="8"/>
            <item><title>Movie.2020.2160p</title><pubDate>Fri, 09 Oct 2026 12:00:00 +0000</pubDate>
            <enclosure type="application/x-nzb" url="/get?id=1&amp;apikey=secret" length="1000"/>
            <newznab:attr name="size" value="2000"/><newznab:attr name="password" value="1"/></item>
            </channel></rss>
        """.trimIndent(), indexer)
        val release = page.releases.single()
        assertEquals(8, page.total)
        assertEquals(2000L, release.size)
        assertEquals(2160, release.resolution)
        assertTrue(release.passworded)
        assertTrue(release.publishedAt > 0)
        assertEquals("https://indexer.test/get?id=1&apikey=secret", release.nzbUrl)
    }

    @Test fun `sort filters duplicates and is stable regardless of response order`() {
        val now = 1800000000000L
        val config = UsenetSourceConfiguration(indexers = listOf(indexer), maxResults = 2, minResolution = 720, maxSizeGb = 10, maxAgeDays = 30)
        val releases = listOf(
            UsenetRelease("Good.1080p", "https://test/1", 4000, now, "one"),
            UsenetRelease("Good.2160p", "https://test/2", 5000, now, "one"),
            UsenetRelease("Bad.HDCAM.1080p", "https://test/3", 6000, now),
            UsenetRelease("Old.2160p", "https://test/4", 4000, now - 40L * 86400000),
            UsenetRelease("Huge.2160p", "https://test/5", 11L * 1024 * 1024 * 1024, now),
            UsenetRelease("Locked.2160p", "https://test/6", 6000, now, passworded = true)
        )
        val sorted = NewznabProtocol.arrange(releases + releases.first(), config, now)
        assertEquals(listOf("Good.2160p", "Good.1080p"), sorted.map { it.title })
        assertEquals(sorted, NewznabProtocol.arrange(releases.reversed(), config, now))
        assertEquals("Good.1080p", NewznabProtocol.arrange(releases, config.copy(sort = UsenetSort.SMALLEST), now).first().title)
    }

    @Test fun `entity declarations are rejected without accessing external resources`() {
        assertThrows(IllegalArgumentException::class.java) {
            NewznabProtocol.releases("""<!DOCTYPE rss [<!ENTITY secret SYSTEM "file:///secret">]><rss/>""", indexer)
        }
        val error = assertThrows(IllegalStateException::class.java) {
            NewznabProtocol.capabilities("""<error code="100" description="API key secret &amp; URL"/>""")
        }
        assertEquals("Indexer authentication failed", error.message)
    }

    @Test fun `sort modes distinguish resolution size date and user indexer priority`() {
        val preferred = indexer.copy(id = "preferred")
        val config = UsenetSourceConfiguration(indexers = listOf(preferred, indexer))
        val releases = listOf(
            UsenetRelease("Sharp.2160p", "https://test/sharp", 4000, 1000, indexer.id),
            UsenetRelease("Large.1080p", "https://test/large", 8000, 2000, indexer.id),
            UsenetRelease("Recent.720p", "https://test/recent", 1000, 3000, indexer.id),
            UsenetRelease("Preferred.1080p", "https://test/preferred", 2000, 1500, preferred.id)
        )
        fun first(sort: UsenetSort) = NewznabProtocol.arrange(releases, config.copy(sort = sort)).first().title
        assertEquals("Sharp.2160p", first(UsenetSort.QUALITY))
        assertEquals("Large.1080p", first(UsenetSort.LARGEST))
        assertEquals("Recent.720p", first(UsenetSort.SMALLEST))
        assertEquals("Recent.720p", first(UsenetSort.NEWEST))
        assertEquals("Preferred.1080p", first(UsenetSort.INDEXER))
        assertEquals("Sharp.2160p", NewznabProtocol.arrange(releases,
            config.copy(sort = UsenetSort.INDEXER, indexers = config.indexers.reversed())).first().title)
    }

    @Test fun `text fallback rejects wrong title year and episode but allows season packs`() {
        val movie = UsenetSearchRequest(title = "The Movie", year = 2020)
        assertTrue(NewznabProtocol.matches(UsenetRelease("The.Movie.2020.1080p", "url"), movie, false))
        assertFalse(NewznabProtocol.matches(UsenetRelease("The.Movie.2021.1080p", "url"), movie, false))
        assertFalse(NewznabProtocol.matches(UsenetRelease("Another.Movie.2020.1080p", "url"), movie, false))
        assertTrue(NewznabProtocol.matches(UsenetRelease("The.Movie.(2020).1080p", "url"), movie, false))
        // Year-like words in the title are not the release year.
        val sequel = UsenetSearchRequest(title = "Blade Runner 2049", year = 2017)
        assertTrue(NewznabProtocol.matches(UsenetRelease("Blade.Runner.2049.2017.2160p", "url"), sequel, false))
        assertTrue(NewznabProtocol.matches(UsenetRelease("Blade.Runner.2049.1080p", "url"), sequel, false))
        assertFalse(NewznabProtocol.matches(UsenetRelease("Blade.Runner.2049.2018.1080p", "url"), sequel, false))
        assertTrue(NewznabProtocol.matches(UsenetRelease("1917.2019.1080p", "url"), UsenetSearchRequest(title = "1917", year = 2019), false))
        val series = UsenetSearchRequest(title = "A Show", series = true, season = 1, episode = 5)
        assertFalse(NewznabProtocol.matches(UsenetRelease("A.Show.S01E04.1080p", "url"), series, true))
        assertFalse(NewznabProtocol.matches(UsenetRelease("A.Show.1x04.1080p", "url"), series, true))
        assertTrue(NewznabProtocol.matches(UsenetRelease("A.Show.S01E01-E10", "url"), series, false))
        assertTrue(NewznabProtocol.matches(UsenetRelease("A.Show.S01.Complete", "url"), series, false))
        assertFalse(NewznabProtocol.matches(UsenetRelease("A.Show.S02.Complete", "url"), series, true))
    }
}
