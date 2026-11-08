package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.repository.AddonEpisodeListService
import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AniListTrackingProgressProvider @Inject constructor(
    private val sync: AniListSyncRepository,
    private val auth: AniListAuthStore,
    private val profiles: ProfileManager,
    private val localProgress: WatchProgressPreferences,
    private val addonEpisodes: AddonEpisodeListService,
    layout: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.ANILIST
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.distinctUntilChanged()
    private val projection = combine(sync.projectionState, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        if (authorization.isAuthenticated && authorization.scope.profileId == profileId &&
            state.scope == authorization.scope
        ) state.projection else AniListProjection.Empty
    }.distinctUntilChanged()
    private val localPlayback = profiles.activeProfileId.flatMapLatest { profileId ->
        localProgress.observeAllProgress(profileId)
    }
    override val allProgress = combine(projection, localPlayback) { projection, local ->
        projection.progressEntries(local.filterNot { it.source.startsWith("anilist") })
    }.loadOnStart()
    override val watchedItems = projection.map { it.watchedItems }.loadOnStart()
    override val watchedMovieIds = projection.map { it.watchedMovieIds }.loadOnStart()
    override val nextUpSeeds = combine(projection, layout.nextUpFromFurthestEpisode) { projection, furthest ->
        selectPreferredTrackingNextUpSeeds(projection.seeds, furthest)
    }.loadOnStart()
    override val remoteProgressLoaded = combine(sync.state, auth.state, profiles.activeProfileId) { state, authorization, profileId ->
        state.hasLoaded && state.scope == authorization.scope && authorization.scope.profileId == profileId &&
            authorization.isAuthenticated
    }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        profiles.activeProfileId.flatMapLatest { profileId ->
            localProgress.getAllEpisodeProgress(contentId, profileId)
        }.map { items -> items.filterValues { !it.isCompleted() } }.loadOnStart()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        projection.map { projection ->
            val parts = parseAniListAnimeVideoId(videoId)
            if (parts?.episode != null && projection.isWatched("${parts.prefix}:${parts.id}", 1, parts.episode)) {
                return@map true
            }
            projection.isWatched(contentId, season, episode)
        }.loadOnStart()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        sync.refresh(TrackingRefreshIntent.AUTOMATIC)
        return sync.currentProjection().watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        sync.ensureLoaded()
        return sync.currentProjection().showIdSiblings
    }

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val projection = sync.currentProjection()
        parseAniListAnimeVideoId(videoId)?.let { parts ->
            if (parts.episode == episode) return projection.isWatched("${parts.prefix}:${parts.id}", 1, episode)
        }
        val parts = videoId.split(':')
        if (parts.size < 3 || parts.last().toIntOrNull() != episode) return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val parent = parts.dropLast(2).joinToString(":")
        return projection.isWatched(parent, season, episode)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        auth.refreshExpiry()
        sync.refresh(intent)
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        sync.dismiss(sync.currentScope(), listOf(contentId))
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit
    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit
    override fun clearOptimistic() = Unit
    override fun isHiddenFromProgress(contentId: String): Boolean = false

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress {
        if (progress.source != ANILIST_SOURCE_PROGRESS) return progress
        val episode = progress.episode ?: return progress
        val parts = parseAniListAnimeVideoId(progress.contentId) ?: return progress
        val episodes = try {
            addonEpisodes.episodes(progress.contentId, "series")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        val expectedVideoId = "${parts.prefix}:${parts.id}:$episode"
        val video = episodes.firstOrNull { it.videoId.equals(expectedVideoId, ignoreCase = true) } ?: return progress
        if (video.season == progress.season && video.episode == progress.episode) return progress
        return progress.copy(
            season = video.season,
            episode = video.episode,
            videoId = video.videoId ?: "${progress.contentId}:${video.season}:${video.episode}",
            episodeTitle = video.title?.takeIf(String::isNotBlank) ?: progress.episodeTitle
        )
    }

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        sync.ensureLoaded()
        sync.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()
}

internal fun aniListSyncErrorMessage(error: AniListSyncError): String = when (error) {
    AniListSyncError.UNAVAILABLE -> "Could not sync with AniList. Please try again."
    AniListSyncError.RATE_LIMIT -> "AniList's request limit has been reached. Syncing will resume shortly."
    AniListSyncError.AUTHORIZATION_REVOKED -> "Your AniList connection has expired or been revoked. Connect again to resume syncing."
}
