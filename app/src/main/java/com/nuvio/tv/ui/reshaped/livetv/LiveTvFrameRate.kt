package com.nuvio.tv.ui.reshaped.livetv

import android.media.MediaFormat
import android.os.Handler
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import com.nuvio.tv.ui.screens.player.FrameRateSource
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.PlayerUiState
import com.nuvio.tv.ui.screens.player.currentHostActivity
import com.nuvio.tv.ui.screens.player.isUsingMpvEngine
import kotlinx.coroutines.flow.first
import java.util.WeakHashMap
import kotlinx.coroutines.flow.update

/**
 * Frame rate matching for Live TV. Nuvio's preflight downloads the start of a file over extra
 * connections before playback starts (for up to 18 s). A live channel has no start, and many IPTV
 * accounts allow a single connection, so the probe could get the channel itself refused. Live TV
 * skips it (ExoPlayer only) and [LiveTvFrameRateMatch] switches the display to the frame rate the
 * playing stream reports, as IPTV players do. Returns true when the preflight is skipped.
 */
internal fun PlayerRuntimeController.skipAfrPreflightForLiveTv(url: String): Boolean {
    if (isUsingMpvEngine() || !LiveTvPlaybackRegistry.isLiveTv(url)) return false
    // The last channel's rate must not be matched again while the new one loads.
    synchronized(LiveTvFrameMeter.measuredUrls) { LiveTvFrameMeter.measuredUrls.remove(this) }
    _uiState.update {
        it.copy(detectedFrameRateRaw = 0f, detectedFrameRate = 0f, detectedFrameRateSource = null, afrProbeRunning = false)
    }
    return true
}

/** Matches the display once the playing channel's video track reports its frame rate. */
@Composable
internal fun LiveTvFrameRateMatch(state: LiveTvPlayerState, uiState: PlayerUiState) {
    val fps = if (uiState.detectedFrameRateSource == FrameRateSource.TRACK) uiState.detectedFrameRate else 0f
    val raw = uiState.detectedFrameRateRaw
    val mode = uiState.frameRateMatchingMode
    LaunchedEffect(fps, mode) {
        if (fps <= 0f || mode == FrameRateMatchingMode.OFF) return@LaunchedEffect
        state.matchDisplay(fps, raw)
    }
}

internal suspend fun PlayerRuntimeController.matchDisplayToLiveTrack(fps: Float, raw: Float) {
    if (isUsingMpvEngine()) return
    val activity = currentHostActivity() ?: return
    val settings = playerSettingsDataStore.playerSettings.first()
    val target = FrameRateUtils.refineFrameRateForDisplay(
        activity = activity,
        detectedFps = fps,
        prefer23976Near24 = raw in 23.95f..23.999f,
    )
    FrameRateUtils.matchFrameRateAndWait(
        activity = activity,
        frameRate = target,
        videoWidth = currentVideoWidth,
        videoHeight = currentVideoHeight,
        resolutionMatchingEnabled = settings.resolutionMatchingEnabled,
    )
}

/**
 * Plain MPEG-TS channels (and HLS without a FRAME-RATE tag) carry no frame rate in their track
 * format, so without Nuvio's preflight nothing would be matched. For those, the rate is read from
 * the presentation times of the first frames the player renders: no extra connection, no decoding,
 * and it stops listening once measured. The result is published as the track's rate, so
 * [LiveTvFrameRateMatch] switches the display exactly as for a stream that reports it.
 */
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.measureLiveTvFrameRate() {
    if (isUsingMpvEngine() || !LiveTvPlaybackRegistry.isLiveTv(currentStreamUrl)) return
    val state = _uiState.value
    if (state.frameRateMatchingMode == FrameRateMatchingMode.OFF || state.detectedFrameRate > 0f) return
    val player = _exoPlayer ?: return
    val url = currentStreamUrl
    // Once per channel start: track updates repeat while the same channel plays.
    synchronized(LiveTvFrameMeter.measuredUrls) {
        if (LiveTvFrameMeter.measuredUrls.put(this, url) == url) return
    }
    player.setVideoFrameMetadataListener(LiveTvFrameMeter(this, player, url))
}

@OptIn(UnstableApi::class)
private class LiveTvFrameMeter(
    private val controller: PlayerRuntimeController,
    private val player: ExoPlayer,
    private val url: String,
) : VideoFrameMetadataListener {
    private val times = LongArray(SAMPLES)
    private var count = 0

    // Called on the playback thread for each frame about to be shown.
    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        if (count >= SAMPLES) return
        times[count++] = presentationTimeUs
        if (count < SAMPLES) return
        val raw = frameRateFromTimes(times)
        Handler(player.applicationLooper).post { publish(raw) }
    }

    private fun publish(raw: Float) {
        runCatching { player.clearVideoFrameMetadataListener(this) }
        // A channel switched meanwhile: its own measurement replaces this one.
        if (raw <= 0f || controller._exoPlayer !== player || controller.currentStreamUrl != url) return
        val snapped = FrameRateUtils.snapToStandardRate(raw)
        controller._uiState.update {
            if (it.detectedFrameRate > 0f) it
            else it.copy(detectedFrameRateRaw = raw, detectedFrameRate = snapped, detectedFrameRateSource = FrameRateSource.TRACK)
        }
    }

    companion object {
        /** About a second of video: enough for a steady median, short enough to match early. */
        const val SAMPLES = 48

        /** The channel each player screen last measured (weak, so a closed screen is not kept). */
        val measuredUrls = WeakHashMap<PlayerRuntimeController, String>()

        /** The median gap between frames; a dropped frame or a timestamp jump doesn't move it. */
        fun frameRateFromTimes(times: LongArray): Float {
            val gaps = (1 until times.size).map { times[it] - times[it - 1] }.filter { it > 0 }.sorted()
            if (gaps.size < times.size / 2) return 0f
            val fps = 1_000_000f / gaps[gaps.size / 2]
            return if (fps in 10f..121f) fps else 0f
        }
    }
}
