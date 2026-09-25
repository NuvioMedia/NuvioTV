package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingScrobbleAction

/**
 * How Nuvio records rewatches on Simkl.
 *
 * Simkl requires an explicit opt-in for rewatch bookkeeping, so the default is [OFF].
 * [AUTOMATIC] sends `allow_rewatch=yes` on every finished playback, [MANUAL] asks after the
 * playback instead and only writes when the user confirms.
 */
enum class SimklRewatchMode {
    OFF,
    MANUAL,
    AUTOMATIC,
    ;

    companion object {
        val Default: SimklRewatchMode = OFF

        fun fromStorage(value: String?): SimklRewatchMode {
            val normalized = value?.trim().orEmpty()
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
 */
internal const val SIMKL_WATCHED_THRESHOLD_MIN_PERCENT = 80
internal const val SIMKL_WATCHED_THRESHOLD_MAX_PERCENT = 95

internal val SimklWatchedThresholdRange: IntRange =
    SIMKL_WATCHED_THRESHOLD_MIN_PERCENT..SIMKL_WATCHED_THRESHOLD_MAX_PERCENT

/*
 * Difference from mobile: mobile has a top level `simklWatchedThresholdPercent` getter that reads
 * `TrackingSettingsRepository.uiState.value`. TV has no such `object` repository for UI state, only
 * `TraktSettingsDataStore`, so the threshold is not read here. It is passed in as a parameter and
 * the scrobbler reads it from the store.
 */

internal fun coerceSimklWatchedThresholdPercent(percent: Int): Int = percent.coerceIn(
    minimumValue = SIMKL_WATCHED_THRESHOLD_MIN_PERCENT,
    maximumValue = SIMKL_WATCHED_THRESHOLD_MAX_PERCENT,
)

/*
 * How early the content end marker is read. Mobile has it as `ContentEndTolerancePercent` in
 * `features/watching/domain/WatchingPolicies.kt`; TV has no such file, so the value is carried over
 * here to keep the rule in one place with the rest of the rewatch policy.
 */
internal const val SIMKL_CONTENT_END_TOLERANCE_PERCENT = 1.0

/**
 * Where a playback counts as finished, in percent, for everything Simkl is told about it.
 *
 * IntroDB carries the credits marker, and that is where the content really ends, so the marker is the
 * completion point whenever there is one: a playback that stopped before it has not finished, whatever
 * percentage the user set as their own floor. The percentage is the fallback, and it is what decides
 * for the items IntroDB knows nothing about.
 *
 * The marker is read one point early, so a timestamp that is a few seconds off does not leave a finished
 * playback reported as unfinished. Under what Simkl needs to record a watch it cannot be used at all,
 * since a stop reported there is not counted by the account, so the threshold decides instead. That is
 * also why the threshold itself is never read below that bar.
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
    val markerWithTolerance = marker - SIMKL_CONTENT_END_TOLERANCE_PERCENT
    if (markerWithTolerance < SIMKL_REWATCH_MIN_PROGRESS_PERCENT) return userThreshold
    return markerWithTolerance
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
 * Whether the scrobble call itself should carry `allow_rewatch=yes`. Only automatic mode does that,
 * because a confirmed rewatch is written after the fact through `/sync/history`.
 */
internal fun shouldRecordSimklRewatchOnStop(
    mode: SimklRewatchMode,
    accountType: String?,
    action: TrackingScrobbleAction,
    progressPercent: Double,
    completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
): Boolean = when {
    mode != SimklRewatchMode.AUTOMATIC -> false
    !isSimklRewatchPlanEligible(accountType) -> false
    action != TrackingScrobbleAction.STOP -> false
    progressPercent < completionThresholdPercent -> false
    else -> true
}

/**
 * Whether the user should be asked to record the playback Simkl just accepted as a repeat viewing.
 *
 * The question is only worth asking when Simkl would create a session for it: the plan has to allow
 * rewatches and the item has to be in the user's history already. A watch from the last 48 hours is
 * skipped, because Simkl folds those into the existing session and a confirmation would do nothing.
 */
internal fun shouldPromptSimklRewatch(
    mode: SimklRewatchMode,
    accountType: String?,
    action: TrackingScrobbleAction,
    outcome: SimklScrobbleOutcome,
    progressPercent: Double,
    priorWatch: SimklPriorWatch,
    nowEpochMs: Long,
    completionThresholdPercent: Double = SIMKL_REWATCH_MIN_PROGRESS_PERCENT,
): Boolean {
    if (mode != SimklRewatchMode.MANUAL) return false
    if (!isSimklRewatchPlanEligible(accountType)) return false
    if (action != TrackingScrobbleAction.STOP) return false
    if (outcome != SimklScrobbleOutcome.SCROBBLE) return false
    if (progressPercent < completionThresholdPercent) return false
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

/** Free-tier accounts cannot record rewatches, so the picker keeps them off and offers an upgrade. */
internal fun isSimklRewatchModeSelectable(
    mode: SimklRewatchMode,
    accountType: String?,
): Boolean = mode == SimklRewatchMode.OFF || isSimklRewatchPlanEligible(accountType)
