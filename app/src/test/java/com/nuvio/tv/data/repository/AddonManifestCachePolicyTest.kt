package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonManifestCachePolicyTest {
    @Test
    fun replacesCachedManifestWhenCatalogVisibilityChangesWithoutVersionBump() {
        val cached = addon(
            version = "2.7.1",
            catalogs = listOf(catalog(showInHome = true, hasExplicitShowInHome = true))
        )
        val fresh = cached.copy(
            catalogs = listOf(catalog(showInHome = false, hasExplicitShowInHome = true))
        )

        assertTrue(shouldReplaceCachedManifest(cached, fresh))
    }

    @Test
    fun replacesCachedManifestWhenConfigVersionChangesWithoutVersionBump() {
        val cached = addon(version = "2.7.1", configVersion = 1L)
        val fresh = cached.copy(configVersion = 2L)

        assertTrue(shouldReplaceCachedManifest(cached, fresh))
    }

    @Test
    fun ignoresTimestampOnlyManifestChanges() {
        val cached = addon(version = "2.7.1", timestamp = 1L)
        val fresh = cached.copy(timestamp = 2L)

        assertFalse(shouldReplaceCachedManifest(cached, fresh))
    }

    @Test
    fun keepsIdenticalCachedManifest() {
        val cached = addon()

        assertFalse(shouldReplaceCachedManifest(cached, cached.copy()))
    }

    @Test
    fun fetchesInstalledAddonManifestWhenCacheIsStaleEvenIfCached() {
        assertTrue(shouldFetchInstalledAddonManifest(cacheStale = true, cachedManifest = addon()))
    }

    @Test
    fun usesCachedInstalledAddonManifestWhenCacheIsFresh() {
        assertFalse(shouldFetchInstalledAddonManifest(cacheStale = false, cachedManifest = addon()))
    }

    @Test
    fun fetchesInstalledAddonManifestWhenCacheIsMissing() {
        assertTrue(shouldFetchInstalledAddonManifest(cacheStale = false, cachedManifest = null))
    }

    private fun addon(
        version: String = "1.0.0",
        catalogs: List<CatalogDescriptor> = listOf(catalog()),
        configVersion: Long? = null,
        timestamp: Long? = null
    ): Addon {
        return Addon(
            id = "aio-addon",
            name = "AIO Addon",
            version = version,
            description = "AIO Addon",
            logo = null,
            baseUrl = "https://nuvio.file-host.net/stremio/user",
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE),
            rawTypes = listOf("movie"),
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie"), idPrefixes = null)),
            configVersion = configVersion,
            timestamp = timestamp
        )
    }

    private fun catalog(
        showInHome: Boolean = true,
        hasExplicitShowInHome: Boolean = true
    ): CatalogDescriptor {
        return CatalogDescriptor(
            type = ContentType.MOVIE,
            rawType = "movie",
            id = "trakt.recommendations.movies",
            name = "Recommended (Movies)",
            showInHome = showInHome,
            hasExplicitShowInHome = hasExplicitShowInHome
        )
    }
}
