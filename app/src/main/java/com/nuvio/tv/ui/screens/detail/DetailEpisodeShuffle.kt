package com.nuvio.tv.ui.screens.detail

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.data.local.EpisodeShuffleProfile
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.EpisodeShuffle
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.ShuffleSurface
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress

internal fun applyDetailShuffle(
    state: MetaDetailsUiState,
    profile: EpisodeShuffleProfile,
    continueWatching: List<WatchProgress>,
    shuffle: EpisodeShuffle,
    visit: Long,
    context: Context
): MetaDetailsUiState {
    val meta = state.meta ?: return state
    val settings = profile.settings(meta.id, meta.apiType)
    val result = state.copy(randomEpisodeEnabled = profile.available, episodeShuffle = settings, shufflePoolEmpty = false)
    if (!settings.enabled) {
        shuffle.clearSelection(profile.profileId, meta.id, ShuffleSurface.DETAIL)
        return result
    }
    val resume = continueWatching.asSequence().filter {
        (it.contentId == meta.id || it.contentId == meta.imdbId) &&
            (it.contentType.equals("series", true) || it.contentType.equals("tv", true)) &&
            it.season != null && it.episode != null && it.videoId.isNotBlank() &&
            !it.isCompleted() && (it.position > 0 || it.progressPercentage > 0)
    }.maxByOrNull { it.lastWatched }
    if (resume != null) {
        return result.copy(nextToWatch = NextToWatch(
            watchProgress = resume, isResume = true, nextVideoId = resume.videoId,
            nextSeason = resume.season, nextEpisode = resume.episode,
            displayText = context.getString(R.string.detail_btn_resume_episode, resume.season, resume.episode)
        ))
    }
    val selected = shuffle.select(
        profile.profileId, meta.id, meta.videos, settings.includeWatched,
        state.watchedEpisodes, state.episodeProgressMap, ShuffleSurface.DETAIL, visit = visit
    )
    return result.copy(
        shufflePoolEmpty = selected == null,
        nextToWatch = NextToWatch(
            watchProgress = null, isResume = false, nextVideoId = selected?.id,
            nextSeason = selected?.season, nextEpisode = selected?.episode,
            displayText = if (selected == null) context.getString(R.string.shuffle_change_selection)
                else context.getString(R.string.detail_btn_play_episode, selected.season, selected.episode)
        )
    )
}

internal fun resolveHeroPlaybackVideo(
    meta: Meta,
    nextToWatch: NextToWatch?,
    episodesForSeason: List<Video>
): Video? {
    if (meta.type != ContentType.SERIES && meta.videos.isEmpty()) return null
    nextToWatch?.nextVideoId?.let { id -> meta.videos.firstOrNull { it.id == id }?.let { return it } }
    nextToWatch?.takeIf { it.isResume }?.watchProgress?.let { progress ->
        return Video(
            id = progress.videoId, title = progress.episodeTitle.orEmpty(), released = null,
            thumbnail = null, season = progress.season, episode = progress.episode, overview = null
        )
    }
    if (nextToWatch?.nextSeason != null && nextToWatch.nextEpisode != null) {
        meta.videos.firstOrNull {
            it.season == nextToWatch.nextSeason && it.episode == nextToWatch.nextEpisode
        }?.let { return it }
    }
    return meta.videos.firstOrNull {
        it.id == meta.behaviorHints?.defaultVideoId && it.available != false
    } ?: episodesForSeason.firstOrNull()
}
