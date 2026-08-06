package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Singleton
class SimklTrackingProgressProvider @Inject constructor(
    private val syncRepository: SimklSyncRepository,
    private val apiClient: SimklApiClient,
    private val authStorage: SimklAuthStorage,
    private val layoutPreferences: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.SIMKL
    override val isAuthenticated = authStorage.state.map { state -> state.isAuthenticated }
        .distinctUntilChanged()
    override val allProgress = syncRepository.projection.map { projection ->
        projection.progress
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()
    override val remoteProgressLoaded = syncRepository.state.map { state ->
        state.hasLoaded && state.errorMessage == null
    }.distinctUntilChanged()
    override val nextUpSeeds = combine(
        syncRepository.projection,
        layoutPreferences.nextUpFromFurthestEpisode
    ) { projection, preferFurthestEpisode ->
        projection.nextUp(preferFurthestEpisode)
    }.distinctUntilChanged()
    override val watchedMovieIds = syncRepository.projection.map { projection ->
        projection.watchedMovieIds
    }.distinctUntilChanged()
    override val watchedItems = syncRepository.projection.map { projection ->
        projection.watched.items
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        syncRepository.projection.map { projection ->
            projection.episodeProgress(contentId)
        }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
            .distinctUntilChanged()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean> = syncRepository.projection.map { projection ->
        projection.isWatched(contentId, videoId, season, episode)
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC)
        return syncRepository.projection.value.watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        syncRepository.ensureLoaded()
        return syncRepository.projection.value.showIdSiblings
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = syncRepository.refresh(intent)

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        syncRepository.ensureLoaded()
        val sessions = syncRepository.state.value.snapshot.playback.filter { session ->
            val media = session.media ?: return@filter false
            val sameContent = media.canonicalContentId().equals(contentId, ignoreCase = true) ||
                syncRepository.state.value.snapshot.entries.any { entry ->
                    entry.media == media && entry.matchesSimklContentId(contentId)
                }
            val sessionSeason = session.episode?.tvdbSeason ?: session.episode?.season
            val sessionEpisode = session.episode?.tvdbNumber ?: session.episode?.number
            sameContent && (season == null || episode == null ||
                sessionSeason == season && sessionEpisode == episode)
        }
        val removed = linkedSetOf<Long>()
        sessions.mapNotNull(SimklPlaybackSession::id).forEach { sessionId ->
            try {
                apiClient.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.DELETE,
                        path = "/sync/playback/$sessionId"
                    )
                )
                removed += sessionId
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
        }
        syncRepository.removePlaybackSessions(removed)
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit

    override fun clearOptimistic() = Unit

    override fun isHiddenFromProgress(contentId: String): Boolean =
        syncRepository.projection.value.isHidden(contentId)

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean =
        syncRepository.projection.value.isWatchedByVideoId(videoId, episode)

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress
}

internal fun checkWatchedByVideoId(
    snapshot: SimklSyncSnapshot,
    videoId: String,
    episode: Int
): Boolean = SimklSnapshotProjection.create(snapshot).isWatchedByVideoId(videoId, episode)
