package com.nuvio.tv.data.wetrakr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrDecodersTest {
    @Test
    fun `last activities are flattened by section`() {
        val activities = decodeWeTrakrActivities(
            """{"all":"2026-09-22T10:09:59.528Z","movies":{"all":"2026-09-22T01:18:05.815Z","last_tracking_watched_at":"2026-09-22T01:18:05.815Z"},"journal_visible_until":"2026-09-22T10:09:54.000Z"}"""
        )

        assertEquals("2026-09-22T10:09:59.528Z", activities["all"])
        assertEquals("2026-09-22T01:18:05.815Z", activities["movies.last_tracking_watched_at"])
        assertEquals("2026-09-22T10:09:54.000Z", activities["journal_visible_until"])
    }

    @Test
    fun `compact watched episodes use the show ids`() {
        val records = decodeWeTrakrWatched(
            """[{"type":"episode","id":5092071,"ids":{"tmdb":5119022,"imdb":"tt31140164","tvdb":10287742},"show_id":1333953,"show_ids":{"tmdb":245318,"imdb":"tt31137273","tvdb":445651},"season_id":426081,"season_number":1,"number":2,"last_watched_at":"2026-09-15T21:09:53.772Z","plays":1}]""",
            WeTrakrItemType.EPISODE
        )

        val record = records.single()
        assertEquals("tt31137273", record.media.ids.imdb)
        assertEquals(1, record.season)
        assertEquals(2, record.episode)
        assertEquals("2026-09-15T21:09:53.772Z", record.watchedAt)
    }

    @Test
    fun `rows without external ids are skipped`() {
        val records = decodeWeTrakrWatched(
            """[{"type":"movie","id":126,"ids":{"tmdb":155,"imdb":"tt0468569"},"last_watched_at":"2026-09-20T18:02:00.000Z"},{"type":"movie","id":127,"last_watched_at":"2026-09-20T18:02:00.000Z"}]""",
            WeTrakrItemType.MOVIE
        )

        assertEquals(listOf("tt0468569"), records.map { it.media.ids.contentId })
    }

    @Test
    fun `playing movies carry progress runtime and poster`() {
        val playback = decodeWeTrakrPlayback(
            """[{"id":1053809,"type":"movie","title":"Pizza Movie","release_date":"2026-03-13T00:00:00.000Z","ids":{"tmdb":1053809},"poster_path":"/3rovbwvxJ5eQrWrQnF1VfJoPcMD.jpg","runtime":98,"playback":{"status":"paused","progress_percent":7,"runtime_seconds":5880,"tracked_at":"2026-08-15T21:31:34.956Z"}}]""",
            WeTrakrItemType.MOVIE
        ).single()

        assertEquals(7f, playback.progress)
        assertEquals(5_880L, playback.runtimeSeconds)
        assertEquals("tmdb:1053809", playback.media.ids.contentId)
        assertEquals(2026, playback.media.year)
        assertEquals("https://image.tmdb.org/t/p/w500/3rovbwvxJ5eQrWrQnF1VfJoPcMD.jpg", playback.media.poster)
    }

    @Test
    fun `playing episodes are filed under their show`() {
        val playback = decodeWeTrakrPlayback(
            """[{"id":62085,"type":"episode","media_id":1396,"ids":{"tmdb":62085},"season_number":1,"number":1,"title":"Pilot","show":{"id":1396,"title":"Breaking Bad","ids":{"tmdb":1396,"imdb":"tt0903747"}},"playback":{"status":"paused","progress_percent":42.5,"tracked_at":"2026-09-26T09:02:40.090Z"}}]""",
            WeTrakrItemType.EPISODE
        ).single()

        assertEquals("tt0903747", playback.media.ids.contentId)
        assertEquals("Breaking Bad", playback.media.title)
        assertEquals("Pilot", playback.episodeTitle)
        assertEquals(1, playback.season)
        assertEquals(1, playback.episode)
    }

    @Test
    fun `account settings give the user id and name`() {
        val user = decodeWeTrakrUser("""{"user":{"id":42,"username":"you"}}""")

        assertEquals(42L, user.id)
        assertEquals("you", user.username)
    }

    @Test
    fun `anime ids are decoded and matched as aliases`() {
        val ids = decodeWeTrakrIds(
            Json.parseToJsonElement("""{"tmdb":209867,"myanimelist":52991,"anilist":154587,"kitsu":"46474","anidb":17617}""").jsonObject
        )

        assertEquals(WeTrakrIds(tmdb = 209867, mal = 52991, anilist = 154587, kitsu = 46474, anidb = 17617), ids)
        assertTrue("kitsu:46474" in ids.aliases())
        assertEquals("tmdb:209867", ids.contentId)
        assertEquals("kitsu:46474", WeTrakrIds(kitsu = 46474, mal = 52991).contentId)
        assertTrue(WeTrakrIds(mal = 52991).matches(ids))
    }

    @Test
    fun `not found items are counted including nested episodes`() {
        val count = weTrakrNotFoundCount(
            """{"added":{"total":0},"notFound":{"movies":[{"ids":{"imdb":"tt0000000"},"status":"watched"}],"shows":[{"ids":{"tmdb":1},"seasons":[{"number":1,"episodes":[{"number":1},{"number":2}]}]}],"seasons":[],"episodes":[]}}"""
        )

        assertEquals(3, count)
        assertTrue(weTrakrNotFoundCount("""{"added":{"total":1}}""") == 0)
    }
}
