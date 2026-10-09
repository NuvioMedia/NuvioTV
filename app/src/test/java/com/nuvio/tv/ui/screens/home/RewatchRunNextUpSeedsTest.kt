package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the rewatch runs do to the next up seeds: which episode a series that is being rewatched
 * offers next, and which series a run puts in the row when nothing else would.
 *
 * The rule is the mobile client's own rule over its candidate list: one run per content id, the run
 * with the newest mark, and a run that has no candidate of its own becomes one.
 */
class RewatchRunNextUpSeedsTest {

    @Test
    fun `a newer run moves the seed onto the run position`() {
        val seed = seed(contentId = SHOW, season = 1, episode = 2, lastWatched = 1_000L)

        val result = applyRewatchRunPositions(
            seeds = listOf(seed),
            runs = listOf(run(contentId = SHOW, season = 3, episode = 4, markedAt = 2_000L)),
        )

        val moved = result.single()
        assertEquals(3, moved.season)
        assertEquals(4, moved.episode)
        assertEquals(2_000L, moved.lastWatched)
        // The id belonged to the old episode, and the resolver falls back to it when the seed is
        // not in the catalogue, so the run position is carried without it.
        assertEquals("", moved.videoId)
    }

    @Test
    fun `an older run leaves the canonical position alone`() {
        val seed = seed(contentId = SHOW, season = 1, episode = 2, lastWatched = 2_000L)

        val result = applyRewatchRunPositions(
            seeds = listOf(seed),
            runs = listOf(run(contentId = SHOW, season = 3, episode = 4, markedAt = 1_000L)),
        )

        assertEquals(seed, result.single())
    }

    @Test
    fun `a seed the run already stands on is left alone`() {
        val seed = seed(contentId = SHOW, season = 3, episode = 4, lastWatched = 1_000L, videoId = "video-3-4")

        val result = applyRewatchRunPositions(
            seeds = listOf(seed),
            runs = listOf(run(contentId = SHOW, season = 3, episode = 4, markedAt = 2_000L)),
        )

        assertEquals(seed, result.single())
        assertEquals("video-3-4", result.single().videoId)
    }

    @Test
    fun `one run per content id decides and the newest mark wins`() {
        val seed = seed(contentId = SHOW, season = 1, episode = 1, lastWatched = 500L)

        val result = applyRewatchRunPositions(
            seeds = listOf(seed),
            runs = listOf(
                run(contentId = SHOW, season = 2, episode = 2, markedAt = 1_500L),
                run(contentId = SHOW, season = 5, episode = 6, markedAt = 2_500L, matchKeys = listOf("imdb:$SHOW")),
            ),
        )

        assertEquals(1, result.size)
        // The newest mark wins the whole position, so the season has to come from the same run the
        // episode and the mark come from: the older S02E02 run contributes nothing.
        assertEquals(5, result.single().season)
        assertEquals(6, result.single().episode)
        assertEquals(2_500L, result.single().lastWatched)
    }

    @Test
    fun `a run is matched by every id form of the series`() {
        // matchKeys are built by SimklMedia.rewatchMatchKeys (SimklRewatchRuns.kt), which adds the
        // canonical content id, the bare imdb id and the "imdb:" form, so a seed built from whichever
        // id the playback carried still finds the run.
        val seriesRun = run(
            contentId = "simkl:39687",
            season = 2, episode = 3, markedAt = 900L,
            matchKeys = listOf("simkl:39687", "tt5753856", "imdb:tt5753856"),
        )

        val byBareImdb = applyRewatchRunPositions(
            seeds = listOf(seed(contentId = "tt5753856", season = 1, episode = 1, lastWatched = 500L)),
            runs = listOf(seriesRun),
        ).single()
        assertEquals(2, byBareImdb.season)
        assertEquals(3, byBareImdb.episode)

        val byImdbPrefix = applyRewatchRunPositions(
            seeds = listOf(seed(contentId = "imdb:tt5753856", season = 1, episode = 1, lastWatched = 500L)),
            runs = listOf(seriesRun),
        ).single()
        assertEquals(3, byImdbPrefix.episode)

        val byRunContentId = applyRewatchRunPositions(
            seeds = listOf(seed(contentId = "simkl:39687", season = 1, episode = 1, lastWatched = 500L)),
            runs = listOf(seriesRun),
        ).single()
        assertEquals(3, byRunContentId.episode)
    }

