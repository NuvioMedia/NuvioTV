@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.os.Build
import android.util.Log
import androidx.media3.common.Format
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Absolute ceiling on the whole track-AFR step (mode switch wait + settle
// hold). Timer-released, never event-gated — cannot deadlock startup.
// 8 s covers the worst case (~4 s mode-switch wait + 2 s settle hold)
// with margin; 7 s could clip a slow HDMI mode change mid-settle.
private const val TRACK_AFR_ABSOLUTE_DEADLINE_MS = 8_000L

// Settle hold after a real display-mode switch, mirroring
// AFR_EXO_SETTLE_HOLD_MS on the preflight path (community
// 0.7.14 QMS stutter report): don't start the A/V pipeline inside the mode
// transition.
private const val TRACK_AFR_SETTLE_HOLD_MS = 2_000L

/**
 * Track-format AFR: frame-rate matching driven by ExoPlayer's reported track
 * format instead of a MediaExtractor/NextLib network probe.
 *
 * Called from the tracks-changed handler the moment the selected video
 * format's frameRate is known — which is during preparation, before the first
 * frame renders. ExoPlayer has already parsed the container (including
 * moov-at-end MP4s, which its range-seeking handles and the blocking
 * MediaExtractor probe did not), so the value is free and always available at
 * exactly the right moment.
 *
 * Start gating: on the normal path the player is built with playWhenReady
 * already true (0.7.14 removed the start-paused-until-first-frame gate), so
 * arming the gate also pauses the player for the switch window — a paused
 * ExoPlayer still renders the first frame and still reaches STATE_READY.
 * While a track-driven switch is in flight the start sites stand down
 * (afrTrackSwitchInFlight); when the switch settles,
 * resumePlaybackAfterTrackAfrIfHeld() starts playback if the first frame
 * arrived during the hold, otherwise the normal onRenderedFirstFrame /
 * tunneled READY path starts it with the gate now open. If the deadline
 * expires the finally block releases the gate regardless; the 12 s
 * first-frame watchdog is a second, longer net and cannot fire inside the
 * 8 s gate window.
 */
internal fun PlayerRuntimeController.maybeRunTrackFormatAfr(rawFps: Float, format: Format) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    if (currentInternalPlayerEngine != InternalPlayerEngine.EXOPLAYER) return
    if (trackAfrAttemptedForCurrentStream) return
    if (afrModeAppliedPreStart) return
    if (rawFps <= 0f) return
    if (_uiState.value.frameRateMatchingMode == FrameRateMatchingMode.OFF) return
    if (_uiState.value.afrProbeRunning) return
    // Too late: playback is already running. A mid-playback mode switch is the
    // known black-screen/stutter risk this design avoids; skip rather than
    // switch under a live pipeline.
    if (hasRenderedFirstFrame && _exoPlayer?.isPlaying == true) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Track AFR: playback already running, skipping display-mode switch (fps=$rawFps)"
        )
        trackAfrAttemptedForCurrentStream = true
        return
    }
    val activity = currentHostActivity()
    if (activity == null) {
        Log.w(PlayerRuntimeController.TAG, "Track AFR skipped: host activity unavailable")
        return
    }

    trackAfrAttemptedForCurrentStream = true
    afrTrackSwitchInFlight = true
    // Engage the gate for real: playWhenReady is true from player build on
    // the normal path, so without this the player auto-starts at STATE_READY
    // and the stand-down at the start sites gates nothing. The guards above
    // ensure playback is not already running; setting an already-false value
    // is a no-op on the paused/startPaused flows.
    _exoPlayer?.playWhenReady = false
    // stamp this attempt with the current stream's generation; the
    // finally block stands down if a per-stream reset happened meanwhile.
    val startGeneration = afrTrackGeneration

    scope.launch {
        try {
            withTimeoutOrNull(TRACK_AFR_ABSOLUTE_DEADLINE_MS) {
                val playerSettings = playerSettingsDataStore.playerSettings.first()
                val snapped = FrameRateUtils.snapToStandardRate(rawFps)
                val prefer23976TrackBias = rawFps in 23.95f..23.999f
                val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
                    activity = activity,
                    detectedFps = snapped,
                    prefer23976Near24 = prefer23976TrackBias
                )
                val initialDisplayModeId = withContext(Dispatchers.Main) {
                    activity.window?.decorView?.display?.mode?.modeId
                }

                Log.d(
                    PlayerRuntimeController.TAG,
                    "Track AFR: raw=$rawFps snapped=$snapped target=$targetFrameRate " +
                        "size=${format.width}x${format.height}"
                )

                val result = FrameRateUtils.matchFrameRateAndWait(
                    activity = activity,
                    frameRate = targetFrameRate,
                    videoWidth = format.width.takeIf { it > 0 },
                    videoHeight = format.height.takeIf { it > 0 },
                    resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
                )

                if (result != null) {
                    val switchedDisplayMode = initialDisplayModeId != null &&
                        initialDisplayModeId != result.appliedMode.modeId
                    _uiState.update {
                        it.copy(
                            displayModeInfo = DisplayModeInfo(
                                width = result.appliedMode.physicalWidth,
                                height = result.appliedMode.physicalHeight,
                                refreshRate = result.appliedMode.refreshRate
                            ),
                            showDisplayModeInfo = true
                        )
                    }
                    if (switchedDisplayMode) {
                        Log.d(
                            PlayerRuntimeController.TAG,
                            "Track AFR: display mode switched; holding playback start ${TRACK_AFR_SETTLE_HOLD_MS}ms"
                        )
                        delay(TRACK_AFR_SETTLE_HOLD_MS)
                    }
                }
            } ?: Log.w(
                PlayerRuntimeController.TAG,
                "Track AFR exceeded absolute deadline (${TRACK_AFR_ABSOLUTE_DEADLINE_MS}ms); releasing start gate"
            )
        } finally {
            // act only if this coroutine still belongs to the current
            // stream — an old stream's finally otherwise collapses the new
            // stream's start-hold (and could start held playback early).
            if (afrTrackGeneration == startGeneration) {
                afrTrackSwitchInFlight = false
                resumePlaybackAfterTrackAfrIfHeld()
            }
        }
    }
}

/**
 * If the first frame rendered (or tunneled READY fired) while the track-AFR
 * gate held playback paused, start playback now with the same guards the
 * normal start sites use. If the frame hasn't arrived yet, do nothing — the
 * normal onRenderedFirstFrame / tunneled READY path starts playback, its gate
 * now open.
 */
internal fun PlayerRuntimeController.resumePlaybackAfterTrackAfrIfHeld() {
    val player = _exoPlayer ?: return
    if (!hasRenderedFirstFrame) return
    if (userPausedManually) return
    if (isReleasingPlayer) return
    if (player.playWhenReady) return
    Log.d(PlayerRuntimeController.TAG, "Track AFR: settle complete; starting held playback")
    player.playWhenReady = true
    player.play()
}
