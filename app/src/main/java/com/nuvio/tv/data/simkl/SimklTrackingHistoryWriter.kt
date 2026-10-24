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
    private val authRepository: SimklAuthRepository
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.SIMKL

    /**
     * Writes the manual marks (the episode menu, a season action, a library action) to the account.
     *
     * A mark of something the account has already watched is a repeat viewing, and Simkl records a
     * plain add of it as nothing at all, so such a mark travels as a rewatch: the item carries the
     * flag, the request carries `allow_rewatch=yes`, and a run the account already holds is pinned by
     * its session id, so the mark joins that run instead of opening another. Whether a mark is a repeat
     * is asked of the account's own history, and the flag only travels while the rewatch mode and the
     * plan allow it: Simkl drops a write carrying the flag for a free account silently, and the watch
     * would be dropped with it.
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
        val mode = settingsDataStore.simklRewatchMode.first()
        val accountType = authRepository.state.value.accountType
        val result = service.addToHistory(
            pushableItems.map { item ->
                val enriched = snapshot.enrichMediaReference(item.media).resolveAnimeEpisodeForSimkl()
                val runningSessionId = snapshot.rewatchSessions.activeRewatchSessionId(enriched)
                if (
                    shouldRecordSimklRewatchOnMark(
                        mode = mode,
                        accountType = accountType,
                        isRepeatViewing = snapshot.entries.hasWatchedTitle(enriched),
                        hasRunningSession = runningSessionId != null
                    )
                ) {
                    item.copy(media = enriched, isRewatch = true, rewatchId = runningSessionId)
                } else {
                    item.copy(media = enriched)
                }
            }
        )
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
        return service.removeFromHistory(
            items.map { ref ->
                snapshot.enrichMediaReference(ref).resolveAnimeEpisodeForSimkl()
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
        val candidates = items.map { ref ->
            snapshot.enrichMediaReference(ref).resolveAnimeEpisodeForSimkl()
        }
        val media = candidates.singleOrNull() ?: return null
        val episode = media.episode ?: return null
        val season = episode.season ?: return null
        val rewatchId = snapshot.rewatchSessions.activeRewatchSessionIdForEpisode(
            media = media,
            seasonNumber = season,
            episodeNumber = episode.number
        ) ?: return null
        return service.removeRewatchFromHistory(candidates, rewatchId)
    }
}
