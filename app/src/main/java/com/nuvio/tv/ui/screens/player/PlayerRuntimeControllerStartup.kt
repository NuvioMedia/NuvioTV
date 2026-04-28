package com.nuvio.tv.ui.screens.player

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.startInitialPlaybackIfNeeded() {
    if (initialPlaybackStarted) return

    initialPlaybackStarted = true

    val infoHash = navigationArgs.infoHash
    Log.d("PlayerStartup", "startInitialPlayback: infoHash=$infoHash, streamUrl=${initialStreamUrl.take(80)}")
    if (infoHash != null) {
        torrentStreamJob = scope.launch {
            try {
                Log.d("PlayerStartup", "Starting torrent stream for $infoHash")
                observeTorrentState()
                val localUrl = startTorrentStream(
                    infoHash = infoHash,
                    fileIdx = navigationArgs.fileIdx,
                    filename = navigationArgs.filename,
                    trackers = navigationArgs.torrentTrackers
                )
                Log.d("PlayerStartup", "Torrent stream ready: $localUrl")
                currentStreamUrl = localUrl
                currentHeaders = emptyMap()
                // Use loadSavedProgress = true — TorrServer handles seeking via
                // HTTP Range requests, so ExoPlayer's standard resume logic works.
                preparePlaybackBeforeStart(
                    url = localUrl,
                    headers = emptyMap(),
                    loadSavedProgress = true
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlayerStartup", "Failed to start torrent", e)
                _uiState.update {
                    it.copy(
                        error = "Failed to start torrent: ${e.message}",
                        showLoadingOverlay = false
                    )
                }
            }
        }
        return
    }

    preparePlaybackBeforeStart(
        url = currentStreamUrl,
        headers = currentHeaders,
        loadSavedProgress = false
    )
}

internal fun PlayerRuntimeController.currentHostActivity(): Activity? {
    return hostActivityRef?.get()
}
