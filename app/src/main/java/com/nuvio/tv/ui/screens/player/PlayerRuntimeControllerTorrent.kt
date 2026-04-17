package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.torrent.TorrentState
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "PlayerTorrent"

/**
 * Starts a torrent stream via TorrServer. Returns the HTTP stream URL for ExoPlayer.
 * TorrServer handles all piece management, buffering, and seeking internally.
 */
internal suspend fun PlayerRuntimeController.startTorrentStream(
    infoHash: String,
    fileIdx: Int?,
    filename: String? = null,
    trackers: List<String> = emptyList()
): String {
    isTorrentStream = true
    currentInfoHash = infoHash
    currentFileIdx = fileIdx

    _uiState.update {
        it.copy(
            showLoadingOverlay = true,
            loadingMessage = "Starting P2P engine...",
            loadingProgress = null,
            isTorrentStream = true
        )
    }

    val effectiveFilename = filename ?: currentFilename
    return torrentService.startStream(infoHash, fileIdx, effectiveFilename, trackers)
}

/**
 * Stops the current torrent stream and cleans up state.
 */
internal fun PlayerRuntimeController.stopTorrentStream() {
    torrentStreamJob?.cancel()
    torrentStreamJob = null
    torrentStateObserverJob?.cancel()
    torrentStateObserverJob = null

    if (isTorrentStream) {
        torrentService.stopStream()
    }

    isTorrentStream = false
    currentInfoHash = null
    currentFileIdx = null
}

/**
 * Collects TorrentService state and maps it to PlayerUiState fields.
 */
internal fun PlayerRuntimeController.observeTorrentState() {
    torrentStateObserverJob?.cancel()
    torrentStateObserverJob = scope.launch {
        torrentService.state.collectLatest { torrentState ->
            when (torrentState) {
                is TorrentState.Idle -> { /* No-op */ }

                is TorrentState.Connecting -> {
                    if (!hasRenderedFirstFrame) {
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = true,
                                loadingMessage = "Connecting to peers...",
                                loadingProgress = null,
                                torrentBufferingMessage = null
                            )
                        }
                    }
                }

                is TorrentState.Streaming -> {
                    val speed = formatSpeed(torrentState.downloadSpeed)
                    val peerInfo = "${torrentState.seeds} seeds \u00B7 ${torrentState.peers} peers"
                    val mbLoaded = formatMB(torrentState.preloadedBytes)
                    val statsHidden = _uiState.value.hideTorrentStats

                    if (!hasRenderedFirstFrame) {
                        // Initial load: show preloaded MB with progress bar
                        // TorrServer preloads ~5MB before streaming starts
                        val preloadTarget = 5_242_880L // 5MB
                        val progress = (torrentState.preloadedBytes.toFloat() / preloadTarget).coerceIn(0f, 1f)
                        val message = if (statsHidden) null else "$mbLoaded buffered \u00B7 $peerInfo \u00B7 $speed"
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = true,
                                loadingMessage = message,
                                loadingProgress = progress,
                                torrentDownloadSpeed = torrentState.downloadSpeed,
                                torrentUploadSpeed = torrentState.uploadSpeed,
                                torrentPeers = torrentState.peers,
                                torrentSeeds = torrentState.seeds,
                                torrentBufferProgress = torrentState.bufferProgress,
                                torrentTotalProgress = torrentState.totalProgress,
                                torrentBufferingMessage = null
                            )
                        }
                    } else {
                        // During playback: update stats, rebuffer message is
                        // handled by the progress loop in PlaybackEvents
                        val message = if (statsHidden) null else "$peerInfo \u00B7 $speed"
                        _uiState.update {
                            it.copy(
                                loadingProgress = null,
                                torrentDownloadSpeed = torrentState.downloadSpeed,
                                torrentUploadSpeed = torrentState.uploadSpeed,
                                torrentPeers = torrentState.peers,
                                torrentSeeds = torrentState.seeds,
                                torrentBufferProgress = torrentState.bufferProgress,
                                torrentTotalProgress = torrentState.totalProgress,
                                torrentBufferingMessage = message
                            )
                        }
                    }
                }

                is TorrentState.Error -> {
                    Log.e(TAG, "Torrent error: ${torrentState.message}")
                    _uiState.update {
                        it.copy(
                            error = "Torrent error: ${torrentState.message}",
                            showLoadingOverlay = false,
                            torrentBufferingMessage = null
                        )
                    }
                }
            }
        }
    }
}

/**
 * Launches a torrent stream for source/episode stream switching.
 */
internal fun PlayerRuntimeController.launchTorrentSourceStream(
    stream: Stream,
    infoHash: String,
    loadSavedProgress: Boolean
) {
    torrentStreamJob?.cancel()
    torrentStreamJob = scope.launch {
        try {
            observeTorrentState()

            currentTorrentSources = stream.sources
            val trackers = stream.sources
                ?.filter { it.startsWith("tracker:") }
                ?.map { it.removePrefix("tracker:") }
                ?: emptyList()
            val localUrl = startTorrentStream(
                infoHash = infoHash,
                fileIdx = stream.fileIdx,
                filename = stream.behaviorHints?.filename,
                trackers = trackers
            )

            currentStreamUrl = localUrl
            currentHeaders = emptyMap()
            currentStreamMimeType = null

            preparePlaybackBeforeStart(
                url = localUrl,
                headers = emptyMap(),
                loadSavedProgress = loadSavedProgress
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start torrent stream", e)
            _uiState.update {
                it.copy(
                    error = "Failed to start torrent: ${e.message}",
                    showLoadingOverlay = false,
                    loadingProgress = null
                )
            }
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1_024 -> String.format("%.0f KB/s", bytesPerSec / 1_024.0)
        else -> "$bytesPerSec B/s"
    }
}

private fun formatMB(bytes: Long): String = String.format("%.1f MB", bytes / 1_048_576.0)
