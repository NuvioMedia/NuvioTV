package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeCatalogLoadSignatureTest {
    @Test
    fun changesWhenAddonConfigVersionChanges() {
        val first = buildHomeCatalogLoadSignature(
            addons = listOf(addon(configVersion = 1L)),
            disabledHomeCatalogKeys = emptySet()
        )
        val second = buildHomeCatalogLoadSignature(
            addons = listOf(addon(configVersion = 2L)),
            disabledHomeCatalogKeys = emptySet()
        )

        assertNotEquals(first, second)
    }

    @Test
    fun changesWhenAddonVersionChanges() {
        val first = buildHomeCatalogLoadSignature(
            addons = listOf(addon(version = "2.7.1")),
            disabledHomeCatalogKeys = emptySet()
        )
        val second = buildHomeCatalogLoadSignature(
            addons = listOf(addon(version = "2.7.2")),
            disabledHomeCatalogKeys = emptySet()
        )

        assertNotEquals(first, second)
    }

    private fun addon(
        version: String = "1.0.0",
        configVersion: Long? = null
    ): Addon {
        return Addon(
            id = "aio-addon",
            name = "AIO Addon",
            version = version,
            description = "AIO Addon",
            logo = null,
            baseUrl = "https://nuvio.file-host.net/stremio/user",
            catalogs = listOf(catalog()),
            types = listOf(ContentType.MOVIE),
            rawTypes = listOf("movie"),
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie"), idPrefixes = null)),
            configVersion = configVersion,
            timestamp = null
        )
    }

    private fun catalog(): CatalogDescriptor {
        return CatalogDescriptor(
            type = ContentType.MOVIE,
            rawType = "movie",
            id = "trakt.recommendations.movies",
            name = "Recommended (Movies)",
            showInHome = true,
            hasExplicitShowInHome = true
        )
    }
}
