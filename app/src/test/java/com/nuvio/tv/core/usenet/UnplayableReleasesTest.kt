package com.nuvio.tv.core.usenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnplayableReleasesTest {
    private class MemoryStorage : UnplayableReleaseStorage {
        var value: String? = null
        override var generation = 0
        override fun load() = value
        override fun save(value: String) { this.value = value }
        fun clear() { value = null; generation += 1 }
    }

    private val provider = listOf("nntps://user:secret@news.example.test:563/20?priority=0")
    private var clock = 1_000L
    private val storage = MemoryStorage()
    private val releases = UnplayableReleases(storage) { clock }

    private fun record(url: String = "https://a.test/getnzb/1.nzb&r=key", title: String = "Movie.2026.2160p-GRP",
        size: Long = 30_000_000_000, kind: String = "missing-article") = releases.record(url, title, size, provider, kind)

    @Test fun `a release missing articles is remembered without storing secrets`() {
        record()
        assertTrue(releases.contains("https://a.test/getnzb/1.nzb&r=key", "Movie.2026.2160p-GRP", 30_000_000_000, provider))
        assertFalse(storage.value!!.contains("secret"))
        assertFalse(storage.value!!.contains("key"))
        assertFalse(storage.value!!.contains("Movie"))
    }

    @Test fun `the same upload from another indexer counts, another size does not`() {
        record(size = 25_991_001_440)
        // One indexer rounds sizes to thousands of bytes, another gives them exactly.
        assertTrue(releases.contains("https://b.test/api?t=get&id=9", "movie.2026.2160p-grp", 25_991_001_000, provider))
        assertTrue(releases.contains("https://b.test/api?t=get&id=9", "Movie.2026.2160p-GRP", 25_991_999_999, provider))
        assertFalse(releases.contains("https://b.test/api?t=get&id=9", "Movie.2026.2160p-GRP", 22_087_105_000, provider))
    }

    @Test fun `failures that other input or time can fix are not remembered`() {
        for (kind in listOf("provider-authentication", "provider-quota", "temporary-read-error", "")) record(kind = kind)
        assertFalse(releases.contains("https://a.test/getnzb/1.nzb&r=key", "Movie.2026.2160p-GRP", 30_000_000_000, provider))
    }

    @Test fun `other providers may still have the articles`() {
        record()
        val moreProviders = provider + "nntps://u:p@backup.example.test:563/8"
        assertFalse(releases.contains("https://a.test/getnzb/1.nzb&r=key", null, null, moreProviders))
        // Credentials, connections and priority do not change what a provider holds.
        val sameAddress = listOf("nntps://other:pass@news.example.test:563/50?priority=2")
        assertTrue(releases.contains("https://a.test/getnzb/1.nzb&r=key", null, null, sameAddress))
    }

    @Test fun `a blank release is unplayable with any provider`() {
        record(kind = "blank-content")
        val otherProviders = listOf("nntps://u:p@backup.example.test:563/8")
        assertTrue(releases.contains("https://a.test/getnzb/1.nzb&r=key", null, null, otherProviders))
    }

    @Test fun `entries expire after 30 days`() {
        record()
        clock += 31L * 24 * 3600 * 1000
        assertFalse(releases.contains("https://a.test/getnzb/1.nzb&r=key", null, null, provider))
    }

    @Test fun `an account reset drops what was already loaded`() {
        record()
        storage.clear()
        assertFalse(releases.contains("https://a.test/getnzb/1.nzb&r=key", null, null, provider))
    }

    @Test fun `listed results put unplayable releases last with the label`() {
        fun stream(title: String) = com.nuvio.tv.domain.model.Stream(name = "Usenet • A", title = title, description = null,
            url = null, ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
            addonName = BuiltInUsenetService.GROUP_NAME, addonLogo = null, nzbUrl = "https://a.test/$title", servers = provider)
        record(url = "https://a.test/dead")
        val group = com.nuvio.tv.domain.model.AddonStreams(BuiltInUsenetService.GROUP_NAME, null,
            listOf(stream("dead"), stream("good")))
        val arranged = BuiltInUsenetService.arrangeOutcomes(listOf(group), releases::contains).single().streams
        assertEquals(listOf("good", "dead"), arranged.map { it.title })
        assertEquals(listOf(BuiltInUsenetService.UNAVAILABLE_LABEL), arranged.last().badges.map { it.name })
        // Arranging again changes nothing.
        assertEquals(arranged, BuiltInUsenetService.arrangeOutcomes(listOf(group.copy(streams = arranged)), releases::contains).single().streams)
    }

    @Test fun `a release that played is available until it fails or two weeks pass`() {
        val url = "https://a.test/getnzb/1.nzb&r=key"
        releases.recordPlayed(url, "Show.S01.1080p-GRP", 30_000_000_000, provider)
        assertTrue(releases.played(url, null, null, provider))
        assertFalse(releases.contains(url, null, null, provider))
        // A later failure wins over the earlier success.
        clock += 1000
        releases.record(url, "Show.S01.1080p-GRP", 30_000_000_000, provider, "missing-article")
        assertTrue(releases.contains(url, null, null, provider))
        assertFalse(releases.played(url, null, null, provider))
        // A newer success wins again, and expires after two weeks.
        clock += 1000
        releases.recordPlayed(url, "Show.S01.1080p-GRP", 30_000_000_000, provider)
        assertTrue(releases.played(url, null, null, provider))
        clock += 15L * 24 * 3600 * 1000
        assertFalse(releases.played(url, null, null, provider))
    }

    @Test fun `playing again within a day writes nothing`() {
        releases.recordPlayed("https://a.test/1", "Movie.2026.2160p-GRP", 30_000_000_000, provider)
        val saved = storage.value
        clock += 3600_000
        storage.value = null
        releases.recordPlayed("https://a.test/1", "Movie.2026.2160p-GRP", 30_000_000_000, provider)
        assertEquals(null, storage.value)
        assertTrue(saved != null)
    }

    @Test fun `available releases go first unless turned off, unplayable ones last`() {
        fun stream(title: String) = com.nuvio.tv.domain.model.Stream(name = "Usenet • A", title = title, description = null,
            url = null, ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
            addonName = BuiltInUsenetService.GROUP_NAME, addonLogo = null, nzbUrl = "https://a.test/$title", servers = provider)
        record(url = "https://a.test/dead")
        releases.recordPlayed("https://a.test/good", null, null, provider)
        val group = com.nuvio.tv.domain.model.AddonStreams(BuiltInUsenetService.GROUP_NAME, null,
            listOf(stream("dead"), stream("untried"), stream("good")))
        fun arranged(availableFirst: Boolean) = BuiltInUsenetService.arrangeOutcomes(listOf(group), releases::contains,
            releases::played, availableFirst).single().streams
        assertEquals(listOf("good", "untried", "dead"), arranged(true).map { it.title })
        assertEquals(listOf("untried", "good", "dead"), arranged(false).map { it.title })
        assertEquals(listOf(BuiltInUsenetService.AVAILABLE_LABEL), arranged(true).first().badges.map { it.name })
        assertEquals(listOf(BuiltInUsenetService.UNAVAILABLE_LABEL), arranged(true).last().badges.map { it.name })
    }

    @Test fun `entries saved before keep matching`() {
        fun hex(value: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val url = "https://a.test/getnzb/1.nzb&r=key"
        storage.value = """{"u:${hex(url)}":{"kind":"missing-article","at":$clock,"providers":"${hex("nntps://news.example.test:563")}"}}"""
        storage.generation += 1
        assertTrue(releases.contains(url, null, null, provider))
    }

    @Test fun `a season pack is matched by its title whatever size it lists`() {
        record(url = "https://a.test/pack", title = "Show.S01.1080p-GRP", size = 9_000_000_000)
        // Another indexer's copy, listed with an episode's share of the size.
        assertTrue(releases.contains("https://b.test/pack", "Show.S01.1080p-GRP", 1_500_000_000, provider))
        assertFalse(releases.contains("https://b.test/pack", "Show.S02.1080p-GRP", 1_500_000_000, provider))
    }
}
