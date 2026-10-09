package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.remote.RemoteCommand
import com.nuvio.tv.core.remote.RemoteSnapshot
import com.nuvio.tv.core.remote.TvRemoteServer
import com.nuvio.tv.core.remote.remotePlaybackState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Reads and controls both internal engines on the runtime's player (main) thread. */
internal class PlayerRemoteBridge(private val runtime: PlayerRuntimeController) {
    private val scope = CoroutineScope(runtime.scope.coroutineContext + SupervisorJob(runtime.scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
    private var identity: String? = null
    private var sessionId: String? = null
    private var last: RemoteSnapshot? = null
    private var closed = false

    init {
        if (BuildConfig.FLAVOR == "full") {
            scope.launch { runtime.uiState.collect { publish() } }
            scope.launch { while (isActive) { publish(); delay(2_000) } }
        }
    }

    private fun sample(): RemoteSnapshot? {
        if (closed || runtime.isReleasingPlayer) return null
        val ui = runtime.uiState.value
        if (ui.playbackEnded || ui.pendingExitReason != null) return null
        if (runtime.isUsingMpvEngine()) {
            if (runtime.mpvView == null) return null
        } else if (runtime._exoPlayer == null) return null
        val nextIdentity = "${runtime.currentVideoId}|${runtime.currentSeason}|${runtime.currentEpisode}"
        if (sessionId == null || nextIdentity != identity) {
            identity = nextIdentity
            sessionId = UUID.randomUUID().toString()
        }
        val duration = runtime.currentPlaybackDurationMs().coerceAtLeast(0)
        val seekable = if (runtime.isUsingMpvEngine()) runtime.mpvView?.isSeekableForRemote() == true
            else runtime._exoPlayer?.isCurrentMediaItemSeekable == true
        return RemoteSnapshot(
            deviceId = "", deviceName = "", sessionId = sessionId,
            title = ui.contentName?.takeIf { it.isNotBlank() } ?: ui.title,
            episodeTitle = ui.currentEpisodeTitle,
            season = ui.currentSeason, episode = ui.currentEpisode,
            // Only display metadata is projected; PlayerUiState also contains private source data.
            artwork = (ui.backdrop ?: runtime.poster)?.takeIf { it.startsWith("https://") },
            state = remotePlaybackState(runtime.hasActivePlayIntent(), ui.isBuffering, runtime.isPlaybackCurrentlyPlaying()),
            positionMs = (runtime.currentPlaybackPositionMs() ?: 0).coerceAtLeast(0),
            durationMs = duration, speed = ui.playbackSpeed,
            canSeek = seekable && duration > 0,
        )
    }

    private fun publish() {
        val server = TvRemoteServer.instance ?: return
        val next = sample()
        if (next == null) {
            server.clear(this)
            last = null
            sessionId = null
        } else if (last != next) {
            last = next
            server.publish(this, next, ::execute)
        }
    }

    private fun execute(command: RemoteCommand): Boolean {
        val current = sample() ?: return false
        // Re-sample here so an episode transition cannot race the state collector.
        if (current.sessionId != command.sessionId) { publish(); return false }
        when (command.action) {
            "play" -> runtime.setPlaybackPaused(false)
            "pause" -> runtime.setPlaybackPaused(true)
            "seek" -> {
                if (!current.canSeek) return false
                runtime.seekPlaybackTo(requireNotNull(command.positionMs).coerceIn(0, current.durationMs))
            }
            else -> return false
        }
        publish()
        return true
    }

    fun close() {
        closed = true
        scope.cancel()
        TvRemoteServer.instance?.clear(this)
    }
}
