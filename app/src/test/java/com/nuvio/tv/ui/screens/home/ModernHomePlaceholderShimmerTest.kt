package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.ui.util.StableList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomePlaceholderShimmerTest {

    @Test
    fun `a loading row of placeholder cards draws the shimmer`() {
        assertTrue(showsPlaceholderShimmer(isLoading = true, firstItemImageUrl = PLACEHOLDER_IMAGE_URL))
    }

    @Test
    fun `a loaded row draws no shimmer even while its cards are still placeholders`() {
        assertFalse(showsPlaceholderShimmer(isLoading = false, firstItemImageUrl = PLACEHOLDER_IMAGE_URL))
    }

    @Test
    fun `a loading row with real posters draws no shimmer`() {
        assertFalse(showsPlaceholderShimmer(isLoading = true, firstItemImageUrl = "https://example.test/poster.jpg"))
    }

    @Test
    fun `an empty row draws no shimmer`() {
        assertFalse(showsPlaceholderShimmer(isLoading = true, firstItemImageUrl = null))
    }

    @Test
    fun `a row still loading placeholders drives the shared shimmer`() {
        assertTrue(row(key = "loading", isLoading = true, imageUrl = PLACEHOLDER_IMAGE_URL).showsPlaceholderShimmer())
    }

    @Test
    fun `a loaded row does not drive the shared shimmer`() {
        assertFalse(row(key = "movies", isLoading = false, imageUrl = "https://example.test/a.jpg").showsPlaceholderShimmer())
    }

    @Test
    fun `a row with no items does not drive the shared shimmer`() {
        assertFalse(emptyRow(key = "empty", isLoading = true).showsPlaceholderShimmer())
    }

    private fun emptyRow(key: String, isLoading: Boolean): HeroCarouselRow =
        HeroCarouselRow(
            key = key,
            title = key,
            globalRowIndex = 0,
            items = StableList(emptyList()),
            isLoading = isLoading
        )

    private fun row(key: String, isLoading: Boolean, imageUrl: String?): HeroCarouselRow =
        HeroCarouselRow(
            key = key,
            title = key,
            globalRowIndex = 0,
            items = StableList(listOf(item(key = key, imageUrl = imageUrl))),
            isLoading = isLoading
        )

    private fun item(key: String, imageUrl: String?): ModernCarouselItem =
        ModernCarouselItem(
            key = key,
            title = key,
            subtitle = null,
            imageUrl = imageUrl,
            heroPreview = HeroPreview(
                title = key,
                logo = null,
                description = null,
                contentTypeText = null,
                yearText = null,
                imdbText = null,
                genres = StableList(emptyList()),
                poster = imageUrl,
                backdrop = null,
                imageUrl = imageUrl
            ),
            payload = ModernPayload.Catalog(
                focusKey = key,
                itemId = key,
                itemType = "movie",
                addonBaseUrl = "https://example.test/manifest.json",
                trailerTitle = key,
                trailerReleaseInfo = null,
                trailerApiType = "movie"
            )
        )
}