    @Test
    fun `a run with no seed becomes a seed of its own`() {
        val result = applyRewatchRunPositions(
            seeds = emptyList(),
            runs = listOf(run(contentId = SHOW, season = 2, episode = 3, markedAt = 900L)),
        )

        val added = result.single()
        assertEquals(SHOW, added.contentId)
        assertEquals("series", added.contentType)
        assertEquals(2, added.season)
        assertEquals(3, added.episode)
        assertEquals(900L, added.lastWatched)
        // The pipeline only accepts a seed it reads as completed.
        assertTrue(added.isCompleted())
        assertNotNull(added.videoId)
    }

    @Test
    fun `a run whose series already has a seed is not added twice`() {
        val seed = seed(contentId = SHOW, season = 1, episode = 1, lastWatched = 500L)

        val result = applyRewatchRunPositions(
            seeds = listOf(seed),
            runs = listOf(run(contentId = SHOW, season = 2, episode = 2, markedAt = 1_500L)),
        )

        assertEquals(1, result.size)
        assertEquals(2, result.single().season)
    }

    @Test
    fun `two runs for one content id add a single seed`() {
        val result = applyRewatchRunPositions(
            seeds = emptyList(),
            runs = listOf(
                run(contentId = SHOW, season = 6, episode = 7, markedAt = 2_500L),
                run(contentId = SHOW, season = 2, episode = 2, markedAt = 1_500L),
            ),
        )

        assertEquals(1, result.size)
        assertEquals(7, result.single().episode)
    }

    @Test
    fun `no runs leaves the seeds untouched`() {
        val seeds = listOf(
            seed(contentId = SHOW, season = 1, episode = 2, lastWatched = 1_000L),
            seed(contentId = OTHER_SHOW, season = 1, episode = 1, lastWatched = 900L),
        )

        assertSame(seeds, applyRewatchRunPositions(seeds = seeds, runs = emptyList()))
    }

    @Test
    fun `a run for another series leaves the seed alone`() {
        val seed = seed(contentId = SHOW, season = 1, episode = 2, lastWatched = 1_000L)

        val result = applyRewatchRunPositions(
            seeds = listOf(seed),
            runs = listOf(run(contentId = OTHER_SHOW, season = 9, episode = 9, markedAt = 2_000L)),
        )

        // The run does not match this seed, so the seed is left exactly as it was. Its own series
        // holds no seed though, and the rule gives a run with no seed of its own one, the same branch
        // the mobile client has, so that series joins the row instead of being lost.
        assertEquals(2, result.size)
        assertSame(seed, result.single { it.contentId == SHOW })
        val added = result.single { it.contentId == OTHER_SHOW }
        assertEquals(9, added.season)
        assertEquals(9, added.episode)
        assertEquals(2_000L, added.lastWatched)
    }

    private fun seed(
        contentId: String,
        season: Int,
        episode: Int,
        lastWatched: Long,
        videoId: String = "$contentId:$season:$episode",
    ) = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = lastWatched,
        progressPercent = 100f,
    )

    private fun run(
        contentId: String,
        season: Int,
        episode: Int,
        markedAt: Long,
        matchKeys: List<String> = emptyList(),
    ) = RewatchRunPosition(
        contentId = contentId,
        matchKeys = matchKeys,
        seasonNumber = season,
        episodeNumber = episode,
        markedAtEpochMs = markedAt,
    )

    private companion object {
        const val SHOW = "tt5753856"
        const val OTHER_SHOW = "tt0903747"
    }
}
