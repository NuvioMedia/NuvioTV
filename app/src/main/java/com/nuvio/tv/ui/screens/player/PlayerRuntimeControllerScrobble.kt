package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.launch
import com.nuvio.tv.data.local.toTrackPreference

internal fun PlayerRuntimeController.preparePlaybackBeforeStart(
    url: String,
    headers: Map<String, String>,
    loadSavedProgress: Boolean
) {
    playbackPreparationJob?.cancel()
    playbackPreparationJob = scope.launch {
        warmTraktEpisodeMappingForCurrentPlayback()
        refreshScrobbleItem()
        if (persistedTrackPreference == null) {
            contentId?.let { id ->
                val loaded = trackPreferenceDataStore.load(id)?.toTrackPreference()
                Log.d(
                    PlayerRuntimeController.TAG,
                    "TRACK_PREF load: contentId=$id S${currentSeason}E${currentEpisode} " +
                        "result=${if (loaded == null) "null (no saved preference)" else "audio=${loaded.audio?.language}/${loaded.audio?.name} subtitle=${loaded.subtitle?.javaClass?.simpleName}"}"
                )
                persistedTrackPreference = loaded
            } ?: Log.d(PlayerRuntimeController.TAG, "TRACK_PREF load: skipped (contentId is null)")
        } else {
            Log.d(
                PlayerRuntimeController.TAG,
                "TRACK_PREF load: skipped (persistedTrackPreference already set: " +
                    "audio=${persistedTrackPreference?.audio?.language}/${persistedTrackPreference?.audio?.name} " +
                    "subtitle=${persistedTrackPreference?.subtitle?.javaClass?.simpleName})"
            )
        }
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
