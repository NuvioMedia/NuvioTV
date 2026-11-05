package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TRACKING_COMPLETION_BAR_PERCENT
import com.nuvio.tv.core.tracking.TrackingScrobbleAction

/**
 * How Nuvio records rewatches on Simkl.
 *
 * Simkl requires an explicit opt-in for rewatch bookkeeping, so the default is [OFF].
 * [AUTOMATIC] sends `allow_rewatch=yes` on every finished playback, [SEMI_AUTOMATIC] asks once
 * before a playback that would open a run and records the rest of the run without asking.
 *
 * The mode only says how a run is started. A run the account holds is shown by Continue Watching
 * while either of the two recording modes is on, and never while it is [OFF].
 */
enum class SimklRewatchMode {
    OFF,
    SEMI_AUTOMATIC,
    AUTOMATIC,
    ;

    companion object {
        val Default: SimklRewatchMode = OFF

        fun fromStorage(value: String?): SimklRewatchMode {
            val normalized = value?.trim().orEmpty()
            // "MANUAL" is the mode the first version of the setting shipped as; it asked for the
            // same consent, only after the playback instead of before it, so the stored answer
            // keeps its meaning and no migration is needed.
            if (normalized.equals("MANUAL", ignoreCase = true)) return SEMI_AUTOMATIC
            return entries.firstOrNull { mode -> mode.name.equals(normalized, ignoreCase = true) } ?: Default
        }
    }
}

/** Simkl marks a title watched at this progress, and rewatches can only exist where a watch does. */
internal const val SIMKL_REWATCH_MIN_PROGRESS_PERCENT = 80.0

/**
 * The window the user can pick the completion threshold from.
 *
 * Simkl itself marks a playback watched at 80%, so the slider cannot start lower; above that the
 * user decides when Nuvio reports a finished playback, and therefore when Simkl is told about it.
 * The top of the window is the whole scale: the threshold rides on the call as `min_progress`, so
 * 100 means only a playback that reached the very end is reported as finished, and a stop there is
 * still counted by the account.
 */
internal const val SIMKL_WATCHED_THRESHOLD_MIN_PERCENT = 80
internal const val SIMKL_WATCHED_THRESHOLD_MAX_PERCENT = 100

internal val SimklWatchedThresholdRange: IntRange =
    SIMKL_WATCHED_THRESHOLD_MIN_PERCENT..SIMKL_WATCHED_THRESHOLD_MAX_PERCENT

/* The completion threshold is a parameter here; the scrobbler reads it from the store. */

internal fun coerceSimklWatchedThresholdPercent(percent: Int): Int = percent.coerceIn(
    minimumValue = SIMKL_WATCHED_THRESHOLD_MIN_PERCENT,
    maximumValue = SIMKL_WATCHED_THRESHOLD_MAX_PERCENT,
)

/**
 * Where a playback counts as finished, in percent, for everything Simkl is told about it.
 *
 * IntroDB carries the credits marker, and that is where the content really ends, so the marker is the
 * completion point whenever there is one: a playback that stopped before it has not finished, whatever
 * percentage the user set as their own floor. The percentage is the fallback, and it is what decides
 * for the items IntroDB knows nothing about.
 *
 * The marker arrives already read a few seconds early, the way the player reads it for the tracker
 * (`PlayerContentEndPolicy.CONTENT_END_TOLERANCE_SECONDS`), so this rule reads the number it is given.
 * Under what Simkl needs to record a watch it cannot be used at all, since a stop reported there is not
 * counted by the account, so the threshold decides instead. That is also why the threshold itself is
 * never read below that bar.
 */
internal fun resolvedSimklCompletionPercent(
    userThresholdPercent: Double,
    contentEndPercent: Double?,
): Double {
    val userThreshold = userThresholdPercent
        .takeIf { value -> value.isFinite() }
        ?.coerceAtLeast(SIMKL_REWATCH_MIN_PROGRESS_PERCENT)
        ?: SIMKL_REWATCH_MIN_PROGRESS_PERCENT
    val marker = contentEndPercent?.takeIf { value -> value.isFinite() } ?: return userThreshold
    if (marker < SIMKL_REWATCH_MIN_PROGRESS_PERCENT) return userThreshold
    return marker
}

/**
 * The number a playback row is read back with, as a fraction, from the stored threshold.
 *
 * The write side decides with [resolvedSimklCompletionPercent] and the credits marker of the playback
 * it is scrobbling; the reading side sees only the row the account kept, so the marker is not there.
 * What the row does carry is the percentage it was reported with, and the account keeps it open while
 * that sat under the threshold of the write. The number is therefore stored on the row, never below
 * the bar Simkl needs. Storing it does not decide the row: a row the account kept is a provider
 * playback position and never completes on a percentage, whatever number it was read with. For a row
 * that is not such a position the number is still the threshold the row is read with.
 *
 * A null means the setting could not be read: the caller leaves the field unset and the row keeps the
 * default of its own source.
 */
