package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktCommentDto
import com.nuvio.tv.data.remote.dto.trakt.TraktCommentUserDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSearchResultDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktCommentsServiceTest {

    @Test
    fun `filterDisplayableComments preserves reviews and comments in API order`() {
        val comments = listOf(
            TraktCommentDto(id = 1, comment = "short comment", review = false),
            TraktCommentDto(id = 2, comment = "full review", review = true),
            TraktCommentDto(id = 3, comment = "another review", review = true)
        )

        val selected = filterDisplayableComments(comments)

        assertEquals(listOf(1L, 2L, 3L), selected.map { it.id })
    }

    @Test
    fun `filterDisplayableComments removes blank comments`() {
        val comments = listOf(
            TraktCommentDto(id = 1, comment = "first comment", review = false),
            TraktCommentDto(id = 2, comment = null, review = false),
            TraktCommentDto(id = 3, comment = "   ", review = false),
            TraktCommentDto(id = 4, comment = "second comment", review = false)
        )

        val selected = filterDisplayableComments(comments)

        assertEquals(listOf(1L, 4L), selected.map { it.id })
    }

    @Test
    fun `containsInlineSpoilers detects trakt spoiler tags`() {
        assertTrue(containsInlineSpoilers("Hello [spoiler]world[/spoiler]"))
        assertFalse(containsInlineSpoilers("Hello world"))
        assertFalse(containsInlineSpoilers(null))
    }

    @Test
    fun `stripInlineSpoilerMarkup removes trakt spoiler tags and normalizes whitespace`() {
        val stripped = stripInlineSpoilerMarkup("A [spoiler]big twist[/spoiler] happened")

        assertEquals("A big twist happened", stripped)
    }

    @Test
    fun `toBestCommentsPathId prefers imdb then trakt then slug`() {
        assertEquals(
            "tt1234567",
            TraktIdsDto(trakt = 5, slug = "movie-slug", imdb = "tt1234567").toBestCommentsPathId()
        )
        assertEquals(
            "5",
            TraktIdsDto(trakt = 5, slug = "movie-slug", imdb = null).toBestCommentsPathId()
        )
        assertEquals(
            "movie-slug",
            TraktIdsDto(trakt = null, slug = "movie-slug", imdb = null).toBestCommentsPathId()
        )
        assertNull(TraktIdsDto().toBestCommentsPathId())
    }

    @Test
    fun `search result path resolution uses the expected media ids`() {
        val movieResult = TraktSearchResultDto(
            type = "movie",
            movie = TraktMovieDto(ids = TraktIdsDto(trakt = 9, slug = "movie-slug"))
        )
        val showResult = TraktSearchResultDto(
            type = "show",
            show = TraktShowDto(
                ids = TraktIdsDto(
                    imdb = "tt7654321",
                    trakt = 99,
                    slug = "show-slug"
                )
            )
        )

        assertEquals("9", movieResult.toTraktPathId(TraktCommentsType.MOVIE))
        assertEquals("tt7654321", showResult.toTraktPathId(TraktCommentsType.SHOW))
    }

    @Test
    fun `filterDisplayableComments does not cap the selected results`() {
        val comments = (1L..10L).map { id ->
            TraktCommentDto(
                id = id,
                comment = "review $id",
                review = true,
                user = TraktCommentUserDto(username = "user$id")
            )
        }

        val selected = filterDisplayableComments(comments)

        assertEquals(10, selected.size)
        assertEquals((1L..10L).toList(), selected.map { it.id })
    }
}
