package com.nuvio.tv.ui.screens.home

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Localization regression test: Cinemeta (the bundled default addon) serves catalog names
 * ("Popular", "New", "Featured", ...) with no language support of its own, so the Home screen
 * substitutes a localized string for its 5 well-known catalog ids specifically - other addons'
 * catalog names must be left completely untouched since arbitrary third-party text can't be
 * safely translated.
 */
class CinemetaCatalogLocalizationTest {

    private val context = mockk<Context>(relaxed = true) {
        every { getString(R.string.home_catalog_popular) } returns "Popolari"
        every { getString(R.string.home_catalog_new) } returns "Novità"
        every { getString(R.string.home_catalog_featured) } returns "In evidenza"
        every { getString(R.string.home_catalog_last_videos) } returns "Ultimi episodi"
        every { getString(R.string.home_catalog_calendar_videos) } returns "Calendario episodi"
    }

    private fun cinemetaAddon() = Addon(
        id = "com.linvo.cinemeta",
        name = "Cinemeta",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://v3-cinemeta.strem.io",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        resources = emptyList()
    )

    private fun thirdPartyAddon() = cinemetaAddon().copy(
        id = "com.example.other",
        baseUrl = "https://example.com/some-addon"
    )

    private fun catalog(id: String, name: String, type: ContentType = ContentType.MOVIE) = CatalogDescriptor(
        type = type,
        id = id,
        name = name
    )

    @Test
    fun `Cinemeta top catalog is localized to Popolari for Italian`() {
        val result = localizedCinemetaCatalogNameOrNull(context, cinemetaAddon(), catalog("top", "Popular"))
        assertEquals("Popolari", result)
    }

    @Test
    fun `Cinemeta year catalog is localized to Novita for Italian`() {
        val result = localizedCinemetaCatalogNameOrNull(context, cinemetaAddon(), catalog("year", "New"))
        assertEquals("Novità", result)
    }

    @Test
    fun `Cinemeta imdbRating catalog is localized to In evidenza for Italian`() {
        val result = localizedCinemetaCatalogNameOrNull(context, cinemetaAddon(), catalog("imdbRating", "Featured"))
        assertEquals("In evidenza", result)
    }

    @Test
    fun `Cinemeta last-videos and calendar-videos catalogs are localized`() {
        assertEquals(
            "Ultimi episodi",
            localizedCinemetaCatalogNameOrNull(context, cinemetaAddon(), catalog("last-videos", "Last videos", ContentType.SERIES))
        )
        assertEquals(
            "Calendario episodi",
            localizedCinemetaCatalogNameOrNull(context, cinemetaAddon(), catalog("calendar-videos", "Calendar videos", ContentType.SERIES))
        )
    }

    @Test
    fun `unknown Cinemeta catalog id falls through to null (caller keeps raw name)`() {
        val result = localizedCinemetaCatalogNameOrNull(context, cinemetaAddon(), catalog("unknown-id", "Something"))
        assertNull(result)
    }

    @Test
    fun `third-party addon catalogs are never localized even with a matching id`() {
        // Same catalog id ("top") but from a different addon - must NOT be translated, since we
        // can't know a third-party addon's "top" catalog actually means the same thing.
        val result = localizedCinemetaCatalogNameOrNull(context, thirdPartyAddon(), catalog("top", "Top Picks"))
        assertNull(result)
    }
}