internal fun resolvedSimklCompletionFraction(userThresholdPercent: Int?): Float? =
    userThresholdPercent?.let { percent ->
        (resolvedSimklCompletionPercent(percent.toDouble(), contentEndPercent = null) / 100.0).toFloat()
    }

/** Simkl merges two watches of the same item this close together, so asking would be pointless. */
internal const val SIMKL_REWATCH_MIN_GAP_MS = 48L * 60L * 60L * 1_000L

/**
 * How long to wait before each read of the rewatch sessions, in order. Simkl publishes a session some
 * time after the write that created it, so the reads are spaced out. The prompt is answered by the
 * write itself, which is why these can be long: they only keep Continue Watching up to date.
 */
internal val SIMKL_REWATCH_SESSION_READ_DELAYS_MS = longArrayOf(3_000L, 10_000L, 25_000L)

/** How long to wait before each look at the account when the write itself came back as an error. */
internal val SIMKL_REWATCH_RECHECK_DELAYS_MS = longArrayOf(2_000L, 5_000L)

/** Query Simkl expects on the calls that are allowed to record a rewatch session. */
internal val SIMKL_ALLOW_REWATCH_QUERY: Map<String, String> = mapOf("allow_rewatch" to "yes")

/**
 * The completion threshold a scrobble call carries, as Simkl's `min_progress`.
 *
 * Simkl reads a whole number from 50 to 100 out of the query string, drops the decimals, counts
 * anything above 100 as 100 and anything under 50 as 50, and it ignores the key in the body. The
 * number is floored and clamped here, once, and then used both in the query and in the client's own
 * comparisons, so the account and the client judge a stop by the same number: what Simkl counts as
 * watched is what this app counts as finished, and what falls below it is the pause it reports.
 *
 * The value rides on the call it is sent with. On a stop it is where the stop turns into a watch; on
 * a start (and on a check-in) it is the bar the session being replaced has to have reached to be
 * completed, which is the same rule this client keeps: a playback left open below the threshold is
 * not finished, so a later start must not finish it. Simkl's own number is 80, which is below every
 * threshold this app can send.
 */
internal fun simklMinProgressPercent(completionThresholdPercent: Double): Int =
    completionThresholdPercent
        .takeIf { value -> value.isFinite() }
        ?.toInt()
        ?.coerceIn(SIMKL_MIN_PROGRESS_MIN_PERCENT, SIMKL_MIN_PROGRESS_MAX_PERCENT)
        ?: SIMKL_REWATCH_MIN_PROGRESS_PERCENT.toInt()

/** The bounds Simkl accepts for `min_progress`. */
internal const val SIMKL_MIN_PROGRESS_MIN_PERCENT = 50
internal const val SIMKL_MIN_PROGRESS_MAX_PERCENT = 100

/** The query key Simkl reads the completion threshold from. It only works in the URL. */
internal const val SIMKL_MIN_PROGRESS_QUERY_KEY = "min_progress"

/**
 * The query a scrobble call carries: the completion threshold the call is judged by, and the rewatch
 * flag where the call is one that may record a session.
 */
internal fun simklScrobbleQuery(
    completionThresholdPercent: Double,
    recordRewatch: Boolean,
): Map<String, String> = buildMap {
    put(SIMKL_MIN_PROGRESS_QUERY_KEY, simklMinProgressPercent(completionThresholdPercent).toString())
    if (recordRewatch) putAll(SIMKL_ALLOW_REWATCH_QUERY)
}

/**
 * Whether the playback behind a stop had a session for Simkl.
 *
 * A playback that began below the player's completion bar was reported from its start, and the
 * session is the one the stop ends. A playback that resumed above that bar is sessionless, but
 * Simkl's own completion point can be higher than the bar: a resume at 82 with a threshold of 87
 * is still an unfinished viewing and may open a rewatch when it ends. A start at or above the
 * threshold is the tail of a viewing the account already counted, and stays out.
 */
internal fun simklPlaybackHadSession(
    hasPlaybackSession: Boolean,
    startProgressPercent: Double?,
    completionThresholdPercent: Double,
): Boolean = hasPlaybackSession ||
    (startProgressPercent != null && startProgressPercent < completionThresholdPercent)

/**
 * Whether the scrobble call itself should carry `allow_rewatch=yes`.
 *
 * [AUTOMATIC] asks for a rewatch on every finished playback, as long as the playback had a session
 * the client reported from its start: a playback that began above the completion bar is the finish
 * of a viewing the account already has, not a viewing that can be repeated. [SEMI_AUTOMATIC] does it
 * only where the user said so: a run of the item is open on the account, or the question before the
 * playback was answered with yes. A semi-automatic write is therefore never the client guessing at a
 * run.
 */
