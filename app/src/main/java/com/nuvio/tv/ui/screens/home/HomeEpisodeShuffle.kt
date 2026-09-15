package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.EpisodeShuffleProfile
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.EpisodeShuffle
import com.nuvio.tv.domain.model.ShuffleSurface
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

internal data class HomeShuffleRefresh(val visit: Long = System.nanoTime(), val metadata: Int = 0)

internal fun HomeViewModel.createShuffleHomeState() = combine(
    _uiState,
    episodeShuffleStore.profiles,
    combine(watchProgressRepository.watchedItems, watchProgressRepository.allProgress) { watched, progress ->
        val keys = watched.mapNotNull { item ->
            item.season?.let { season -> item.episode?.let { item.contentId to (season to it) } }
        } + progress.filter { it.isCompleted() }.mapNotNull { item ->
            item.season?.let { season -> item.episode?.let { item.contentId to (season to it) } }
        }
        keys.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }.distinctUntilChanged(),
    shuffleHomeRefresh
) { state, profile, watched, refresh ->
    applyHomeShuffle(state, profile, watched, episodeShuffle, refresh.visit, continueWatchingSortMode) { id, type ->
        val cached = cwMetaCache["$type:$id"] ?: cwMetaCache["series:$id"] ?: cwMetaCache["tv:$id"]
        cached?.videos?.map { video ->
            Video(video.id, video.title.orEmpty(), video.released, video.thumbnail,
                season = video.season, episode = video.episode, overview = video.overview, available = video.available)
        }
    }
}.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

internal fun applyHomeShuffle(
    state: HomeUiState,
    profile: EpisodeShuffleProfile,
    watched: Map<String, Set<Pair<Int, Int>>>,
    shuffle: EpisodeShuffle,
    visit: Long,
    sortMode: ContinueWatchingSortMode,
    videos: (String, String) -> List<Video>?
): HomeUiState {
    if (!profile.available || profile.shows.values.none { it.enabled }) return state
    val items = state.continueWatchingItems + state.upcomingItems
    val resumable = items.filterIsInstance<ContinueWatchingItem.InProgress>()
        .filter { profile.settings(it.progress.contentId, it.progress.contentType).enabled }
        .groupBy { it.progress.contentId }
        .mapValues { (_, episodes) -> episodes.maxBy { it.progress.lastWatched } }
    val projected = items.mapNotNull { item ->
        when (item) {
            is ContinueWatchingItem.InProgress -> {
                val latest = resumable[item.progress.contentId]
                if (latest != null && latest != item) null else item.copy(shufflePlayback = latest != null)
            }
            is ContinueWatchingItem.NextUp -> {
                val info = item.info
                if (info.contentId in resumable) return@mapNotNull null
                val settings = profile.settings(info.contentId, info.contentType)
                if (!settings.enabled) item else {
                    val catalogue = videos(info.contentId, info.contentType) ?: return@mapNotNull null
                    val selected = shuffle.select(
                        profile.profileId, info.contentId, catalogue, settings.includeWatched,
                        watched[info.contentId].orEmpty(), surface = ShuffleSurface.HOME, visit = visit
                    ) ?: return@mapNotNull null
                    item.copy(shufflePlayback = true, info = info.copy(
                        videoId = selected.id, season = selected.season!!, episode = selected.episode!!,
                        episodeTitle = selected.title, episodeDescription = selected.overview,
                        thumbnail = selected.thumbnail, released = selected.released,
                        hasAired = true, airDateLabel = null, releaseTimestamp = null,
                        isReleaseAlert = false, isNewSeasonRelease = false
                    ))
                }
            }
        }
    }
    val unique = projected.distinctBy { it.shuffleFocusKey ?: continueWatchingItemKey(it) }
    val (current, upcoming) = splitUpcomingItems(unique, sortMode)
    return state.copy(continueWatchingItems = current, upcomingItems = upcoming)
}
