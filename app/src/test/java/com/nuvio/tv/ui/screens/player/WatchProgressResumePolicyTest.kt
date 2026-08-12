package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressResumePolicyTest {

    @Test
    fun `trakt percent-only movie is resumable even with position and duration zero`() {
        val saved = traktPlayback(percent = 42f)

        assertTrue(WatchProgressResumePolicy.isResumable(saved))
        assertEquals(0L, WatchProgressResumePolicy.resolveInitialResumePosition(saved))
        assertTrue(
            WatchProgressResumePolicy.shouldKeepPendingAfterInitialPrepare(saved, initialPositionMs = 0L)
        )
    }

    @Test
    fun `trakt percent-only episode seeks once player duration is known`() {
        val saved = traktPlayback(percent = 35f, season = 1, episode = 4)
        val duration = 2_400_000L

        val decision = WatchProgressResumePolicy.decideReadySeek(
            saved = saved,
            playerDurationMs = duration,
            isSeekable = true
        )

        assertEquals(
            WatchProgressResumePolicy.ReadySeekDecision.Seek(840_000L),
            decision
        )
    }

    @Test
    fun `unseekable first STATE_READY must keep pending instead of starting at zero`() {
        val saved = localMovie(position = 600_000L, duration = 2_400_000L)

        val decision = WatchProgressResumePolicy.decideReadySeek(
            saved = saved,
            playerDurationMs = 0L,
            isSeekable = false
        )

        assertEquals(WatchProgressResumePolicy.ReadySeekDecision.KeepPending, decision)
    }

    @Test
    fun `percent-only progress waits for duration instead of seeking to zero`() {
        val saved = traktPlayback(percent = 50f)

        val decision = WatchProgressResumePolicy.decideReadySeek(
            saved = saved,
            playerDurationMs = 0L,
            isSeekable = true
        )

        assertEquals(WatchProgressResumePolicy.ReadySeekDecision.KeepPending, decision)
    }

    @Test
    fun `local millisecond position is preferred over trakt percent-only row`() {
        val local = localMovie(position = 900_000L, duration = 2_400_000L, lastWatched = 10L)
        val trakt = traktPlayback(percent = 10f, lastWatched = 20L)

        val picked = WatchProgressResumePolicy.pickResumeProgress(provider = trakt, local = local)

        assertEquals(900_000L, picked?.position)
        assertEquals(WatchProgress.SOURCE_LOCAL, picked?.source)
    }

    @Test
    fun `provider row wins when it also has a real position and is newer`() {
        val local = localMovie(position = 100_000L, duration = 2_400_000L, lastWatched = 10L)
        val simkl = simklPlayback(position = 800_000L, duration = 2_400_000L, lastWatched = 20L)

        val picked = WatchProgressResumePolicy.pickResumeProgress(provider = simkl, local = local)

        assertEquals(800_000L, picked?.position)
        assertEquals(WatchProgress.SOURCE_SIMKL_PLAYBACK, picked?.source)
    }

    @Test
    fun `empty first episode-progress emission must not beat local in-progress`() {
        val local = localEpisode(position = 450_000L, duration = 1_800_000L)

        val picked = WatchProgressResumePolicy.pickResumeProgressFromCandidates(
            listOfNotNull(null, local)
        )

        assertEquals(450_000L, picked?.position)
    }

    @Test
    fun `completed history rows are not used as resume targets`() {
        val completed = watchProgress(
            position = 1L,
            duration = 1L,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY
        )

        assertFalse(WatchProgressResumePolicy.isResumable(completed))
        assertNull(
            WatchProgressResumePolicy.pickResumeProgress(provider = completed, local = null)
        )
    }

    @Test
    fun `paused local save with unknown duration still resumes from position`() {
        val saved = watchProgress(
            position = 600_000L,
            duration = 0L,
            progressPercent = 5f
        )

        assertTrue(WatchProgressResumePolicy.isResumable(saved))
        assertEquals(600_000L, WatchProgressResumePolicy.resolveInitialResumePosition(saved))
        assertEquals(
            WatchProgressResumePolicy.ReadySeekDecision.Seek(600_000L),
            WatchProgressResumePolicy.decideReadySeek(
                saved = saved,
                playerDurationMs = 2_400_000L,
                isSeekable = true
            )
        )
    }

    @Test
    fun `continue watching click with local position must not collapse to zero`() {
        val cwItem = localEpisode(position = 1_250_000L, duration = 3_000_000L)
        val emptyEpisodeSnapshot: WatchProgress? = null

        val resume = WatchProgressResumePolicy.pickResumeProgress(
            provider = emptyEpisodeSnapshot,
            local = cwItem
        )
        val initial = WatchProgressResumePolicy.resolveInitialResumePosition(resume!!)
        val ready = WatchProgressResumePolicy.decideReadySeek(
            saved = resume,
            playerDurationMs = 3_000_000L,
            isSeekable = true
        )

        assertEquals(1_250_000L, initial)
        assertEquals(WatchProgressResumePolicy.ReadySeekDecision.Seek(1_250_000L), ready)
    }

    private fun traktPlayback(
        percent: Float,
        season: Int? = null,
        episode: Int? = null,
        lastWatched: Long = 1L
    ) = watchProgress(
        contentType = if (season == null) "movie" else "series",
        season = season,
        episode = episode,
        position = 0L,
        duration = 0L,
        progressPercent = percent,
        lastWatched = lastWatched,
        source = WatchProgress.SOURCE_TRAKT_PLAYBACK
    )

    private fun simklPlayback(
        position: Long,
        duration: Long,
        lastWatched: Long
    ) = watchProgress(
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = ((position.toFloat() / duration.toFloat()) * 100f),
        source = WatchProgress.SOURCE_SIMKL_PLAYBACK
    )

    private fun localMovie(
        position: Long,
        duration: Long,
        lastWatched: Long = 1L
    ) = watchProgress(
        position = position,
        duration = duration,
        lastWatched = lastWatched
    )

    private fun localEpisode(
        position: Long,
        duration: Long
    ) = watchProgress(
        contentType = "series",
        season = 2,
        episode = 5,
        position = position,
        duration = duration
    )

    private fun watchProgress(
        contentType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        position: Long,
        duration: Long,
        progressPercent: Float? = null,
        lastWatched: Long = 1L,
        source: String = WatchProgress.SOURCE_LOCAL
    ) = WatchProgress(
        contentId = "tt1234567",
        contentType = contentType,
        name = "Test",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1234567",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = progressPercent,
        source = source
    )
}
