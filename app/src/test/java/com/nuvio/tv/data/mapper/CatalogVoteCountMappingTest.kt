package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.MetaPreview
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The "Popolarità" sort in Ricerca orders by the vote count carried by the
 * catalog response, so the optional vote fields must survive parsing in every
 * shape the addons use (string, number, missing) without ever breaking the
 * whole response.
 */
class CatalogVoteCountMappingTest {

    private val adapter: com.squareup.moshi.JsonAdapter<MetaPreviewDto> =
        Moshi.Builder().build().adapter(MetaPreviewDto::class.java)

    private fun parse(json: String): MetaPreview =
        adapter.fromJson(json)!!.toDomain("movie")

    @Test
    fun `string votes field becomes the vote count`() {
        val meta = parse("""{"id":"a","name":"A","votes":"12345"}""")
        assertEquals(12345, meta.voteCount)
    }

    @Test
    fun `tmdb style numeric vote_count becomes the vote count`() {
        val meta = parse("""{"id":"a","name":"A","vote_count":987}""")
        assertEquals(987, meta.voteCount)
    }

    @Test
    fun `camel case voteCount becomes the vote count`() {
        val meta = parse("""{"id":"a","name":"A","voteCount":42}""")
        assertEquals(42, meta.voteCount)
    }

    @Test
    fun `grouped or decimal strings are read leniently`() {
        assertEquals(1234, parse("""{"id":"a","name":"A","votes":"1,234"}""").voteCount)
        assertNull(parse("""{"id":"a","name":"A","votes":"N/A"}""").voteCount)
    }

    @Test
    fun `catalog that omits the vote fields still parses with a null count`() {
        val meta = parse("""{"id":"a","name":"A","imdbRating":"7.1"}""")
        assertNull(meta.voteCount)
        assertEquals(7.1f, meta.imdbRating)
    }

    @Test
    fun `malformed vote value never breaks the response`() {
        val meta = parse("""{"id":"a","name":"A","votes":["x"]}""")
        assertNull(meta.voteCount)
        assertEquals("A", meta.name)
    }
}
