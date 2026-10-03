package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings this feature adds and what each combination of them does: the completion threshold, the
 * rewatch mode, the plan it needs, and the next-up mode that reads the sessions back.
 *
 * The write on the scrobble and the question asked afterwards are the two places a rewatch can be
 * recorded, so both are covered here as the gates they are: the mode, the plan, the action, whether the
 * playback finished, and whether the item is a repeat viewing at all.
 */
class SimklRewatchPolicyTest {

    @Test
    fun `the threshold is kept inside the range the account accepts`() {
        assertEquals(80, coerceSimklWatchedThresholdPercent(70))
        assertEquals(80, coerceSimklWatchedThresholdPercent(80))
        assertEquals(85, coerceSimklWatchedThresholdPercent(85))
        assertEquals(95, coerceSimklWatchedThresholdPercent(95))
        assertEquals(95, coerceSimklWatchedThresholdPercent(99))
        assertEquals(80, SimklWatchedThresholdRange.first)
        assertEquals(95, SimklWatchedThresholdRange.last)
    }

    @Test
    fun `rewatch bookkeeping is off until the user asks for it`() {
        assertEquals(SimklRewatchMode.OFF, SimklRewatchMode.Default)
        assertEquals(SimklRewatchNextUpMode.ALWAYS, SimklRewatchNextUpMode.Default)
        assertEquals(SimklRewatchMode.OFF, SimklRewatchMode.fromStorage(null))
        assertEquals(SimklRewatchMode.OFF, SimklRewatchMode.fromStorage("nonsense"))
        assertEquals(SimklRewatchMode.AUTOMATIC, SimklRewatchMode.fromStorage("automatic"))
        assertEquals(SimklRewatchMode.MANUAL, SimklRewatchMode.fromStorage(" MANUAL "))
    }

