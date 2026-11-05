package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestReleaseRowTest {

    private fun meta(
        id: String,
        releaseInfo: String? = null,
        logo: String? = "https://example.com/logo-$id.png"
    ): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = "Title $id",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = logo,
        description = null,
        releaseInfo = releaseInfo,
        imdbRating = null,
        genres = emptyList()
    )

    private fun item(id: String, date: LocalDate?, logo: String? = "https://example.com/logo-$id.png"): CalendarItem =
        CalendarItem(
            meta = meta(id, if (date == null) "2026" else null, logo),
            releaseDate = date
        )

    private val format: (LocalDate) -> String = { "DATE:${it}" }

    @Test
    fun emptyItemsReturnNull() {
        assertNull(buildLatestReleaseHomeRow(emptyList(), "Ultime Uscite", format))
    }

    @Test
    fun rowUsesStableSyntheticIdentity() {
        val row = buildLatestReleaseHomeRow(
            items = listOf(item("a", LocalDate.of(2026, 9, 5))),
            title = "Ultime Uscite",
            format = format
        )
        val catalog = requireNotNull(row).row
        assertEquals(LATEST_RELEASE_ADDON_ID, catalog.addonId)
        assertEquals(LATEST_RELEASE_CATALOG_ID, catalog.catalogId)
        assertEquals("Ultime Uscite", catalog.catalogName)
        assertEquals("all", catalog.apiType)
        assertTrue(!catalog.hasMore)
        assertTrue(!catalog.supportsSkip)
    }

    @Test
    fun cardsGetFormattedDateAsReleaseInfo() {
        val date = LocalDate.of(2026, 9, 5)
        val row = buildLatestReleaseHomeRow(
            items = listOf(item("a", date)),
            title = "Ultime Uscite",
            format = format
        )
        val card = requireNotNull(row).row.items.single()
        assertEquals("DATE:2026-09-05", card.releaseInfo)
        assertEquals("2026-09-05", card.released)
    }

    @Test
    fun nullDateKeepsOriginalReleaseInfo() {
        val row = buildLatestReleaseHomeRow(
            items = listOf(item("a", null)),
            title = "Ultime Uscite",
            format = format
        )
        val card = requireNotNull(row).row.items.single()
        assertEquals("2026", card.releaseInfo)
        assertNull(card.released)
    }

    @Test
    fun itemWithoutLogoIsExcluded() {
        val row = buildLatestReleaseHomeRow(
            items = listOf(
                item("a", LocalDate.of(2026, 9, 5)),
                item("no-logo", LocalDate.of(2026, 9, 6), logo = null),
                item("blank-logo", LocalDate.of(2026, 9, 7), logo = "  ")
            ),
            title = "Ultime Uscite",
            format = format
        )
        val ids = requireNotNull(row).row.items.map { it.id }
        assertEquals(listOf("a"), ids)
    }

    @Test
    fun rowWithoutAnyLogoReturnsNull() {
        val row = buildLatestReleaseHomeRow(
            items = listOf(item("a", LocalDate.of(2026, 9, 5), logo = null)),
            title = "Ultime Uscite",
            format = format
        )
        assertNull(row)
    }
}
