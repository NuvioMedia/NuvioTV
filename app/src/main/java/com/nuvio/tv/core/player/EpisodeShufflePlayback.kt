package com.nuvio.tv.core.player

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.EpisodeShuffleStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.EpisodeShuffle
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import com.nuvio.tv.domain.model.ShuffleSurface
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.player.PlayerNextEpisodeRules
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackShuffleState(
    val settings: EpisodeShuffleSettings,
    val watched: Set<Pair<Int, Int>>,
    val progress: Map<Pair<Int, Int>, WatchProgress>
)

@Singleton
class EpisodeShufflePlayback @Inject constructor(
    private val store: EpisodeShuffleStore,
    private val shuffle: EpisodeShuffle,
    private val progressRepository: WatchProgressRepository,
    private val watchedPreferences: WatchedItemsPreferences,
    private val profileManager: ProfileManager
) {
    fun observe(profileId: Int, contentId: String, contentType: String) = combine(
        store.observeProfile(profileId),
        progressRepository.getAllEpisodeProgress(contentId, profileId),
        watchedPreferences.getWatchedEpisodesForContent(contentId, profileId)
    ) { profile, progress, watched ->
        PlaybackShuffleState(profile.settings(contentId, contentType), watched, progress)
    }

    suspend fun isEnabled(metadata: ExternalPlaybackMetadata): Boolean =
        store.observeProfile(metadata.profileId).first()
            .settings(metadata.contentId, metadata.contentType).enabled

    fun nextEpisode(
        profileId: Int,
        contentId: String,
        videos: List<Video>,
        season: Int?,
        episode: Int,
        state: PlaybackShuffleState,
        preferredVideoId: String? = null
    ): Video? {
        if (!state.settings.enabled) {
            shuffle.clearSelection(profileId, contentId, ShuffleSurface.PLAYBACK)
            return PlayerNextEpisodeRules.resolveNextEpisode(videos, season, episode)
        }
        val remoteWatched = if (!state.settings.includeWatched && profileManager.activeProfileId.value == profileId) {
            videos.filter { video ->
                video.episode?.let { progressRepository.isWatchedByVideoId(video.id, it) } == true
            }.mapNotNull { video -> video.season?.let { it to video.episode!! } }.toSet()
        } else emptySet()
        return shuffle.select(
            profileId, contentId, videos, state.settings.includeWatched,
            state.watched + remoteWatched, state.progress, ShuffleSurface.PLAYBACK,
            current = season?.let { it to episode }, preferredVideoId = preferredVideoId
        )
    }

    suspend fun externalSnapshot(
        metadata: ExternalPlaybackMetadata,
        videos: List<Video>,
        preferredVideoId: String? = null
    ): ExternalNextEpisodeSnapshot {
        val state = observe(metadata.profileId, metadata.contentId, metadata.contentType).first()
        if (!state.settings.enabled) {
            shuffle.clearSelection(metadata.profileId, metadata.contentId, ShuffleSurface.PLAYBACK)
            return resolveExternalNextEpisodeSnapshot(videos, metadata.season, metadata.episode)
        }
        val episode = metadata.episode ?: return ExternalNextEpisodeSnapshot.Unknown
        val season = metadata.season ?: videos.firstOrNull { it.id == metadata.videoId }?.season
        val next = nextEpisode(metadata.profileId, metadata.contentId, videos,
            season, episode, state, preferredVideoId)
        return ExternalNextEpisodeSnapshot(
            metadataResolved = true, nextVideoId = next?.id, nextSeason = next?.season,
            nextEpisode = next?.episode, shufflePlayback = true
        )
    }
}
