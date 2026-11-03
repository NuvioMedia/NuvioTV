package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.reshaped.livetv.LiveEdgeRecovery
import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import kotlinx.coroutines.flow.update

/**
 * A replay (catch-up, or a programme started over) plays on instead of stopping at the guide's
 * end time: it follows the player's own position updates, see [LiveTvPlayerState.followReplay].
 * Should the stream still end first (a provider that has less than was asked for), the end is
 * taken as the cue for what follows rather than as the end of a film, which would close the player.
 */
@Composable
internal fun LiveTvReplayFollow(state: LiveTvPlayerState) {
    LaunchedEffect(state) {
        val controller = state.player
        var watched: ExoPlayer? = null
        // The replay whose start was checked: a provider's replay playlist that is still growing
        // (no end mark, as for a programme started over) opens at its live edge, not its start.
        var startChecked: String? = null
        val endGuard = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                val window = LiveTvPlaybackRegistry.replayWindow(controller.currentStreamUrl) ?: return
                // Nuvio's listener, added first, has just marked the end; it is cleared before
                // the player screen reads it.
                controller._uiState.update { it.copy(playbackEnded = false) }
                state.continueReplay(window)
            }
        }
        LiveEdgeRecovery.replayFallback = state::playReplayFallback
        try {
            controller.playbackTimeline.collect { timeline ->
                if (LiveTvPlaybackRegistry.replayWindow(controller.currentStreamUrl) == null) return@collect
                val player = controller._exoPlayer
                if (player !== watched) {
                    watched?.removeListener(endGuard)
                    player?.addListener(endGuard)
                    watched = player
                }
                val playing = controller.currentStreamUrl
                if (player != null && playing != startChecked && player.hasLoadedWindow()) {
                    startChecked = playing
                    // Its position at the edge would also read as the replay's end: followed only from the start.
                    if (player.isCurrentMediaItemDynamic && player.currentPosition > LIVE_EDGE_START_MS) {
                        player.seekTo(0L)
                        return@collect
                    }
                }
                state.followReplay(timeline.currentPosition, timeline.duration)
            }
        } finally {
            if (LiveEdgeRecovery.replayFallback == state::playReplayFallback) LiveEdgeRecovery.replayFallback = null
            watched?.removeListener(endGuard)
        }
    }
}

/** A replay found this far in when it first plays was opened at its live edge. */
private const val LIVE_EDGE_START_MS = 10_000L

/** Whether the player has read what it plays (a playlist's own window, not the stand-in it shows first). */
private fun ExoPlayer.hasLoadedWindow(): Boolean {
    val timeline = currentTimeline
    if (timeline.isEmpty || currentMediaItemIndex >= timeline.windowCount) return false
    return !timeline.getWindow(currentMediaItemIndex, Timeline.Window()).isPlaceholder
}
