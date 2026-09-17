package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.usenet.UsenetSidecar
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.resolveUsenetForSwitch(
    stream: Stream,
    fromEpisodePanel: Boolean,
    target: Video? = null,
    autoPlay: Boolean = false
): Boolean {
    if (!stream.isUsenet()) {
        if (stream.isTorrent()) UsenetSidecar.get(context).releaseIfDifferent(null)
        return false
    }
    debridResolveJob?.cancel()
    _uiState.update {
        if (fromEpisodePanel) it.copy(isLoadingEpisodeStreams = true, episodeStreamsError = null)
        else it.copy(isLoadingSourceStreams = true, sourceStreamsError = null)
    }
    debridResolveJob = scope.launch {
        try {
            val state = _uiState.value
            val season = if (fromEpisodePanel) target?.season ?: state.episodeStreamsSeason ?: currentSeason else currentSeason
            val episode = if (fromEpisodePanel) target?.episode ?: state.episodeStreamsEpisode ?: currentEpisode else currentEpisode
            val resolved = resolveSelectedStreamWithFallback(stream, season, episode)
                ?: throw IllegalStateException(context.getString(com.nuvio.tv.R.string.player_stream_fallback_exhausted))
            debridResolveJob = null
            if (fromEpisodePanel) switchToEpisodeStream(resolved, target, autoPlay, continuingSelection = true)
            else switchToSourceStream(resolved)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            debridResolveJob = null
            val message = e.message ?: context.getString(com.nuvio.tv.R.string.usenet_failed)
            _uiState.update {
                if (fromEpisodePanel) it.copy(isLoadingEpisodeStreams = false, episodeStreamsError = message)
                else it.copy(isLoadingSourceStreams = false, sourceStreamsError = message)
            }
        }
    }
    return true
}
