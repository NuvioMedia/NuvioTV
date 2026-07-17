package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoUtilsTest {
    private val eastern = ZoneId.of("America/Detroit")

    @Test
    fun `catalog release filtering uses exact timestamp`() {
        val item = preview(released = "2026-07-15T15:00:00Z")
        val today = LocalDate.of(2026, 7, 15)

        assertTrue(
            item.isUnreleased(
                today = today,
                clock = Clock.fixed(Instant.parse("2026-07-15T14:59:59Z"), eastern)
            )
        )
        assertFalse(
            item.isUnreleased(
                today = today,
                clock = Clock.fixed(Instant.parse("2026-07-15T15:00:00Z"), eastern)
            )
        )
    }

    @Test
    fun `catalog date only release starts at utc midnight`() {
        val item = preview(released = "2026-07-15")
        val today = LocalDate.of(2026, 7, 14)

        assertTrue(
            item.isUnreleased(
                today = today,
                clock = Clock.fixed(Instant.parse("2026-07-14T23:59:59Z"), eastern)
            )
        )
        assertFalse(
            item.isUnreleased(
                today = today,
                clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), eastern)
            )
        )
    }

    private fun preview(released: String): MetaPreview = MetaPreview(
        id = "tt1",
        type = ContentType.MOVIE,
        name = "Title",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        released = released,
    )
}
