package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.preparePlaybackBeforeStart(
    url: String,
    headers: Map<String, String>,
    loadSavedProgress: Boolean
) {
    playbackPreparationJob?.cancel()
    playbackPreparationJob = scope.launch {
        warmTraktEpisodeMappingForCurrentPlayback()
        refreshScrobbleItem()
        initializePlayer(url, headers)
        if (loadSavedProgress) {
            loadSavedProgressFor(currentSeason, currentEpisode)
        }
    }
}

internal suspend fun PlayerRuntimeController.warmTraktEpisodeMappingForCurrentPlayback() {
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv")) {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }

    val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: run {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }
    val season = currentSeason ?: run {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }
    val episode = currentEpisode ?: run {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }

    currentTraktEpisodeMapping = traktEpisodeMappingService.prefetchEpisodeMapping(
        contentId = resolvedContentId,
        contentType = contentType,
        videoId = currentVideoId,
        season = season,
        episode = episode
    )
    currentTraktEpisodeMappingKey = currentEpisodeMappingCacheKey()
}

internal fun PlayerRuntimeController.currentEpisodeMappingCacheKey(): String? {
    val resolvedContentId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val resolvedType = contentType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val season = currentSeason ?: return null
    val episode = currentEpisode ?: return null
    val videoId = currentVideoId?.trim().orEmpty()
    return "$resolvedType|$resolvedContentId|$videoId|$season|$episode"
}
