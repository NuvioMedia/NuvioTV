package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for #2421: with Landscape Posters enabled, the first visible cards
 * stayed blank after shimmer placeholders were replaced by real catalog items.
 *
 * Root cause: LazyRow reuses index-based keys, so composition state froze
 * `placeholder://empty` as the landscape backdrop and never adopted the real URL.
 */
class LandscapePlaceholderImageUrlTest {

    @Test
    fun `placeholder scheme is detected`() {
        assertTrue(isPlaceholderImageUrl("placeholder://empty"))
        assertTrue(isPlaceholderImageUrl("placeholder://shimmer"))
        assertFalse(isPlaceholderImageUrl(null))
        assertFalse(isPlaceholderImageUrl(""))
        assertFalse(isPlaceholderImageUrl("https://cdn.example/poster.jpg"))
    }

    @Test
    fun `realImageUrl strips placeholders and blanks`() {
        assertNull(realImageUrl(null))
        assertNull(realImageUrl("  "))
        assertNull(realImageUrl("placeholder://empty"))
        assertEquals(
            "https://cdn.example/bg.jpg",
            realImageUrl("  https://cdn.example/bg.jpg  "),
        )
    }

    @Test
    fun `firstRealImageUrl skips placeholder then picks poster`() {
        assertEquals(
            "https://cdn.example/poster.jpg",
            firstRealImageUrl(
                "placeholder://empty",
                null,
                "https://cdn.example/poster.jpg",
            ),
        )
        assertNull(firstRealImageUrl("placeholder://empty", null, "  "))
    }

    @Test
    fun `buildCatalogItem does not freeze placeholder backdrop`() {
        val row = sampleRow()
        val placeholderItem = sampleMeta(
            id = "__placeholder_top_0",
            poster = "placeholder://empty",
            background = null,
        )

        val built = buildCatalogItem(
            item = placeholderItem,
            row = row,
            useLandscapePosters = true,
            occurrence = 0,
        )

        assertNull(built.heroPreview.frozenBackdropUrl)
        assertNull(built.imageUrl)
        assertNull(realImageUrl(built.heroPreview.backdrop))
    }

    @Test
    fun `buildCatalogItem ignores carried placeholder when real art arrives`() {
        val row = sampleRow()
        val stickyPlaceholder = buildCatalogItem(
            item = sampleMeta(
                id = "old",
                poster = "placeholder://empty",
                background = "placeholder://empty",
            ),
            row = row,
            useLandscapePosters = true,
            occurrence = 0,
        ).let { item ->
            // Simulate a corrupt cache entry that somehow froze a placeholder.
            item.copy(
                heroPreview = item.heroPreview.copy(
                    frozenBackdropUrl = "placeholder://empty",
                    frozenLogoUrl = "placeholder://empty",
                ),
            )
        }

        val real = buildCatalogItem(
            item = sampleMeta(
                id = "tt123",
                poster = "https://cdn.example/poster.jpg",
                background = "https://cdn.example/backdrop.jpg",
                logo = "https://cdn.example/logo.png",
            ),
            row = row,
            useLandscapePosters = true,
            occurrence = 0,
            previousCachedItem = stickyPlaceholder,
        )

        assertEquals("https://cdn.example/backdrop.jpg", real.heroPreview.frozenBackdropUrl)
        assertEquals("https://cdn.example/logo.png", real.heroPreview.frozenLogoUrl)
        assertEquals("https://cdn.example/backdrop.jpg", real.imageUrl)
    }

    @Test
    fun `buildCatalogItem landscape freezes first real backdrop and keeps it over later enrichment`() {
        val row = sampleRow()
        val first = buildCatalogItem(
            item = sampleMeta(
                id = "tt123",
                poster = "https://cdn.example/poster.jpg",
                background = "https://cdn.example/backdrop-addon.jpg",
            ),
            row = row,
            useLandscapePosters = true,
            occurrence = 0,
        )
        val enriched = buildCatalogItem(
            item = sampleMeta(
                id = "tt123",
                poster = "https://cdn.example/poster.jpg",
                background = "https://cdn.example/backdrop-tmdb.jpg",
            ),
            row = row,
            useLandscapePosters = true,
            occurrence = 0,
            previousCachedItem = first,
        )

        assertEquals("https://cdn.example/backdrop-addon.jpg", first.heroPreview.frozenBackdropUrl)
        assertEquals(
            "https://cdn.example/backdrop-addon.jpg",
            enriched.heroPreview.frozenBackdropUrl,
        )
    }

    @Test
    fun `landscape card image recovery prefers real art over sticky placeholder`() {
        // Models the compose recovery path when remember(item.key) still holds
        // a placeholder from the shimmer card that shared this LazyRow key.
        val stickyFrozen = "placeholder://empty"
        val recovered = firstRealImageUrl(
            realImageUrl(stickyFrozen),
            "https://cdn.example/backdrop.jpg",
            "https://cdn.example/poster.jpg",
        )
        assertEquals("https://cdn.example/backdrop.jpg", recovered)
    }

    private fun sampleRow(): CatalogRow = CatalogRow(
        addonId = "cinemeta",
        addonName = "Cinemeta",
        addonBaseUrl = "https://v3-cinemeta.strem.io",
        catalogId = "top",
        catalogName = "Top",
        type = ContentType.MOVIE,
        items = emptyList(),
    )

    private fun sampleMeta(
        id: String,
        poster: String?,
        background: String?,
        logo: String? = null,
    ): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = "Sample",
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = logo,
        description = null,
        releaseInfo = "2024",
        imdbRating = null,
        genres = emptyList(),
    )
}