    @Test
    fun `only automatic mode on a plan that allows it writes a rewatch on a finished stop`() {
        assertTrue(record(SimklRewatchMode.AUTOMATIC, "pro"))
        assertTrue(record(SimklRewatchMode.AUTOMATIC, " VIP "))

        // The plan decides, and every other mode leaves the flag off so nothing is written server side.
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "free"))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, null))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "unconfirmed"))
        assertFalse(record(SimklRewatchMode.MANUAL, "pro"))
        assertFalse(record(SimklRewatchMode.OFF, "pro"))
    }

    @Test
    fun `only a finished stop carries the flag`() {
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "pro", action = TrackingScrobbleAction.PAUSE))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "pro", action = TrackingScrobbleAction.START))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "pro", progressPercent = 79.0))
        assertTrue(record(SimklRewatchMode.AUTOMATIC, "pro", progressPercent = 80.0))

        // The threshold is a parameter, not a constant: the same stop passes or fails with it.
        assertFalse(
            record(
                mode = SimklRewatchMode.AUTOMATIC,
                accountType = "pro",
                progressPercent = 89.0,
                completionThresholdPercent = 90.0,
            ),
        )
        assertTrue(
            record(
                mode = SimklRewatchMode.AUTOMATIC,
                accountType = "pro",
                progressPercent = 90.0,
                completionThresholdPercent = 90.0,
            ),
        )
    }

    @Test
    fun `a stop before the credits is not a finished stop, whatever the user bar`() {
        // The threshold says 80 and the playback stopped at 85, but the credits start at 93, so the
        // playback is not over and no rewatch is written for it.
        val completion = resolvedSimklCompletionPercent(userThresholdPercent = 80.0, contentEndPercent = 93.0)

        assertEquals(92.0, completion, 0.0001)
        assertFalse(
            record(
                mode = SimklRewatchMode.AUTOMATIC,
                accountType = "pro",
                progressPercent = 85.0,
                completionThresholdPercent = completion,
            ),
        )
        assertTrue(
            record(
                mode = SimklRewatchMode.AUTOMATIC,
                accountType = "pro",
                progressPercent = 92.0,
                completionThresholdPercent = completion,
            ),
        )
    }

    @Test
    fun `the credits marker wins and is read one point early`() {
        // Marker above the user bar: the marker decides.
        assertEquals(
            92.0,
            resolvedSimklCompletionPercent(userThresholdPercent = 80.0, contentEndPercent = 93.0),
            0.0001,
        )
        // Marker below the user bar but above what Simkl needs: the marker still decides.
        assertEquals(
            84.0,
            resolvedSimklCompletionPercent(userThresholdPercent = 92.0, contentEndPercent = 85.0),
            0.0001,
        )
        // A user bar under the Simkl bar is raised to it.
        assertEquals(
            80.0,
            resolvedSimklCompletionPercent(userThresholdPercent = 40.0, contentEndPercent = null),
            0.0001,
        )
    }

    @Test
    fun `a marker that does not survive the tolerance above the Simkl bar is dropped`() {
        // 80.5 minus the tolerance is 79.5, under what Simkl needs to count a watch, so the user bar
        // decides instead of a marker that would never be able to record anything.
        assertEquals(
            85.0,
            resolvedSimklCompletionPercent(userThresholdPercent = 85.0, contentEndPercent = 80.5),
            0.0001,
        )
        assertEquals(
            85.0,
            resolvedSimklCompletionPercent(userThresholdPercent = 85.0, contentEndPercent = 80.0),
            0.0001,
        )
        // Exactly on the bar after the tolerance is still usable.
        assertEquals(
            80.0,
            resolvedSimklCompletionPercent(userThresholdPercent = 95.0, contentEndPercent = 81.0),
            0.0001,
        )
        // No marker at all: the user bar is the completion point.
        assertEquals(
            90.0,
            resolvedSimklCompletionPercent(userThresholdPercent = 90.0, contentEndPercent = null),
            0.0001,
        )
    }

    @Test
    fun `a nonsense threshold falls back to the Simkl bar`() {
        // A threshold that cannot be read is replaced by the bar itself, which is the percentage a
        // stop has to reach for Simkl to count the watch at all.
        assertEquals(80.0, resolvedSimklCompletionPercent(Double.NaN, null), 0.0001)
        assertEquals(80.0, resolvedSimklCompletionPercent(Double.POSITIVE_INFINITY, 79.0), 0.0001)
        assertEquals(80.0, resolvedSimklCompletionPercent(Double.NEGATIVE_INFINITY, 79.0), 0.0001)
        // Only the threshold is replaced. The credits marker is where the content really ends, so a
        // marker that survives the tolerance decides whatever the threshold was.
        assertEquals(92.0, resolvedSimklCompletionPercent(Double.POSITIVE_INFINITY, 93.0), 0.0001)
        assertEquals(92.0, resolvedSimklCompletionPercent(Double.NEGATIVE_INFINITY, 93.0), 0.0001)
        // A marker that cannot be read is dropped and the user bar decides instead.
        assertEquals(90.0, resolvedSimklCompletionPercent(90.0, Double.NaN), 0.0001)
        assertEquals(90.0, resolvedSimklCompletionPercent(90.0, Double.POSITIVE_INFINITY), 0.0001)
    }

    @Test
    fun `only manual mode asks, and only for a repeat viewing Simkl would keep`() {
        val nowEpochMs = 10_000_000_000L
        val olderThanTheGap = nowEpochMs - SIMKL_REWATCH_MIN_GAP_MS - 1L
        val insideTheGap = nowEpochMs - SIMKL_REWATCH_MIN_GAP_MS + 1L
        val exactlyOnTheGap = nowEpochMs - SIMKL_REWATCH_MIN_GAP_MS
        val watchedLongAgo = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = olderThanTheGap)

        assertTrue(ask(mode = SimklRewatchMode.MANUAL, accountType = "pro", priorWatch = watchedLongAgo))
        assertTrue(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = exactlyOnTheGap),
            ),
        )

        // The other modes never ask, and neither does a plan that cannot record a rewatch.
        assertFalse(ask(mode = SimklRewatchMode.OFF, accountType = "pro", priorWatch = watchedLongAgo))
        assertFalse(ask(mode = SimklRewatchMode.AUTOMATIC, accountType = "pro", priorWatch = watchedLongAgo))
        assertFalse(ask(mode = SimklRewatchMode.MANUAL, accountType = "free", priorWatch = watchedLongAgo))
        assertFalse(ask(mode = SimklRewatchMode.MANUAL, accountType = null, priorWatch = watchedLongAgo))

        // Only a finished stop that Simkl accepted as a scrobble is worth asking about.
        assertFalse(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = watchedLongAgo,
                action = TrackingScrobbleAction.PAUSE,
            ),
        )
        assertFalse(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = watchedLongAgo,
                outcome = SimklScrobbleOutcome.PAUSE,
            ),
        )
        assertFalse(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = watchedLongAgo,
                progressPercent = 79.0,
            ),
        )
        // The same gate the write uses: credits at 93 mean a stop at 85 has not finished.
        assertFalse(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = watchedLongAgo,
                progressPercent = 85.0,
                completionThresholdPercent = 92.0,
            ),
        )

        // An item the account does not hold is not a repeat viewing at all.
        assertFalse(ask(mode = SimklRewatchMode.MANUAL, accountType = "pro", priorWatch = SimklPriorWatch.None))

        // Simkl folds a watch from the last two days into the session it already has, so a confirmation
        // would do nothing. A row with no timestamp is asked about, since nothing says otherwise.
        assertFalse(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = insideTheGap),
            ),
        )
        assertTrue(
            ask(
                mode = SimklRewatchMode.MANUAL,
                accountType = "pro",
                priorWatch = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = null),
            ),
        )
    }

    @Test
    fun `the next-up setting decides how much of a run is read back`() {
        assertEquals(1, SimklRewatchNextUpMode.ALWAYS.minimumRunEpisodes)
        assertEquals(2, SimklRewatchNextUpMode.AFTER_TWO.minimumRunEpisodes)
        assertNull(SimklRewatchNextUpMode.NEVER.minimumRunEpisodes)
        assertEquals(SimklRewatchNextUpMode.AFTER_TWO, SimklRewatchNextUpMode.fromStorage("after_two"))
        assertEquals(SimklRewatchNextUpMode.NEVER, SimklRewatchNextUpMode.fromStorage("Never"))
        assertEquals(SimklRewatchNextUpMode.ALWAYS, SimklRewatchNextUpMode.fromStorage("nonsense"))
        assertEquals(SimklRewatchNextUpMode.ALWAYS, SimklRewatchNextUpMode.fromStorage(null))
    }

    @Test
    fun `rewatches need a plan that allows them, and a free account keeps the picker on off`() {
        assertTrue(isSimklRewatchPlanEligible("pro"))
        assertTrue(isSimklRewatchPlanEligible(" VIP "))
        assertFalse(isSimklRewatchPlanEligible("free"))
        assertFalse(isSimklRewatchPlanEligible(""))
        assertFalse(isSimklRewatchPlanEligible(null))

        // Off is always available, because turning it off never needs the plan.
        assertTrue(isSimklRewatchModeSelectable(SimklRewatchMode.OFF, "free"))
        assertTrue(isSimklRewatchModeSelectable(SimklRewatchMode.OFF, null))
        assertFalse(isSimklRewatchModeSelectable(SimklRewatchMode.MANUAL, "free"))
        assertFalse(isSimklRewatchModeSelectable(SimklRewatchMode.AUTOMATIC, null))
        assertTrue(isSimklRewatchModeSelectable(SimklRewatchMode.MANUAL, "pro"))
        assertTrue(isSimklRewatchModeSelectable(SimklRewatchMode.AUTOMATIC, "vip"))
    }

    private fun record(
        mode: SimklRewatchMode,
        accountType: String?,
        action: TrackingScrobbleAction = TrackingScrobbleAction.STOP,
        progressPercent: Double = 95.0,
        completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
    ): Boolean = shouldRecordSimklRewatchOnStop(
        mode = mode,
        accountType = accountType,
        action = action,
        progressPercent = progressPercent,
        completionThresholdPercent = completionThresholdPercent,
    )

    private fun ask(
        mode: SimklRewatchMode,
        accountType: String?,
        priorWatch: SimklPriorWatch,
        nowEpochMs: Long = 10_000_000_000L,
        action: TrackingScrobbleAction = TrackingScrobbleAction.STOP,
        outcome: SimklScrobbleOutcome = SimklScrobbleOutcome.SCROBBLE,
        progressPercent: Double = 95.0,
        completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
    ): Boolean = shouldPromptSimklRewatch(
        mode = mode,
        accountType = accountType,
        action = action,
        outcome = outcome,
        progressPercent = progressPercent,
        priorWatch = priorWatch,
        nowEpochMs = nowEpochMs,
        completionThresholdPercent = completionThresholdPercent,
    )
}
