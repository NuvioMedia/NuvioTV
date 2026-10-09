package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings this feature adds and what each combination of them does: the completion threshold, the
 * rewatch mode and the plan it needs.
 *
 * The write on the scrobble and the question raised before a playback are the two places a rewatch can
 * be recorded, so both are covered here as the gates they are: the mode, the plan, the action, whether
 * the playback finished, and whether the item is a repeat viewing at all.
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
        assertEquals(SimklRewatchMode.OFF, SimklRewatchMode.fromStorage(null))
        assertEquals(SimklRewatchMode.OFF, SimklRewatchMode.fromStorage("nonsense"))
        assertEquals(SimklRewatchMode.AUTOMATIC, SimklRewatchMode.fromStorage("automatic"))
        assertEquals(SimklRewatchMode.SEMI_AUTOMATIC, SimklRewatchMode.fromStorage("semi_automatic"))
        // The mode the setting shipped as before it asked before the playback: the answer the user
        // stored is still an answer, so it is read as the mode that asks.
        assertEquals(SimklRewatchMode.SEMI_AUTOMATIC, SimklRewatchMode.fromStorage(" MANUAL "))
    }

    @Test
    fun `automatic mode writes on every finished stop, semi only where the user said so`() {
        assertTrue(record(SimklRewatchMode.AUTOMATIC, "pro"))
        assertTrue(record(SimklRewatchMode.AUTOMATIC, " VIP "))

        // The plan decides, and every mode leaves the flag off without one.
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "free"))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, null))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "unconfirmed"))
        assertFalse(record(SimklRewatchMode.SEMI_AUTOMATIC, "pro"))
        assertFalse(record(SimklRewatchMode.OFF, "pro"))

        // Semi-automatic writes in two cases, and only those: the account runs the item already, or
        // the user answered the question before the playback with yes. Never as a guess of its own.
        assertTrue(record(SimklRewatchMode.SEMI_AUTOMATIC, "pro", hasRunningSession = true))
        assertTrue(record(SimklRewatchMode.SEMI_AUTOMATIC, "pro", consented = true))

        // The gates every mode shares still hold for both of them.
        assertFalse(record(SimklRewatchMode.SEMI_AUTOMATIC, "pro", consented = true, progressPercent = 79.0))
        assertFalse(record(SimklRewatchMode.SEMI_AUTOMATIC, "free", consented = true))
        assertFalse(record(SimklRewatchMode.SEMI_AUTOMATIC, "pro", consented = true, action = TrackingScrobbleAction.PAUSE))
    }

    @Test
    fun `only a finished stop carries the flag`() {
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "pro", action = TrackingScrobbleAction.PAUSE))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "pro", action = TrackingScrobbleAction.START))
        assertFalse(record(SimklRewatchMode.AUTOMATIC, "pro", progressPercent = 79.0))
        assertFalse(record(SimklRewatchMode.SEMI_AUTOMATIC, "pro", consented = true, action = TrackingScrobbleAction.START))
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
    fun `only semi-automatic mode asks, and only before a playback that opens a run`() {
        val nowEpochMs = 10_000_000_000L
        val olderThanTheGap = nowEpochMs - SIMKL_REWATCH_MIN_GAP_MS - 1L
        val insideTheGap = nowEpochMs - SIMKL_REWATCH_MIN_GAP_MS + 1L
        val exactlyOnTheGap = nowEpochMs - SIMKL_REWATCH_MIN_GAP_MS
        val watchedLongAgo = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = olderThanTheGap)

        assertTrue(ask(mode = SimklRewatchMode.SEMI_AUTOMATIC, accountType = "pro", priorWatch = watchedLongAgo))
        assertTrue(
            ask(
                mode = SimklRewatchMode.SEMI_AUTOMATIC,
                accountType = "pro",
                priorWatch = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = exactlyOnTheGap),
            ),
        )

        // The other modes never ask, and neither does a plan that cannot record a rewatch.
        assertFalse(ask(mode = SimklRewatchMode.OFF, accountType = "pro", priorWatch = watchedLongAgo))
        assertFalse(ask(mode = SimklRewatchMode.AUTOMATIC, accountType = "pro", priorWatch = watchedLongAgo))
        assertFalse(ask(mode = SimklRewatchMode.SEMI_AUTOMATIC, accountType = "free", priorWatch = watchedLongAgo))
        assertFalse(ask(mode = SimklRewatchMode.SEMI_AUTOMATIC, accountType = null, priorWatch = watchedLongAgo))

        // A run the account already holds is never asked about: the playback is written into it.
        assertFalse(
            ask(
                mode = SimklRewatchMode.SEMI_AUTOMATIC,
                accountType = "pro",
                priorWatch = watchedLongAgo,
                hasRunningSession = true,
            ),
        )

        // Only a playback that is beginning asks. A stop is where the answer is used, not where it
        // is collected.
        assertFalse(
            ask(
                mode = SimklRewatchMode.SEMI_AUTOMATIC,
                accountType = "pro",
                priorWatch = watchedLongAgo,
                action = TrackingScrobbleAction.STOP,
            ),
        )

        // An item the account does not hold is not a repeat viewing at all.
        assertFalse(
            ask(mode = SimklRewatchMode.SEMI_AUTOMATIC, accountType = "pro", priorWatch = SimklPriorWatch.None),
        )

        // Simkl folds a watch from the last two days into the session it already has, so a yes would
        // do nothing. A row with no timestamp is asked about, since nothing says otherwise.
        assertFalse(
            ask(
                mode = SimklRewatchMode.SEMI_AUTOMATIC,
                accountType = "pro",
                priorWatch = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = insideTheGap),
            ),
        )
        assertTrue(
            ask(
                mode = SimklRewatchMode.SEMI_AUTOMATIC,
                accountType = "pro",
                priorWatch = SimklPriorWatch(wasWatched = true, watchedAtEpochMs = null),
            ),
        )
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
        assertFalse(isSimklRewatchModeSelectable(SimklRewatchMode.SEMI_AUTOMATIC, "free"))
        assertFalse(isSimklRewatchModeSelectable(SimklRewatchMode.AUTOMATIC, null))
        assertTrue(isSimklRewatchModeSelectable(SimklRewatchMode.SEMI_AUTOMATIC, "pro"))
        assertTrue(isSimklRewatchModeSelectable(SimklRewatchMode.AUTOMATIC, "vip"))
    }

    private fun record(
        mode: SimklRewatchMode,
        accountType: String?,
        action: TrackingScrobbleAction = TrackingScrobbleAction.STOP,
        progressPercent: Double = 95.0,
        completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
        hasRunningSession: Boolean = false,
        consented: Boolean = false,
    ): Boolean = shouldRecordSimklRewatchOnStop(
        mode = mode,
        accountType = accountType,
        action = action,
        progressPercent = progressPercent,
        completionThresholdPercent = completionThresholdPercent,
        hasRunningSession = hasRunningSession,
        consented = consented,
    )

    private fun ask(
        mode: SimklRewatchMode,
        accountType: String?,
        priorWatch: SimklPriorWatch,
        nowEpochMs: Long = 10_000_000_000L,
        action: TrackingScrobbleAction = TrackingScrobbleAction.START,
        hasRunningSession: Boolean = false,
    ): Boolean = shouldAskToStartSimklRewatch(
        mode = mode,
        accountType = accountType,
        action = action,
        priorWatch = priorWatch,
        nowEpochMs = nowEpochMs,
        hasRunningSession = hasRunningSession,
    )
}
