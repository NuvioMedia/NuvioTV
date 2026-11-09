package com.nuvio.tv.reshaped.livetv

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * The full screen player's load control for Live TV channels. Nuvio's buffer settings are made
 * for files: playback waits for 3 to 5 s of video before it starts (and again after a stall),
 * while the Live TV preview starts after half a second. A live channel arrives in real time, so
 * those seconds are a wait of the same length on every channel open and every zap.
 *
 * This keeps [delegate] (its memory budget, allocator, back buffer and how far it reads ahead)
 * and only lets playback start once [START_MS] of video is buffered, or [RESTART_MS] after a
 * stall, as IPTV players do. It never waits longer than [delegate] would.
 */
@OptIn(UnstableApi::class)
class LiveTvStartLoadControl(private val delegate: LoadControl) : LoadControl {

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (delegate.shouldStartPlayback(parameters)) return true
        val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        val neededUs = (if (parameters.rebuffering) RESTART_MS else START_MS) * 1_000L
        return bufferedUs >= neededUs
    }

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(): Allocator = delegate.allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    companion object {
        /** Video buffered before a channel starts: the preview uses 0.5 s, full screen keeps a little more. */
        const val START_MS = 1_000L

        /** Video buffered before a channel resumes after a stall, so it does not stall again at once. */
        const val RESTART_MS = 2_000L

        /** [loadControl] as the player for [url] should use it: wrapped for Live TV, else as it is. */
        fun forUrl(url: String, loadControl: LoadControl): LoadControl =
            if (LiveTvPlaybackRegistry.isLiveTv(url)) LiveTvStartLoadControl(loadControl) else loadControl
    }
}
