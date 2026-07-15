package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAutoNextPolicyTest {

    private val window = 12_000L

    // ---- shouldResetChainAbort: the 12s manual-relaunch fix ----

    @Test
    fun `manual launch always resets the chain abort, even inside the window`() {
        assertTrue(
            ExternalAutoNextPolicy.shouldResetChainAbort(
                autoLaunch = false, nowMs = 5_000L, lastAutoNextEmitMs = 0L, continuationWindowMs = window
            )
        )
    }

    @Test
    fun `auto-launch inside the window is a continuation and keeps the abort`() {
        assertFalse(
            ExternalAutoNextPolicy.shouldResetChainAbort(
                autoLaunch = true, nowMs = 5_000L, lastAutoNextEmitMs = 0L, continuationWindowMs = window
            )
        )
    }

    @Test
    fun `auto-launch after the window is fresh and resets the abort`() {
        assertTrue(
            ExternalAutoNextPolicy.shouldResetChainAbort(
                autoLaunch = true, nowMs = 20_000L, lastAutoNextEmitMs = 0L, continuationWindowMs = window
            )
        )
    }

    // ---- isAbortedContinuation ----

    @Test
    fun `abort skips the auto-launch only inside the window`() {
        assertTrue(
            ExternalAutoNextPolicy.isAbortedContinuation(
                chainAborted = true, nowMs = 5_000L, lastAutoNextEmitMs = 0L, continuationWindowMs = window
            )
        )
        assertFalse(
            ExternalAutoNextPolicy.isAbortedContinuation(
                chainAborted = true, nowMs = 20_000L, lastAutoNextEmitMs = 0L, continuationWindowMs = window
            )
        )
    }

    @Test
    fun `no abort means no continuation skip`() {
        assertFalse(
            ExternalAutoNextPolicy.isAbortedContinuation(
                chainAborted = false, nowMs = 1_000L, lastAutoNextEmitMs = 0L, continuationWindowMs = window
            )
        )
    }

    // ---- shouldRaiseLoader: overlay re-raise fix + season-less ----

    @Test
    fun `loader raises for a normal series episode`() {
        assertTrue(raise(episode = 3, type = "series"))
    }

    @Test
    fun `loader raises for a season-less series episode`() {
        assertTrue(raise(episode = 7, type = "series"))
    }

    @Test
    fun `loader does not raise when suppressed by a settle release`() {
        assertFalse(raise(episode = 3, type = "series", overlaySuppressed = true))
    }

    @Test
    fun `loader does not raise when the chain was aborted or cancelled`() {
        assertFalse(raise(episode = 3, type = "series", chainAborted = true))
        assertFalse(raise(episode = 3, type = "series", cancelled = true))
    }

    @Test
    fun `loader does not raise when already showing`() {
        assertFalse(raise(episode = 3, type = "series", alreadyShowing = true))
    }

    @Test
    fun `loader does not raise when prefetch confirms a series finale`() {
        assertFalse(raise(episode = 12, type = "series", hasNextEpisode = false))
    }

    @Test
    fun `loader does not raise before next episode lookup completes`() {
        assertFalse(raise(episode = 12, type = "series", hasNextEpisode = null))
    }

    @Test
    fun `loader does not raise when auto-next is disabled`() {
        assertFalse(raise(episode = 3, type = "series", autoNextEnabled = false))
    }

    @Test
    fun `loader does not raise for movies or non-series content`() {
        assertFalse(raise(episode = null, type = "movie"))
        assertFalse(raise(episode = 3, type = "movie"))
    }

    // ---- shouldAttemptAdvance ----

    @Test
    fun `advance attempted for a season-less series episode`() {
        assertTrue(
            ExternalAutoNextPolicy.shouldAttemptAdvance(
                episode = 7, contentType = "series", cancelled = false, chainAborted = false
            )
        )
    }

    @Test
    fun `advance not attempted when aborted or for a movie`() {
        assertFalse(
            ExternalAutoNextPolicy.shouldAttemptAdvance(
                episode = 7, contentType = "series", cancelled = false, chainAborted = true
            )
        )
        assertFalse(
            ExternalAutoNextPolicy.shouldAttemptAdvance(
                episode = null, contentType = "movie", cancelled = false, chainAborted = false
            )
        )
    }

    @Test
    fun `next episode must be available and aired`() {
        assertTrue(ExternalAutoNextPolicy.isPlayableNextEpisode(available = true, hasAired = true))
        assertTrue(ExternalAutoNextPolicy.isPlayableNextEpisode(available = null, hasAired = true))
        assertFalse(ExternalAutoNextPolicy.isPlayableNextEpisode(available = false, hasAired = true))
        assertFalse(ExternalAutoNextPolicy.isPlayableNextEpisode(available = true, hasAired = false))
    }

    private fun raise(
        episode: Int?,
        type: String,
        cancelled: Boolean = false,
        chainAborted: Boolean = false,
        overlaySuppressed: Boolean = false,
        alreadyShowing: Boolean = false,
        autoNextEnabled: Boolean? = null,
        hasNextEpisode: Boolean? = true
    ) = ExternalAutoNextPolicy.shouldRaiseLoader(
        episode = episode,
        contentType = type,
        cancelled = cancelled,
        chainAborted = chainAborted,
        overlaySuppressed = overlaySuppressed,
        alreadyShowing = alreadyShowing,
        autoNextEnabled = autoNextEnabled,
        hasNextEpisode = hasNextEpisode
    )
}
