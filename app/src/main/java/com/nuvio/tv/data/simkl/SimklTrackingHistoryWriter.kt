package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklTrackingHistoryWriter @Inject constructor(
    private val service: SimklMutationService,
    private val syncRepository: SimklSyncRepository,
    private val profileManager: ProfileManager
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.SIMKL

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        // A mark without episode coordinates describes a whole series, and Simkl answers such a mark by
        // marking every episode of the show watched. Only films may travel without coordinates; the
        // episodes of a whole-series action are reported one by one by the caller anyway.
        val pushableItems = simklHistoryPushItems(items)
        if (pushableItems.isEmpty()) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        return service.addToHistory(
            pushableItems.map { item ->
                val enriched = snapshot.enrichMediaReference(item.media)
                item.copy(media = enriched.resolveAnimeEpisodeForSimkl())
            }
        )
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
}

/**
 * What may travel to Simkl as a watched mark.
 *
 * A mark without episode coordinates describes a whole series. Simkl turns that into a show-level
 * entry and answers by marking every episode of the show watched, including episodes the user never
 * opened, which is how a single ill-timed mark wiped a full series. Only a movie is allowed through
 * without coordinates. An anime mark is dropped too: without more metadata the app cannot tell an
 * anime movie from an anime series.
 */
internal fun simklHistoryPushItems(items: Collection<TrackingHistoryItem>): List<TrackingHistoryItem> =
    items.filterNot(TrackingHistoryItem::isWholeSeriesMark)

private fun TrackingHistoryItem.isWholeSeriesMark(): Boolean =
    media.episode == null && media.kind.name.lowercase() !in MOVIE_WATCHED_TYPES

/** The one content type that stands on its own and needs no episode to be a real mark. */
private val MOVIE_WATCHED_TYPES = setOf("movie")
