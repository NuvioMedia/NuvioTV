package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a live run overrides a saved dismissal of its title`() {
        val dismissed = setOf(SHOW, "imdb:$SHOW", OTHER_SHOW)

        val result = withoutLiveRewatchDismissals(
            dismissed = dismissed,
            runs = listOf(
                run(
                    contentId = SHOW,
                    season = 1,
                    episode = 2,
                    markedAt = 1_000L,
                    matchKeys = listOf("imdb:$SHOW"),
                )
            ),
        )

        assertEquals(setOf(OTHER_SHOW), result)
    }

    @Test
    fun `no runs leave the saved dismissals alone`() {
        val dismissed = setOf(SHOW, OTHER_SHOW)

        assertSame(dismissed, withoutLiveRewatchDismissals(dismissed, emptyList()))
    }

    @Test
    fun `an optimistic run replaces the account run for the same item`() {
        val account = run(contentId = SHOW, season = 3, episode = 4, markedAt = 1_000L)
        val optimistic = run(contentId = SHOW, season = 3, episode = 5, markedAt = 2_000L)

        val merged = mergeOptimisticRewatchRuns(listOf(account), mapOf(SHOW to optimistic))

        assertEquals(listOf(optimistic), merged)
    }

    @Test
    fun `an optimistic run for an item the account does not hold is added`() {
        val other = run(contentId = OTHER_SHOW, season = 1, episode = 2, markedAt = 1_000L)
        val optimistic = run(contentId = SHOW, season = 3, episode = 5, markedAt = 2_000L)

        val merged = mergeOptimisticRewatchRuns(listOf(other), mapOf(SHOW to optimistic))

        assertEquals(setOf(SHOW, OTHER_SHOW), merged.map { run -> run.contentId }.toSet())
        assertEquals(optimistic, merged.first())
    }

    @Test
    fun `no optimistic run leaves the account list alone`() {
        val account = run(contentId = SHOW, season = 3, episode = 4, markedAt = 1_000L)

        assertEquals(listOf(account), mergeOptimisticRewatchRuns(listOf(account), emptyMap()))
    }

    @Test
    fun `a run that appears invalidates every id form of its series`() {
        val appeared = run(
            contentId = SHOW,
            season = 3,
            episode = 5,
            markedAt = 2_000L,
            matchKeys = listOf("imdb:$SHOW", "tmdb:123"),
        )

        assertEquals(
            setOf(SHOW, "imdb:$SHOW", "tmdb:123"),
            changedRewatchRunKeys(previous = emptyList(), current = listOf(appeared)),
        )
    }

    @Test
    fun `a run that disappears invalidates only its series`() {
        val gone = run(
            contentId = SHOW,
            season = 3,
            episode = 5,
            markedAt = 2_000L,
            matchKeys = listOf("imdb:$SHOW"),
        )
        val other = run(contentId = OTHER_SHOW, season = 1, episode = 2, markedAt = 1_000L)

        assertEquals(
            setOf(SHOW, "imdb:$SHOW"),
            changedRewatchRunKeys(previous = listOf(gone, other), current = listOf(other)),
        )
    }

    @Test
    fun `a run that moves invalidates only the series that moved`() {
        val before = run(
            contentId = SHOW,
            season = 3,
            episode = 4,
            markedAt = 1_000L,
            matchKeys = listOf("imdb:$SHOW"),
        )
        val after = run(
            contentId = SHOW,
            season = 3,
            episode = 5,
            markedAt = 2_000L,
            matchKeys = listOf("imdb:$SHOW"),
        )
        val other = run(contentId = OTHER_SHOW, season = 1, episode = 2, markedAt = 1_000L)

        assertEquals(
            setOf(SHOW, "imdb:$SHOW"),
            changedRewatchRunKeys(
                previous = listOf(before, other),
                current = listOf(after, other),
            ),
        )
    }

    @Test
    fun `a run whose mark time alone changes invalidates nothing`() {
        val before = run(contentId = SHOW, season = 3, episode = 4, markedAt = 1_000L)
        val after = run(contentId = SHOW, season = 3, episode = 4, markedAt = 2_000L)

        assertTrue(changedRewatchRunKeys(listOf(before), listOf(after)).isEmpty())
    }

    @Test
    fun `the optimistic run is held until the account reaches the marked episode`() {
        val optimistic = run(contentId = SHOW, season = 1, episode = 11, markedAt = 3_000L)

        assertFalse(accountCaughtUpWithOptimisticRun(listOf(run(SHOW, 1, 10, 2_000L)), optimistic))
        assertTrue(accountCaughtUpWithOptimisticRun(listOf(run(SHOW, 1, 11, 2_500L)), optimistic))
        assertTrue(accountCaughtUpWithOptimisticRun(listOf(run(SHOW, 1, 12, 2_500L)), optimistic))
    }

    @Test
    fun `a mark that completes a season's session lets the series move on to the next season`() {
        val optimistic = run(contentId = SHOW, season = 1, episode = 11, markedAt = 3_000L)

        // The first season's session completed, and the series now runs on the second season's.
        assertTrue(accountCaughtUpWithOptimisticRun(listOf(run(SHOW, 2, 1, 1_000L)), optimistic))
        // Or on no session at all.
        assertTrue(accountCaughtUpWithOptimisticRun(listOf(run(OTHER_SHOW, 1, 1, 1_000L)), optimistic))
    }

    @Test
    fun `an earlier season of the account does not release the hold`() {
        val optimistic = run(contentId = SHOW, season = 2, episode = 3, markedAt = 3_000L)

        assertFalse(accountCaughtUpWithOptimisticRun(listOf(run(SHOW, 1, 24, 2_000L)), optimistic))
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
