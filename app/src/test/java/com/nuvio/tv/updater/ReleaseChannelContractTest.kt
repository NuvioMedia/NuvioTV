package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseChannelContractTest {
    @Test fun `shared release policy agrees with default channel and selector`() {
        val json = checkNotNull(javaClass.getResource("/updater/release-channel-cases.json")).readText()
        val rows = Moshi.Builder().build().adapter(List::class.java).fromJson(json)!!
        for (raw in rows) {
            val row = raw as Map<*, *>
            val version = row["version"] as String
            val stable = row["stable"] as Boolean
            assertEquals(version, !stable, VersionUtils.isPrerelease(version))
            assertEquals(version, if (stable) UpdateChannel.STABLE else UpdateChannel.BETA,
                UpdateChannel.defaultForVersion(version))
            val selected = ReleaseSelector.eligibleReleases(
                listOf(GitHubReleaseDto(tagName = version)), UpdateChannel.STABLE)
            assertEquals(version, stable, selected.isNotEmpty())
        }
    }

    @Test fun `shared invalid release versions are rejected`() {
        val json = checkNotNull(javaClass.getResource("/updater/release-channel-invalid.json")).readText()
        val rows = Moshi.Builder().build().adapter(List::class.java).fromJson(json)!!
        for (raw in rows) assertNull(raw as String, VersionUtils.parse(raw))
    }

    @Test fun `fork revisions retain numeric ordering`() {
        assertTrue(VersionUtils.isRemoteNewer("0.9.0-brusus.14", "0.9.0-brusus.13"))
        assertTrue(VersionUtils.isRemoteNewer("0.8.12-brusus.10", "0.8.12-brusus.9"))
    }

    @Test fun `github and title prerelease markers override stable fork suffix`() {
        val releases = listOf(
            GitHubReleaseDto(tagName = "0.9.0-brusus.14", prerelease = true),
            GitHubReleaseDto(tagName = "0.9.0-brusus.15", name = "Nuvio Beta2"),
            GitHubReleaseDto(tagName = "0.9.0-brusus.16", name = "Nuvio RC1"),
            GitHubReleaseDto(tagName = "0.9.0-brusus.17", name = "Release Candidate 0.9.0"),
            GitHubReleaseDto(tagName = "0.9.0-brusus.13", name = "NuvioTV fork")
        )
        assertEquals(listOf("0.9.0-brusus.13"),
            ReleaseSelector.eligibleReleases(releases, UpdateChannel.STABLE).map { it.tagName })
        assertEquals(5, ReleaseSelector.eligibleReleases(releases, UpdateChannel.BETA).size)
    }
}