internal fun shouldRecordSimklRewatchOnStop(
    mode: SimklRewatchMode,
    accountType: String?,
    action: TrackingScrobbleAction,
    progressPercent: Double,
    completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
    hasRunningSession: Boolean = false,
    consented: Boolean = false,
    hasPlaybackSession: Boolean = true,
): Boolean = when {
    mode == SimklRewatchMode.OFF -> false
    !isSimklRewatchPlanEligible(accountType) -> false
    action != TrackingScrobbleAction.STOP -> false
    !hasPlaybackSession -> false
    progressPercent < completionThresholdPercent -> false
    mode == SimklRewatchMode.AUTOMATIC -> true
    else -> hasRunningSession || consented
}

/**
 * Whether a manual mark of an item is written as a repeat viewing.
 *
 * A mark of something the account has already watched is a repeat, and Simkl records a plain add of it
 * as nothing at all: the write has to travel with the rewatch flag to land. A run the account already
 * holds makes the mark part of that run whatever the item's own state says, and a mark of something the
 * account has never watched stays a plain first watch, because there is nothing to repeat.
 *
 * The mode and the plan are the gates the scrobble path uses. A semi-automatic account does not ask
 * here: the user marked the episode watched themselves, so the question the mode would ask before a
 * playback has been answered by the mark. A free account is never sent the flag, because Simkl drops a
 * write carrying it silently and the watch would be lost with it.
 */
internal fun shouldRecordSimklRewatchOnMark(
    mode: SimklRewatchMode,
    accountType: String?,
    isRepeatViewing: Boolean,
    hasRunningSession: Boolean = false,
): Boolean = when {
    mode == SimklRewatchMode.OFF -> false
    !isSimklRewatchPlanEligible(accountType) -> false
    hasRunningSession -> true
    else -> isRepeatViewing
}

/**
 * Whether to ask, before a playback starts, whether it opens a new rewatch run.
 *
 * The question is only worth asking where Simkl would open a session for the playback: the plan has
 * to allow rewatches, the item has to be in the user's history already, and its last watch has to
 * be older than 48 hours, or Simkl folds the new one into it and a yes would change nothing. A run
 * that is already open never asks: the playback is simply written into it.
 *
 * Only [SimklRewatchMode.SEMI_AUTOMATIC] asks. The answer is carried to the end of the playback,
 * where it decides whether the scrobble asks Simkl for a rewatch (see [shouldRecordSimklRewatchOnStop]).
 */
internal fun shouldAskToStartSimklRewatch(
    mode: SimklRewatchMode,
    accountType: String?,
    action: TrackingScrobbleAction,
    priorWatch: SimklPriorWatch,
    nowEpochMs: Long,
    hasRunningSession: Boolean,
    progressPercent: Double = 0.0,
    hasPlaybackIdentity: Boolean = true,
): Boolean {
    if (mode != SimklRewatchMode.SEMI_AUTOMATIC) return false
    if (!isSimklRewatchPlanEligible(accountType)) return false
    // An external player sends its start and stop back to back after it already closed, so a
    // question raised by that start cannot be answered before the playback it is about. Such a
    // start carries no playback id.
    if (!hasPlaybackIdentity) return false
    if (action != TrackingScrobbleAction.START) return false
    if (hasRunningSession) return false
    // A start the player sends above the completion bar says that a playback that was already under
    // way is running again, not that a viewing is beginning: the question belongs before a playback,
    // and this one was asked when it began. The trackers still hear about the playback, which is why
    // the start itself travels.
    if (progressPercent >= TRACKING_COMPLETION_BAR_PERCENT) return false
    if (!priorWatch.wasWatched) return false
    val watchedAt = priorWatch.watchedAtEpochMs ?: return true
    return nowEpochMs - watchedAt >= SIMKL_REWATCH_MIN_GAP_MS
}

/** What the local Simkl snapshot knew about the scrobbled item before this playback. */
internal data class SimklPriorWatch(
    val wasWatched: Boolean,
    val watchedAtEpochMs: Long?,
) {
    companion object {
        val None = SimklPriorWatch(wasWatched = false, watchedAtEpochMs = null)
    }
}

/**
 * Simkl Pro / VIP only. Unknown plans stay ineligible: sending the flag for a free account would
 * consume a rate-limit slot and be dropped server-side.
 */
internal fun isSimklRewatchPlanEligible(accountType: String?): Boolean {
    val normalized = accountType?.trim()?.lowercase().orEmpty()
    return normalized == "pro" || normalized == "vip"
}

/** The plan Simkl reports for an account without rewatches; the cached plan is corrected to it. */
internal const val SIMKL_FREE_PLAN = "free"

/** Free-tier accounts cannot record rewatches, so the picker keeps them off and offers an upgrade. */
internal fun isSimklRewatchModeSelectable(
    mode: SimklRewatchMode,
    accountType: String?,
): Boolean = mode == SimklRewatchMode.OFF || isSimklRewatchPlanEligible(accountType)
