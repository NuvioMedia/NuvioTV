package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRowPaginationTest {
    @Test
    fun `mergeCatalogPage advances by returned item count without changing skip step`() {
        val first = row(ids = (0 until 11).map { "a$it" }, skipStep = 50, nextSkip = 11)
        val second = row(ids = (0 until 17).map { "b$it" }, skipStep = 50, nextSkip = 28)

        val merged = first.mergeCatalogPage(second)

        assertEquals(28, merged.items.size)
        assertEquals(50, merged.skipStep)
        assertEquals(28, merged.nextSkip)
        assertEquals(1, merged.currentPage)
        assertEquals(0, merged.consecutiveDuplicatePages)
        assertTrue(merged.hasMore)
    }

    @Test
    fun `mergeCatalogPage advances duplicate pages instead of ending immediately`() {
        val current = row(ids = (0 until 45).map { "m$it" }, skipStep = 50, nextSkip = 45, currentPage = 3)
        val duplicate = row(ids = (27 until 45).map { "m$it" }, skipStep = 50, nextSkip = 63)

        val merged = current.mergeCatalogPage(duplicate)

        assertEquals(45, merged.items.size)
        assertEquals(63, merged.nextSkip)
        assertEquals(1, merged.consecutiveDuplicatePages)
        assertTrue(merged.hasMore)
    }

    private fun row(
        ids: List<String>,
        skipStep: Int,
        nextSkip: Int,
        currentPage: Int = 0
    ): CatalogRow {
        return CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://example.test",
            catalogId = "catalog",
            catalogName = "Catalog",
            type = ContentType.MOVIE,
            items = ids.map { id ->
                MetaPreview(
                    id = id,
                    type = ContentType.MOVIE,
                    name = id,
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = null,
                    imdbRating = null,
                    genres = emptyList()
                )
            },
            hasMore = true,
            currentPage = currentPage,
            supportsSkip = true,
            skipStep = skipStep,
            nextSkip = nextSkip
        )
    }
}
