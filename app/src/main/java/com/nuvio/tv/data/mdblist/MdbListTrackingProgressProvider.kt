package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Singleton
class MdbListTrackingProgressProvider @Inject constructor(
    private val sync: MdbListSyncRepository,
    private val scrobbles: MdbListScrobbleService,
    auth: MdbListAuthStore,
    profiles: ProfileManager,
    layout: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.MDBLIST
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.distinctUntilChanged()
    private val projection = combine(sync.progressState, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        if (authorization.isAuthenticated && authorization.scope.profileId == profileId &&
            state.scope == authorization.scope) state.projection else MdbListProgressProjection.Empty
    }.distinctUntilChanged()
    override val allProgress = projection.map { it.progress }.loadOnStart()
    override val watchedItems = projection.map { it.watchedItems }.loadOnStart()
    override val watchedMovieIds = projection.map { it.watchedMovieIds }.loadOnStart()
    override val nextUpSeeds = combine(projection, layout.nextUpFromFurthestEpisode) { projection, furthest ->
        projection.nextUp(furthest)
    }.loadOnStart()
    override val remoteProgressLoaded = combine(sync.state, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        state.hasLoaded && state.scope == authorization.scope && authorization.scope.profileId == profileId && authorization.isAuthenticated
    }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        projection.map { it.episodeProgress(contentId) }.loadOnStart()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        projection.map { it.isWatched(contentId, season, episode) }.loadOnStart()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        return currentProjection().watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        sync.ensureLoaded()
        return currentProjection().showIdSiblings
    }

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val parts = videoId.split(':')
        if (parts.size < 3 || parts.last().toIntOrNull() != episode) return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val parent = parts.dropLast(2).joinToString(":")
        if (retainsLocalProgress(parent)) return null
        return currentProjection().isWatched(parent, season, episode)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) =
        scrobbles.clear(sync.currentScope(), contentId, season, episode)

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit
    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit
    override fun clearOptimistic() = Unit
    override fun isHiddenFromProgress(contentId: String): Boolean = currentProjection().isHidden(contentId)
    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress
    override fun retainsLocalProgress(contentId: String): Boolean = parseTrackingExternalIds(contentId).toMdbListIds() == null
    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = retainsLocalProgress(item.contentId)

    private fun currentProjection(): MdbListProgressProjection =
        sync.currentProjection()

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()
}
