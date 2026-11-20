package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.repository.EpisodeMappingEntry
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MdbListTrackingProgressProvider @Inject constructor(
    private val sync: MdbListSyncRepository,
    private val scrobbles: MdbListScrobbleService,
    private val episodeMappings: MdbListEpisodeMappingService,
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
    override val allProgress = projection.map { it.progress }.inAddonNumbering().loadOnStart()
    override val watchedItems = projection.map { it.watchedItems }.loadOnStart()
    override val watchedMovieIds = projection.map { it.watchedMovieIds }.loadOnStart()
    override val nextUpSeeds = combine(projection, layout.nextUpFromFurthestEpisode) { projection, furthest ->
        projection.nextUp(furthest)
    }.loadOnStart()
    override val remoteProgressLoaded = combine(sync.state, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        state.hasLoaded && state.scope == authorization.scope && authorization.scope.profileId == profileId && authorization.isAuthenticated
    }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        projection.transformLatest { projection ->
            val known = episodeMappings.cached(contentId)
            if (known != null) {
                emit(projection.episodeProgressInAddonNumbering(contentId, known))
                return@transformLatest
            }
            emit(projection.episodeProgress(contentId))
            if (!projection.hasEpisodes(contentId)) return@transformLatest
            episodeMapping(contentId, projection)?.let { mapping ->
                emit(projection.episodeProgressInAddonNumbering(contentId, mapping))
            }
        }.loadOnStart()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        projection.map { projection ->
            val tmdb = if (season != null && episode != null && projection.hasEpisodes(contentId)) {
                episodeMapping(contentId, projection)?.toTmdb(season, episode, videoId)
            } else null
            projection.isWatched(contentId, tmdb?.season ?: season, tmdb?.episode ?: episode)
        }.loadOnStart()

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
        val mapping = episodeMappings.cached(parent)?.takeIf { it.remaps }
            ?: return currentProjection().isWatched(parent, season, episode)
        val tmdb = mapping.toTmdb(season, episode, videoId) ?: return null
        return currentProjection().isWatched(parent, tmdb.season, tmdb.episode)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = sync.refresh(intent)

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val tmdb = if (season != null && episode != null) {
            episodeMapping(contentId, currentProjection())?.toTmdb(season, episode)
        } else null
        scrobbles.clear(sync.currentScope(), contentId, tmdb?.season ?: season, tmdb?.episode ?: episode)
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit
    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit
    override fun clearOptimistic() = Unit
    override fun isHiddenFromProgress(contentId: String): Boolean = currentProjection().isHidden(contentId)

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress {
        val season = progress.season ?: return progress
        val episode = progress.episode ?: return progress
        val addon = episodeMapping(progress.contentId, currentProjection())
            ?.toAddon(season, episode, progress.episodeTitle) ?: return progress
        return progress.withAddonEpisode(addon)
    }

    override fun retainsLocalProgress(contentId: String): Boolean = parseTrackingExternalIds(contentId).toMdbListIds() == null
    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = retainsLocalProgress(item.contentId)

    private fun currentProjection(): MdbListProgressProjection =
        sync.currentProjection()

    private suspend fun episodeMapping(
        contentId: String,
        projection: MdbListProgressProjection
    ): MdbListEpisodeMapping? {
        val ids = parseTrackingExternalIds(contentId).toMdbListIds() ?: return null
        val tmdbShowId = projection.mediaIndex.resolve(MdbListItemType.SHOW, ids).ids.tmdb
        return episodeMappings.resolve(contentId, tmdbShowId)
    }

    /**
     * Episode progress of [contentId] in the addon's numbering. When the show is split into seasons
     * differently, watched episodes are included too: the detail screen cannot look them up by the
     * addon's video ids until they are remapped.
     */
    private fun MdbListProgressProjection.episodeProgressInAddonNumbering(
        contentId: String,
        mapping: MdbListEpisodeMapping
    ): Map<Pair<Int, Int>, WatchProgress> {
        if (!mapping.remaps) return episodeProgress(contentId)
        return (watchedEpisodeProgress(contentId) + episodeProgress(contentId).values)
            .mapNotNull { progress ->
                val addon = mapping.toAddon(progress.season!!, progress.episode!!, progress.episodeTitle)
                    ?: return@mapNotNull null
                (addon.season to addon.episode) to progress.withAddonEpisode(addon)
            }
            .toMap()
    }

    /**
     * Moves in-progress episodes to the addon's numbering. Shows with a known mapping are remapped
     * at once; the others are emitted as MDBList numbers them and again once their mapping loads.
     */
    private fun Flow<List<WatchProgress>>.inAddonNumbering(): Flow<List<WatchProgress>> =
        transformLatest { items ->
            emit(items.map(::withCachedAddonEpisode))
            val pending = items.asSequence()
                .filter { it.season != null && it.episode != null && episodeMappings.cached(it.contentId) == null }
                .map(WatchProgress::contentId)
                .distinct()
                .toList()
            if (pending.isEmpty()) return@transformLatest
            val projection = currentProjection()
            val resolved = coroutineScope {
                pending.map { contentId -> async { episodeMapping(contentId, projection) } }.awaitAll()
            }
            if (resolved.any { it?.remaps == true }) emit(items.map(::withCachedAddonEpisode))
        }

    private fun withCachedAddonEpisode(progress: WatchProgress): WatchProgress {
        val season = progress.season ?: return progress
        val episode = progress.episode ?: return progress
        val addon = episodeMappings.cached(progress.contentId)
            ?.toAddon(season, episode, progress.episodeTitle) ?: return progress
        return progress.withAddonEpisode(addon)
    }

    private fun WatchProgress.withAddonEpisode(addon: EpisodeMappingEntry): WatchProgress = copy(
        season = addon.season,
        episode = addon.episode,
        videoId = addon.videoId ?: "$contentId:${addon.season}:${addon.episode}"
    )

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()
}
