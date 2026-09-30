package com.nuvio.tv.core.poster

import com.nuvio.tv.core.poster.CustomPosterUrlResolver.ContentIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomPosterUrlResolverTest {

    // ── extractIds ──────────────────────────────────────────────────────

    @Test
    fun `extractIds parses IMDb id`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        assertEquals("tt0137523", ids.id)
        assertEquals("tt0137523", ids.imdbId)
        assertNull(ids.tmdbId)
    }

    @Test
    fun `extractIds parses TMDB id`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        assertEquals("tmdb:1396", ids.id)
        assertEquals("1396", ids.tmdbId)
        assertNull(ids.imdbId)
    }

    @Test
    fun `extractIds parses Kitsu id`() {
        val ids = CustomPosterUrlResolver.extractIds("kitsu:7442")
        assertEquals("kitsu:7442", ids.id)
        assertEquals("7442", ids.kitsuId)
        assertNull(ids.imdbId)
        assertNull(ids.tmdbId)
    }

    @Test
    fun `extractIds parses AniList id`() {
        val ids = CustomPosterUrlResolver.extractIds("anilist:21")
        assertEquals("21", ids.anilistId)
    }

    @Test
    fun `extractIds parses MAL id`() {
        val ids = CustomPosterUrlResolver.extractIds("mal:1535")
        assertEquals("1535", ids.malId)
    }

    @Test
    fun `extractIds uses explicit imdbId over parsed`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396", explicitImdbId = "tt0903747")
        assertEquals("tmdb:1396", ids.id)
        assertEquals("1396", ids.tmdbId)
        assertEquals("tt0903747", ids.imdbId)
    }

    @Test
    fun `extractIds explicit imdbId fills when stremio id is not imdb`() {
        val ids = CustomPosterUrlResolver.extractIds("kitsu:7442", explicitImdbId = "tt0137523")
        assertEquals("7442", ids.kitsuId)
        assertEquals("tt0137523", ids.imdbId)
    }

    // ── Nuvio native placeholders ───────────────────────────────────────

    @Test
    fun `resolve Nuvio native pattern with IMDb id`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&id_type={id_type}&type={type}",
            ids, "movie"
        )
        assertEquals("https://example.com/poster?id=tt0137523&id_type=imdb&type=movie", url)
    }

    @Test
    fun `resolve Nuvio native pattern with TMDB id`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&id_type={id_type}&type={type}",
            ids, "series"
        )
        assertEquals("https://example.com/poster?id=tmdb:1396&id_type=tmdb&type=series", url)
    }

    @Test
    fun `resolve Nuvio native pattern with Kitsu id`() {
        val ids = CustomPosterUrlResolver.extractIds("kitsu:7442")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&id_type={id_type}&type={type}",
            ids, "series"
        )
        assertEquals("https://example.com/poster?id=kitsu:7442&id_type=kitsu&type=series", url)
    }

    // ── RPDB standard pattern ───────────────────────────────────────────

    @Test
    fun `resolve RPDB pattern with IMDb id`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/{imdb_id}.jpg?fallback=true",
            ids, "movie"
        )
        assertEquals(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/tt0137523.jpg?fallback=true",
            url
        )
    }

    @Test
    fun `resolve RPDB pattern falls back to TMDB when IMDb id is missing`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/{imdb_id}.jpg?fallback=true",
            ids, "series"
        )
        assertEquals(
            "https://api.ratingposterdb.com/t1-key/tmdb/poster-default/series-1396.jpg?fallback=true",
            url
        )
    }

    @Test
    fun `resolve RPDB pattern falls back to TVDB when IMDb and TMDB are missing`() {
        val ids = ContentIds(id = "tvdb:81189", tvdbId = "81189")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/{imdb_id}.jpg?fallback=true",
            ids, "series"
        )
        assertEquals(
            "https://api.ratingposterdb.com/t1-key/tvdb/poster-default/series-81189.jpg?fallback=true",
            url
        )
    }

    @Test
    fun `resolve RPDB with both IMDb and TMDB prefers IMDb`() {
        val ids = ContentIds(id = "tt0903747", imdbId = "tt0903747", tmdbId = "1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/{imdb_id}.jpg?fallback=true",
            ids, "series"
        )
        assertEquals(
            "https://api.ratingposterdb.com/t1-key/imdb/poster-default/tt0903747.jpg?fallback=true",
            url
        )
    }

    // ── aioratings (same format as RPDB) ────────────────────────────────

    @Test
    fun `resolve aioratings pattern falls back to TMDB`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:550")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.aioratings.com/mykey/imdb/poster-default/{imdb_id}.jpg?fallback=true",
            ids, "movie"
        )
        assertEquals(
            "https://api.aioratings.com/mykey/tmdb/poster-default/movie-550.jpg?fallback=true",
            url
        )
    }

    // ── BetterPosters ───────────────────────────────────────────────────

    @Test
    fun `resolve BetterPosters pattern with IMDb id`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://btttr.cc/poster/imdb/poster-default/{imdb_id}.jpg",
            ids, "movie"
        )
        assertEquals("https://btttr.cc/poster/imdb/poster-default/tt0137523.jpg", url)
    }

    @Test
    fun `resolve BetterPosters returns null when IMDb id is missing`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://btttr.cc/poster/imdb/poster-default/{imdb_id}.jpg",
            ids, "series"
        )
        assertNull(url)
    }

    // ── PostersPlus ─────────────────────────────────────────────────────

    @Test
    fun `resolve PostersPlus pattern with TMDB id`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.example.com/poster?tmdb_id={tmdb_id}&type={type}",
            ids, "series"
        )
        assertEquals("https://postersplus.example.com/poster?tmdb_id=1396&type=series", url)
    }

    @Test
    fun `resolve PostersPlus with optional imdb_id`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396", explicitImdbId = "tt0903747")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.example.com/poster?tmdb_id={tmdb_id}&imdb_id={imdb_id?}&type={type}",
            ids, "series"
        )
        assertEquals(
            "https://postersplus.example.com/poster?tmdb_id=1396&imdb_id=tt0903747&type=series",
            url
        )
    }

    @Test
    fun `resolve PostersPlus optional imdb_id resolves to empty when missing`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.example.com/poster?tmdb_id={tmdb_id}&imdb_id={imdb_id?}&type={type}",
            ids, "series"
        )
        assertEquals(
            "https://postersplus.example.com/poster?tmdb_id=1396&imdb_id=&type=series",
            url
        )
    }

    @Test
    fun `resolve PostersPlus returns null when tmdb_id is missing`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.example.com/poster?tmdb_id={tmdb_id}&type={type}",
            ids, "movie"
        )
        assertNull(url)
    }

    @Test
    fun `resolve PostersPlus with stremio_id for anime`() {
        val ids = CustomPosterUrlResolver.extractIds("kitsu:7442")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.example.com/poster?tmdb_id={tmdb_id?}&stremio_id={id}&type={type}",
            ids, "series"
        )
        assertEquals(
            "https://postersplus.example.com/poster?tmdb_id=&stremio_id=kitsu:7442&type=series",
            url
        )
    }

    // ── Full PostersPlus URL from configurator ──────────────────────────

    @Test
    fun `resolve full PostersPlus URL with static params`() {
        val ids = ContentIds(id = "tmdb:1396", tmdbId = "1396", imdbId = "tt0903747")
        val url = CustomPosterUrlResolver.resolve(
            "https://postersplus.stremio.ru/poster?tmdb_id={tmdb_id}&imdb_id={imdb_id?}&type={type}" +
                "&primary_client=stremio_tv_nuvio&fallback_to_imdb=true" +
                "&movie_weights=letterboxd%3A0.99",
            ids, "series"
        )
        assertEquals(
            "https://postersplus.stremio.ru/poster?tmdb_id=1396&imdb_id=tt0903747&type=series" +
                "&primary_client=stremio_tv_nuvio&fallback_to_imdb=true" +
                "&movie_weights=letterboxd%3A0.99",
            url
        )
    }

    // ── Pipe syntax (supported ID types) ───────────────────────────────

    @Test
    fun `resolve pipe picks first available ID`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?content_id={imdb_id|tmdb_id|kitsu_id}&type={type}",
            ids, "movie"
        )
        assertEquals("https://example.com/poster?content_id=tt0137523&type=movie", url)
    }

    @Test
    fun `resolve pipe skips unavailable and picks second`() {
        val ids = CustomPosterUrlResolver.extractIds("kitsu:7442")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?content_id={imdb_id|kitsu_id|tvdb_id}&type={type}",
            ids, "series"
        )
        assertEquals("https://example.com/poster?content_id=7442&type=series", url)
    }

    @Test
    fun `resolve pipe returns null when no declared ID available`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?content_id={imdb_id|kitsu_id}&type={type}",
            ids, "series"
        )
        assertNull(url)
    }

    @Test
    fun `resolve pipe with shape`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:550")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/art?id={imdb_id|tmdb_id}&type={type}&shape={shape}",
            ids, "movie", shape = "landscape"
        )
        assertEquals("https://example.com/art?id=550&type=movie&shape=landscape", url)
    }

    @Test
    fun `resolve pipe with explicit imdbId enrichment`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396", explicitImdbId = "tt0903747")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?content_id={imdb_id|tmdb_id}&type={type}",
            ids, "series"
        )
        assertEquals("https://example.com/poster?content_id=tt0903747&type=series", url)
    }

    // ── Shape ───────────────────────────────────────────────────────────

    @Test
    fun `resolve typed_id for IMDb stays as-is`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.aioratings.com/KEY/{id_type}/poster-default/{typed_id}.jpg",
            ids, "movie"
        )
        assertEquals("https://api.aioratings.com/KEY/imdb/poster-default/tt0137523.jpg", url)
    }

    @Test
    fun `resolve typed_id for TMDB adds movie prefix`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:550")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.aioratings.com/KEY/{id_type}/poster-default/{typed_id}.jpg",
            ids, "movie"
        )
        assertEquals("https://api.aioratings.com/KEY/tmdb/poster-default/movie-550.jpg", url)
    }

    @Test
    fun `resolve typed_id for TMDB series adds series prefix`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.aioratings.com/KEY/{id_type}/poster-default/{typed_id}.jpg",
            ids, "series"
        )
        assertEquals("https://api.aioratings.com/KEY/tmdb/poster-default/series-1396.jpg", url)
    }

    @Test
    fun `resolve typed_id for TVDB adds series prefix`() {
        val ids = ContentIds(id = "tvdb:81189", tvdbId = "81189")
        val url = CustomPosterUrlResolver.resolve(
            "https://api.aioratings.com/KEY/{id_type}/poster-default/{typed_id}.jpg",
            ids, "series"
        )
        assertEquals("https://api.aioratings.com/KEY/tvdb/poster-default/series-81189.jpg", url)
    }

    @Test
    fun `resolve universal aioratings URL works for both IMDb and TMDB`() {
        val pattern = "https://api.aioratings.com/KEY/{id_type}/poster-default/{typed_id}.jpg?fallback=true"

        val imdbIds = CustomPosterUrlResolver.extractIds("tt0137523")
        assertEquals(
            "https://api.aioratings.com/KEY/imdb/poster-default/tt0137523.jpg?fallback=true",
            CustomPosterUrlResolver.resolve(pattern, imdbIds, "movie")
        )

        val tmdbIds = CustomPosterUrlResolver.extractIds("tmdb:1396")
        assertEquals(
            "https://api.aioratings.com/KEY/tmdb/poster-default/series-1396.jpg?fallback=true",
            CustomPosterUrlResolver.resolve(pattern, tmdbIds, "series")
        )
    }

    // ── Shape ───────────────────────────────────────────────────────────

    @Test
    fun `resolve with shape placeholder for landscape`() {
        val ids = CustomPosterUrlResolver.extractIds("tmdb:1396")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&id_type={id_type}&type={type}&shape={shape}",
            ids, "series", shape = "landscape"
        )
        assertEquals(
            "https://example.com/poster?id=tmdb:1396&id_type=tmdb&type=series&shape=landscape",
            url
        )
    }

    @Test
    fun `resolve shape defaults to poster`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&shape={shape}",
            ids, "movie"
        )
        assertEquals("https://example.com/poster?id=tt0137523&shape=poster", url)
    }

    @Test
    fun `resolve optional shape placeholder`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/poster?id={id}&type={type}&shape={shape?}",
            ids, "movie", shape = "square"
        )
        assertEquals("https://example.com/poster?id=tt0137523&type=movie&shape=square", url)
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `resolve returns null for blank pattern`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        assertNull(CustomPosterUrlResolver.resolve("", ids, "movie"))
        assertNull(CustomPosterUrlResolver.resolve("   ", ids, "movie"))
    }

    @Test
    fun `resolve pattern without any placeholders returns as-is`() {
        val ids = CustomPosterUrlResolver.extractIds("tt0137523")
        val url = CustomPosterUrlResolver.resolve(
            "https://example.com/static-poster.jpg",
            ids, "movie"
        )
        assertEquals("https://example.com/static-poster.jpg", url)
    }

    @Test
    fun `resolve OpenPosterDB backdrop pattern with tmdb type prefix`() {
        val ids = ContentIds(id = "tt0137523", imdbId = "tt0137523", tmdbId = "550")
        val url = CustomPosterUrlResolver.resolve(
            "https://opdb.example.com/key123/tmdb/backdrop-default/{type}-{tmdb_id}.jpg",
            ids, "movie"
        )
        assertEquals("https://opdb.example.com/key123/tmdb/backdrop-default/movie-550.jpg", url)
    }
}
