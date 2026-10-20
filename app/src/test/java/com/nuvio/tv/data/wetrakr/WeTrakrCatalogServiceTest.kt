package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrCatalogServiceTest {
    private class MemoryStorage : WeTrakrSyncStorage {
        override suspend fun load(profileId: Int): String? = null
        override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) = Unit
        override suspend fun remove(profileId: Int, checkScope: () -> Unit) = Unit
    }

    private fun TestScope.service(harness: WeTrakrTestHarness): WeTrakrCatalogService {
        val dispatcher = StandardTestDispatcher(testScheduler)
        harness.connected()
        val sync = WeTrakrSyncRepository(
            MemoryStorage(), harness.store, harness.api, MutableStateFlow(1), TestScope(dispatcher),
            now = { harness.now }, dispatcher = dispatcher, remote = { WeTrakrTestRemote() }
        )
        return WeTrakrCatalogService(harness.api, sync, { "WeTrakr user" }, now = { harness.now })
    }

    @Test
    fun `comments look up WeTrakr's id by IMDb id once and are cached`() = runTest {
        val harness = WeTrakrTestHarness()
        val service = service(harness)
        harness.reply(body = SEARCH)
        harness.reply(body = COMMENTS, headers = mapOf("X-Pagination-Page-Count" to "3", "X-Pagination-Item-Count" to "120"))
        harness.reply(body = SIMILAR)

        val page = service.getCommentsPage(movie(), page = 1)
        service.getCommentsPage(movie(), page = 1)
        val similar = service.getSimilar(movie())

        assertEquals(listOf("/search", "/movies/435/comments", "/movies/435/similar"), harness.engine.requests.map { it.path })
        assertEquals(mapOf("q" to "tt0137523", "filter_type" to "movie"), harness.engine.requests.first().query)
        assertEquals(3, page.pageCount)
        assertEquals(120, page.itemCount)
        val comment = page.items.single()
        assertEquals("Narate", comment.authorDisplayName)
        assertEquals("20th Anniversary\nGreat & weird", comment.comment)
        assertEquals(4, comment.likes)
        assertEquals(9, comment.rating)
        assertTrue(comment.spoiler)
        assertEquals(listOf("tt0114814"), similar.map { it.id })
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", similar.single().poster)
        assertEquals(PosterShape.LANDSCAPE, similar.single().posterShape)
    }

    @Test
    fun `episode comments resolve the episode's WeTrakr id`() = runTest {
        val harness = WeTrakrTestHarness()
        val service = service(harness)
        harness.reply(body = """[{"type":"show","id":1391953,"ids":{"tmdb":1396,"imdb":"tt0903747"}}]""")
        harness.reply(body = """{"id":174658,"media_id":1391953}""")
        harness.reply(body = "[]")
        val episode = Video("tt0903747:1:1", "Pilot", null, null, season = 1, episode = 1, overview = null)

        service.getCommentsPage(show(), targetEpisode = episode)

        assertEquals(
            listOf("/search", "/shows/1391953/seasons/1/episodes/1", "/episodes/174658/comments"),
            harness.engine.requests.map { it.path }
        )
    }

    @Test
    fun `a search result only counts when its ids match`() = runTest {
        val harness = WeTrakrTestHarness()
        val service = service(harness)
        harness.reply(body = """[{"type":"movie","id":9,"ids":{"tmdb":1,"imdb":"tt9999999"}}]""")

        val page = service.getCommentsPage(movie())

        assertTrue(page.items.isEmpty())
        assertEquals(1, harness.engine.requests.size)
    }

    private fun movie() = meta("tt0137523", ContentType.MOVIE, "Fight Club")

    private fun show() = meta("tt0903747", ContentType.SERIES, "Breaking Bad")

    private fun meta(id: String, type: ContentType, name: String) = Meta(
        id = id, type = type, name = name, poster = null, posterShape = PosterShape.POSTER, background = null,
        logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), runtime = null,
        director = emptyList(), cast = emptyList(), videos = emptyList(), country = null, awards = null,
        language = null, links = emptyList()
    )

    private companion object {
        const val SEARCH = """[{"type":"movie","id":435,"title":"Fight Club","ids":{"tmdb":550,"imdb":"tt0137523"}}]"""
        const val COMMENTS = """[
            {"id":1,"hidden":false,"spoiler":true,"is_long":false,"rating":9,"text":"<b>20th Anniversary\n</b><i>Great &amp; weird</i>",
             "user":{"info":{"username":"Narate","display_name":"Narate"}},"interactions":{"counter":{"likes":4,"replies":0}},
             "comment_added_at":"2026-08-31T20:53:41.347Z"},
            {"id":2,"hidden":true,"text":"hidden","user":{"info":{"username":"x"}}},
            {"id":3,"hidden":false,"text":"   ","user":{"info":{"username":"y"}}}
        ]"""
        const val SIMILAR = """[
            {"type":"movie","id":517,"title":"Seven","ids":{"tmdb":807,"imdb":"tt0114814"},"backdrop_path":"/b.jpg","release_date":"1995-09-22T00:00:00.000Z","rating":8.4,"genres":["Crime"]},
            {"type":"movie","id":518,"title":"No ids","ids":{}}
        ]"""
    }
}
