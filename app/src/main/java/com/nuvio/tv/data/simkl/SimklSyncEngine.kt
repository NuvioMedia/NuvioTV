package com.nuvio.tv.data.simkl

import com.nuvio.tv.data.local.TraktSettingsDataStore
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class SimklSyncEngine internal constructor(
    private val remote: SimklSyncRemote,
    private val settingsDataStore: TraktSettingsDataStore,
    private val authStorage: SimklAuthStorage,
    private val nowEpochMs: () -> Long
) {
    @Inject
    constructor(
        remote: SimklSyncRemote,
        settingsDataStore: TraktSettingsDataStore,
        authStorage: SimklAuthStorage
    ) : this(remote, settingsDataStore, authStorage, System::currentTimeMillis)

    suspend fun synchronize(current: SimklSyncSnapshot): SimklSyncSnapshot {
        if (!current.isInitialized) return initialSync()

        val activities = remote.fetchActivities()
        // A snapshot whose sessions were never read is completed even when nothing else changed:
        // this is the first sync after the feature was turned on, or after the app update that
        // introduced it, and the run row must not wait for the next activity to fill in.
        val needsRewatchRead = activities.all == current.watermark &&
            !current.rewatchSessionsComplete &&
            settingsDataStore.simklRewatchMode.first() != SimklRewatchMode.OFF &&
            isSimklRewatchPlanEligible(authStorage.state.value.accountType)
        if (activities.all == current.watermark && !needsRewatchRead) {
            return current.copy(
                activities = activities,
                lastCheckedAtEpochMs = nowEpochMs()
            )
        }
        if (current.watermark == null) return initialSync()

        var entries = current.entries
        if (hasAllItemsActivityChanged(current.activities, activities)) {
            val delta = remote.fetchAllItems(SimklAllItemsRequest.Changes(current.watermark))
            entries = mergeSimklDelta(entries, delta)
        }
        if (hasRemovalActivityChanged(current.activities, activities)) {
            entries = reconcileRemovedSimklEntries(
                entries,
                remote.fetchAllItems(SimklAllItemsRequest.CurrentIds)
            )
        }
        val playback = if (hasPlaybackActivityChanged(current.activities, activities)) {
            remote.fetchPlayback()
        } else {
            current.playback
        }
        val now = nowEpochMs()
        /*
         * A rewatch session is opened or closed by a watch of a show or an anime, so a sync whose
         * only changes sit in the movie or playback domains cannot have moved one. Keeping the
         * stored sessions saves the rate-limit slot the read would spend.
         */
        val rewatch = if (
            needsRewatchRead ||
            !current.rewatchSessionsComplete ||
            hasRewatchActivityChanged(current.activities, activities)
        ) {
            readRewatchRuns(current)
        } else {
            SimklRewatchRead(
                runs = current.rewatchRuns,
                sessions = current.rewatchSessions,
                sessionsComplete = current.rewatchSessionsComplete
            )
        }
        return current.copy(
            watermark = activities.all,
            activities = activities,
            entries = entries,
            playback = playback,
            rewatchRuns = rewatch.runs,
            rewatchSessions = rewatch.sessions,
            rewatchSessionsComplete = rewatch.sessionsComplete,
            lastSyncedAtEpochMs = now,
            lastCheckedAtEpochMs = now
        ).reconcileWatchedPlayback()
    }

    private suspend fun initialSync(): SimklSyncSnapshot {
        val entries = buildList {
            SimklMediaType.entries.forEach { type ->
                addAll(remote.fetchAllItems(SimklAllItemsRequest.Bootstrap(type)).entriesFor(type))
            }
        }
        val playback = remote.fetchPlayback()
        val activities = remote.fetchActivities()
        val now = nowEpochMs()
        val rewatch = readRewatchRuns(current = null)
        return SimklSyncSnapshot(
            isInitialized = true,
            watermark = activities.all,
            activities = activities,
            entries = entries.distinctBy(SimklLibraryEntry::stableKey),
            playback = playback,
            rewatchRuns = rewatch.runs,
            rewatchSessions = rewatch.sessions,
            rewatchSessionsComplete = rewatch.sessionsComplete,
            lastSyncedAtEpochMs = now,
            lastCheckedAtEpochMs = now
        ).reconcileWatchedPlayback()
    }

    /**
     * Reads the rewatch sessions of the account, keeps them, and turns them into runs.
     *
     * While rewatches cannot be recorded the read is skipped: the sessions would matter to nothing,
     * and pulling the whole show library on every sync for a user who never turns the feature on is
     * not worth it. A skipped read drops the runs the snapshot still carries only when the reason is
     * known and final: the mode is off, or the plan has loaded and cannot hold sessions. A plan that
     * has not loaded yet keeps them, so starting the app does not blank the row. A failed read keeps
     * what the previous sync found: losing the network must not empty the Continue Watching cards the
     * user is looking at.
     */
    private suspend fun readRewatchRuns(current: SimklSyncSnapshot?): SimklRewatchRead {
        val mode = settingsDataStore.simklRewatchMode.first()
        val accountType = authStorage.state.value.accountType
        if (mode == SimklRewatchMode.OFF || !isSimklRewatchPlanEligible(accountType)) {
            val knownToHoldNoSessions = mode == SimklRewatchMode.OFF || accountType != null
            return if (knownToHoldNoSessions) {
                SimklRewatchRead(
                    runs = emptyList(),
                    sessions = emptyList(),
                    sessionsComplete = false
                )
            } else {
                SimklRewatchRead(
                    runs = current?.rewatchRuns.orEmpty(),
                    sessions = current?.rewatchSessions.orEmpty(),
                    sessionsComplete = current?.rewatchSessionsComplete ?: false
                )
            }
        }
        // The guide's rule for this read: every read outside the initial sync pairs date_from, so the
        // server answers with the changed slice instead of the whole library. That slice mentions only
        // the items that changed, and the runs need every session the account holds, so it is merged
        // into the sessions already on the snapshot.
        // The watermark is only paired once the stored sessions are known to be the whole set. A
        // cold read is the feature's own initial pull, and it is the one read the guide allows to go
        // without date_from; the flag makes it happen once, not on every sync.
        val dateFrom = current
            ?.takeIf { snapshot -> snapshot.rewatchSessionsComplete }
            ?.watermark
        return runCatching {
            val sessions = mergeRewatchSessions(
                read = remote.fetchRewatchSessions(dateFrom = dateFrom),
                stored = current?.rewatchSessions.orEmpty()
            )
            SimklRewatchRead(
                runs = deriveSimklRewatchRuns(
                    entries = sessions,
                    offerRuns = true
                ),
                sessions = sessions,
                sessionsComplete = true
            )
        }.getOrElse {
            // The window the read failed on was never read, and the sync still advances the
            // watermark, so the flag has to force one cold read instead of letting the next delta
            // skip that window for good.
            SimklRewatchRead(
                runs = current?.rewatchRuns.orEmpty(),
                sessions = current?.rewatchSessions.orEmpty(),
                sessionsComplete = false
            )
        }
    }
}

