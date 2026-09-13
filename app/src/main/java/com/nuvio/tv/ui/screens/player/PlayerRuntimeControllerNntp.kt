package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.launchNntpSourceStream(
    stream: Stream,
    season: Int?,
    episode: Int?,
    fromEpisodePanel: Boolean,
    forcedTargetVideo: Video? = null,
    isAutoPlay: Boolean = false
) {
    nntpStreamJob?.cancel()
    nntpStreamJob = scope.launch {
        setNntpPanelLoading(fromEpisodePanel, true)
        try {
            stopTorrentStream()
            val localUrl = nntpService.startStream(
                nzbUrl = stream.nzbUrl.orEmpty(),
                servers = stream.servers.orEmpty(),
                fileIdx = stream.fileIdx,
                fileMustInclude = stream.fileMustInclude,
                season = season,
                episode = episode
            )
            val resolved = stream.copy(
                url = localUrl,
                nzbUrl = null,
                servers = null,
                fileMustInclude = null
            )
            nntpStreamJob = null
            if (fromEpisodePanel) {
                switchToEpisodeStream(resolved, forcedTargetVideo, isAutoPlay)
            } else {
                switchToSourceStream(resolved)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(
                PlayerRuntimeController.TAG,
                "Failed to create local NNTP session (${error::class.simpleName})"
            )
            nntpStreamJob = null
            val message = context.getString(
                R.string.player_error_failed_start_nntp,
                error.message ?: context.getString(R.string.error_unknown)
            )
            _uiState.update {
                if (fromEpisodePanel) {
                    it.copy(isLoadingEpisodeStreams = false, episodeStreamsError = message)
                } else {
                    it.copy(isLoadingSourceStreams = false, sourceStreamsError = message)
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.stopNntpStream() {
    nntpStreamJob?.cancel()
    nntpStreamJob = null
    nntpService.stopStream()
}

private fun PlayerRuntimeController.setNntpPanelLoading(
    fromEpisodePanel: Boolean,
    loading: Boolean
) {
    _uiState.update {
        if (fromEpisodePanel) {
            it.copy(
                isLoadingEpisodeStreams = loading,
                episodeStreamsError = null
            )
        } else {
            it.copy(
                isLoadingSourceStreams = loading,
                sourceStreamsError = null
            )
        }
    }
}
