package com.nuvio.tv.core.usenet

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
        record()
        assertTrue(releases.contains("https://b.test/api?t=get&id=9", "movie.2026.2160p-grp", 30_000_000_000, provider))
        assertFalse(releases.contains("https://b.test/api?t=get&id=9", "Movie.2026.2160p-GRP", 29_000_000_000, provider))
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
}