fun mergeSimklDelta(
    current: List<SimklLibraryEntry>,
    delta: SimklAllItemsResponse
): List<SimklLibraryEntry> {
    val merged = current.mapNotNull { entry -> entry.stableKey()?.let { key -> key to entry } }
        .toMap()
        .toMutableMap()
    val deltaEntries = mutableListOf<SimklLibraryEntry>()
    delta.presentTypes().forEach { type ->
        delta.entriesFor(type).forEach { entry ->
            entry.stableKey()?.let { key -> merged[key] = entry }
            deltaEntries += entry
        }
    }
    deltaEntries.forEach { deltaEntry ->
        val deltaMedia = deltaEntry.media ?: return@forEach
        merged.entries.removeIf { (key, existing) ->
            key != deltaEntry.stableKey() &&
                existing.mediaType == deltaEntry.mediaType &&
                existing.media?.matchesTarget(deltaMedia) == true
        }
    }
    return merged.values.sortedWith(simklEntryComparator)
}

fun reconcileRemovedSimklEntries(
    current: List<SimklLibraryEntry>,
    authoritative: SimklAllItemsResponse
): List<SimklLibraryEntry> {
    val allowedKeys = SimklMediaType.entries.flatMapTo(mutableSetOf()) { type ->
        authoritative.entriesFor(type).mapNotNull(SimklLibraryEntry::stableKey)
    }
    return current.filter { entry -> entry.stableKey() in allowedKeys }
        .sortedWith(simklEntryComparator)
}

private fun hasAllItemsActivityChanged(previous: SimklActivities?, current: SimklActivities): Boolean {
    if (previous == null) return true
    return SimklMediaType.entries.any { type ->
        current.domain(type).hasAllItemsActivityChangedFrom(previous.domain(type))
    }
}

private fun SimklActivityDomain.hasAllItemsActivityChangedFrom(previous: SimklActivityDomain): Boolean =
    ratedAt != previous.ratedAt ||
        plantowatch != previous.plantowatch ||
        watching != previous.watching ||
        completed != previous.completed ||
        hold != previous.hold ||
        dropped != previous.dropped

private fun hasRemovalActivityChanged(previous: SimklActivities?, current: SimklActivities): Boolean =
    previous == null ||
    SimklMediaType.entries.any { type ->
        previous.domain(type).removedFromList != current.domain(type).removedFromList
    }

private fun hasPlaybackActivityChanged(previous: SimklActivities?, current: SimklActivities): Boolean =
    previous == null ||
    SimklMediaType.entries.any { type ->
        previous.domain(type).playback != current.domain(type).playback
    }

/** Whether the domains a rewatch session can move with changed. */
private fun hasRewatchActivityChanged(previous: SimklActivities?, current: SimklActivities): Boolean {
    if (previous == null) return true
    return previous.tvShows != current.tvShows || previous.anime != current.anime
}

private val simklEntryComparator = compareBy<SimklLibraryEntry>(
    { entry -> entry.mediaType.ordinal },
    { entry -> entry.media?.title.orEmpty().lowercase() },
    { entry -> entry.stableKey().orEmpty() }
)
