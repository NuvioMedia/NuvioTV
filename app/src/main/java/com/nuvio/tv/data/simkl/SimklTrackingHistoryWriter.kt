package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.TraktSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class SimklTrackingHistoryWriter @Inject constructor(
    private val service: SimklMutationService,
    private val syncRepository: SimklSyncRepository,
    private val profileManager: ProfileManager,
    private val settingsDataStore: TraktSettingsDataStore,
    private val authRepository: SimklAuthRepository,
    private val rewatchWriter: SimklRewatchWriter
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.SIMKL

    /**
     * Writes the manual marks (the episode menu, a season action, a library action) to the account.
     *
     * A mark of an item the account already watched is a repeat, and Simkl records a plain add of it
     * as nothing, so it travels as a rewatch: the item carries the flag, the request carries
     * `allow_rewatch=yes`, and a run the account already holds is pinned by its session id. A mark of
     * the step the open run is waiting for continues it too, which is what the Continue Watching card
     * asks for; the account's episode rows are not needed for that, because the run position already
     * says where the user is. The explicit Mark as rewatched action asks for one directly. A mark of
     * an item that was never watched stays a plain add everywhere else, so a first watch of a new
     * episode cannot join a run it does not belong to. The flag only travels while the rewatch mode
     * and the plan allow it; Simkl drops a write carrying the flag for a free account silently, and
     * the watch would be dropped with it.
     */
    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        // A mark without episode coordinates describes a whole series, and Simkl answers such a mark by
        // marking every episode of the show watched. Only films may travel without coordinates; the
        // episodes of a whole-series action are reported one by one by the caller anyway.
        val pushableItems = items.filterNot(TrackingHistoryItem::isWholeSeriesMark)
        if (pushableItems.isEmpty()) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        val entryIndex = syncRepository.currentEntryIndex()
        val mode = settingsDataStore.simklRewatchMode.first()
        val accountType = authRepository.state.value.accountType
        val resolved = pushableItems.map { item ->
            val catalogue = snapshot.enrichMediaReference(item.media, entryIndex)
            val enriched = catalogue.resolveAnimeEpisodeForSimkl()
            val repeatViewing = snapshot.priorWatchForMark(catalogue, enriched).wasWatched ||
                snapshot.markContinuesRunningRewatch(enriched)
            if (
                shouldRecordSimklRewatchOnMark(
                    mode = mode,
                    accountType = accountType,
                    isRepeatViewing = repeatViewing,
                    explicitRewatch = item.isRewatch
                )
            ) {
                item.copy(
                    media = enriched,
                    isRewatch = true,
                    rewatchId = snapshot.rewatchSessions.activeRewatchSessionId(enriched)
                )
            } else {
                item.copy(media = enriched, isRewatch = false, rewatchId = null)
            }
        }
        // One show entry can carry only one flag, and a batch that mixes a repeat with a first watch
        // is sent as first watches by the body. Make the same choice here, so the read-back only
        // starts for a write that really was a rewatch.
        val writes = resolved.groupBy { item -> item.media.stableKey }.values.flatMap { group ->
            if (group.all(TrackingHistoryItem::isRewatch) || group.none(TrackingHistoryItem::isRewatch)) {
                group
            } else {
                group.map { item -> item.copy(isRewatch = false, rewatchId = null) }
            }
        }
        val result = service.addToHistory(writes)
        // The run this mark opened or extended is not in the snapshot until a read sees it. The
        // history write updates the canonical entry but not the rewatch session sidecar, so the
        // Continue Watching row would keep the old run position until a sync. Read the sessions
        // back and adopt them, the same way a confirmed rewatch does.
        val targetItem = writes.firstOrNull { item -> item.isRewatch }
        val target = targetItem?.media
        // The running session is found by its id, so its own canonical id is used for the read
        // back. A flat anime session can resolve to a different id form than the catalogue item.
        val sessionContentId = targetItem?.rewatchId?.let { rewatchId ->
            snapshot.rewatchSessions
                .firstOrNull { session ->
                    session.rewatchId == rewatchId && session.isRunningRewatchSession()
                }
                ?.media
                ?.canonicalContentId()
                ?.takeIf(String::isNotBlank)
        }
        val targetContentId = sessionContentId
            ?: target?.let { media ->
                media.toSimklMedia().canonicalContentId()?.takeIf(String::isNotBlank)
            }
            ?: target?.catalog?.contentId?.takeIf(String::isNotBlank)
        val targetSeason = target?.episode?.season
        val targetEpisode = target?.episode?.number
        // Only a status the account uses for a recorded session means a run exists to read back.
        // A no-op inside the 2-day gap carries no status, so the old check would have read the
        // sessions three times for a write that never opened one. A batch with one accepted item
        // still reads, so the accepted item's run is not missed.
        val rewatchStatuses = result.resolutions.mapNotNull { resolution -> resolution.rewatchStatus }
        val rewatchRecorded = rewatchStatuses.any { status ->
            simklHistoryRewatchWasRecorded(status, result.addedCount)
        }
        if (
            result.isComplete &&
            rewatchRecorded &&
            target != null &&
            targetContentId != null &&
            targetEpisode != null
        ) {
            val readContentId = targetContentId
            val readEpisode = targetEpisode
            val readKind = target.kind
            rewatchWriter.refreshRewatchSessions { sessions ->
                sessions.rewatchSessionHolds(
                    contentId = readContentId,
                    kind = readKind,
                    season = targetSeason,
                    episode = readEpisode
                )
            }
        }
        // The answer is what says whether the account booked the view: `pro_required` and `too_soon`
        // are the two that come back for a mark that was recorded as nothing, and both are silent in
        // the counts.
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl history mark items=${pushableItems.size} " +
                "rewatch=${result.resolutions.count { resolution -> resolution.rewatchStatus != null }} " +
                "answers=" +
                result.resolutions.joinToString(separator = ",") { resolution ->
                    resolution.rewatchStatus ?: "-"
                }
        )
        return result
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        val entryIndex = syncRepository.currentEntryIndex()
        return service.removeFromHistory(
            items.map { ref ->
                snapshot.enrichMediaReference(ref, entryIndex).resolveAnimeEpisodeForSimkl()
            }
        )
    }

    /**
     * Removes one episode from the rewatch session that holds it, leaving the original watch alone.
     *
     * The session id is read from the same stored sessions the detail screen marks the episode from.
     * A removal that cannot name one is not sent: without the id Simkl would clear the canonical
     * watch instead.
     */
    suspend fun removeRewatchFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult? {
        if (profileId != profileManager.activeProfileId.value) return null
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        // The lookup needs the identity and the coordinates the user marked, while the request needs
        // the anime-native reference: resolving first would drop the season the stored session was
        // found with.
        val candidates = items.map { ref ->
            val enriched = snapshot.enrichMediaReference(ref)
            enriched to enriched.resolveAnimeEpisodeForSimkl()
        }
        val (media, resolved) = candidates.singleOrNull() ?: return null
        val episode = media.episode ?: return null
        val rewatchId = snapshot.rewatchSessions.activeRewatchSessionIdForRemoval(
            media = media,
            seasonNumber = episode.season,
            episodeNumber = episode.number,
            animeEpisodeNumber = resolved.episode?.number
        ) ?: return null
        return service.removeRewatchFromHistory(listOf(resolved), rewatchId)
    }
}
