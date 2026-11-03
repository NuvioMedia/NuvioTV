package com.nuvio.tv.ui.screens.player

import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DolbyVisionP5Native
import com.nuvio.tv.core.player.DolbyVisionP5Session
import com.nuvio.tv.core.player.DolbyVisionP5SurfaceRenderer
import com.nuvio.tv.data.local.LibassRenderMode
import com.nuvio.tv.core.player.DolbyVisionP5Session.BlockedReason
import com.nuvio.tv.core.player.DolbyVisionP5Session.Status
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.PlayerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DV5_NOTE_MS = 20_000L
/** The player's view can come a little after Profile 5 is found: wait for it this long. */
private const val DV5_ATTACH_RETRIES = 25
private const val DV5_ATTACH_RETRY_MS = 200L
/** A mapped player with this much buffered and still no frame drawn has stalled. */
private const val DV5_START_TIMEOUT_MS = 12_000L
private const val DV5_STALL_BUFFERED_MS = 4_000L
/** Past this, a player still without a frame is left to the player's own error handling. */
private const val DV5_WATCH_LIMIT_MS = 60_000L

/**
 * Set once a mapped player never showed a frame. Some TV chips can't hand 10-bit HDR video to the
 * GPU at all; then every mapped stream would stall, so the mapping stays off until the app restarts.
 */
@Volatile
private var dv5EffectStalledOnDevice = false
private var dv5EffectStalledFramesDrawn = 0L
/** How long a mapped stream plays before the player says how the mapping is going. */
private const val DV5_STATUS_DELAY_MS = 6_000L

/**
 * What this device lacks for [DolbyVisionP5SurfaceRenderer], or null when it has everything: a GPU
 * that samples decoded 10-bit YUV as is (EXT_YUV_target), and Android 9+ to mark the output as
 * HDR10 (ANativeWindow_setBuffersDataSpace; EGL's own BT.2020 PQ colour space isn't needed).
 */
@androidx.annotation.OptIn(UnstableApi::class)
private val rendererMissing: BlockedReason? by lazy {
    val missing = runCatching {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> BlockedReason.GPU_NO_HDR_OUTPUT
            !GlUtil.isYuvTargetExtensionSupported() -> BlockedReason.GPU_NO_YUV_TARGET
            else -> null
        }
    }.getOrElse {
        Log.w(PlayerRuntimeController.TAG, "DV5_HDR: GL capability check failed: ${it.message}")
        BlockedReason.GPU
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "DV5_HDR: GL check missing=$missing device=${Build.MANUFACTURER} ${Build.MODEL} " +
            "soc=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "?"} api=${Build.VERSION.SDK_INT}"
    )
    missing
}

/**
 * Starts the Dolby Vision Profile 5 -> HDR10 state of a (re)build of the player for [url], and
 * returns its session, or null when the setting is off. On a device that can map, the session is
 * [active]: once a Profile 5 track shows up, the decoder's output is routed through a
 * [DolbyVisionP5SurfaceRenderer], with no restart. Otherwise it only detects Profile 5, so the
 * player can say why the stream isn't mapped.
 */
internal fun PlayerRuntimeController.startDolbyVisionP5Session(
    url: String,
    settings: PlayerSettings,
): DolbyVisionP5Session? {
    if (dv5HdrStreamUrl != url) {
        dv5HdrStreamUrl = url
        dv5HdrFailedForCurrentStream = false
        dv5HdrNoteShownForCurrentStream = false
        dv5HdrProfile5ForCurrentStream = false
    }
    dv5HdrStatusJob?.cancel()
    dv5HdrStatusJob = null
    releaseDolbyVisionP5Session()
    if (!settings.dv5ToHdr10Enabled) return null
    val blocked = dolbyVisionP5BlockedReason(settings)
    val session = DolbyVisionP5Session(active = blocked == null, blockedReason = blocked)
    // Profile 5 can show up only once samples are read, after the tracks were reported.
    session.onDetected = { scope.launch(Dispatchers.Main) { maybeEnableDolbyVisionP5Hdr() } }
    dolbyVisionP5Session = session
    Log.i(PlayerRuntimeController.TAG, "DV5_HDR: session active=${session.active} blocked=$blocked")
    return session
}

private fun PlayerRuntimeController.dolbyVisionP5BlockedReason(settings: PlayerSettings): BlockedReason? {
    if (dv5EffectStalledOnDevice) return BlockedReason.EFFECT_STALLED
    if (dv5HdrFailedForCurrentStream) return BlockedReason.PLAYBACK_ERROR
    // The DV5 -> 8.1 RPU conversion would rewrite the RPU this needs.
    if (settings.dv5ToDv81Enabled && settings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI) {
        return BlockedReason.DV81_CONVERSION
    }
    if (!DolbyVisionP5Native.isSupported) return BlockedReason.NO_LIBRARY
    val hdrTypes = displayHdrTypes()
    if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes) return BlockedReason.DISPLAY_DOLBY_VISION
    if (Display.HdrCapabilities.HDR_TYPE_HDR10 !in hdrTypes &&
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS !in hdrTypes
    ) {
        return BlockedReason.DISPLAY_NOT_HDR10
    }
    return rendererMissing
}

/** The display's HDR types, from both the old and (Android 14+) the per-mode API. */
private fun PlayerRuntimeController.displayHdrTypes(): Set<Int> = runCatching {
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        ?: return@runCatching emptySet()
    val types = HashSet<Int>()
    @Suppress("DEPRECATION")
    display.hdrCapabilities?.supportedHdrTypes?.forEach(types::add)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        display.mode.supportedHdrTypes.forEach(types::add)
    }
    types
}.getOrDefault(emptySet())

/**
 * Once a Profile 5 track is found (from the tracks, or later from its first RPU): routes it through
 * the renderer and, a few seconds in, says how the mapping went; or says why it can't be mapped.
 */
internal fun PlayerRuntimeController.maybeEnableDolbyVisionP5Hdr() {
    val session = dolbyVisionP5Session ?: return
    if (!session.detected) return
    dv5HdrProfile5ForCurrentStream = true
    session.blockedReason?.let { reason ->
        if (!dv5HdrNoteShownForCurrentStream) {
            dv5HdrNoteShownForCurrentStream = true
            showDolbyVisionP5Note(
                if (reason == BlockedReason.EFFECT_STALLED) {
                    context.getString(R.string.dv5_hdr_note_off_stalled, dv5EffectStalledFramesDrawn)
                } else {
                    context.getString(reason.noteRes())
                }
            )
        }
        return
    }
    if (session.active) {
        attachDolbyVisionP5Renderer(session)
        scheduleDolbyVisionP5Status(session)
    }
}

/**
 * Puts a [DolbyVisionP5SurfaceRenderer] between the decoder and the player's SurfaceView: the
 * decoder renders into the renderer, which draws into the SurfaceView as HDR10.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.attachDolbyVisionP5Renderer(session: DolbyVisionP5Session) {
    if (dolbyVisionP5Renderer != null || dolbyVisionP5Session !== session) return
    val player = _exoPlayer
    val playerView = exoPlayerView
    val surfaceView = playerView?.videoSurfaceView as? SurfaceView
    if (player == null || playerView == null || playerView.player !== player || surfaceView == null) {
        // Profile 5 can be found while the player's view is still being attached.
        if (dv5HdrAttachRetries++ < DV5_ATTACH_RETRIES) {
            scope.launch(Dispatchers.Main) {
                delay(DV5_ATTACH_RETRY_MS)
                attachDolbyVisionP5Renderer(session)
            }
        } else {
            Log.w(PlayerRuntimeController.TAG, "DV5_HDR: no SurfaceView to draw into; not mapping")
        }
        return
    }
    if (activeAssVideoEffect != null) {
        // libass already draws through Media3's video effects on this player, which would take the
        // decoder's output instead.
        showDolbyVisionP5NoteOnce(context.getString(R.string.dv5_hdr_note_off_libass))
        return
    }
    val renderer = DolbyVisionP5SurfaceRenderer(session)
    if (!renderer.start()) {
        renderer.release()
        Log.w(PlayerRuntimeController.TAG, "DV5_HDR: renderer couldn't start")
        showDolbyVisionP5NoteOnce(context.getString(R.string.dv5_hdr_note_off_renderer))
        return
    }
    // The renderer needs the decoder's frames, which a tunneled decoder never hands out.
    trackSelector?.let { selector ->
        if (selector.parameters.tunnelingEnabled) {
            selector.setParameters(selector.buildUponParameters().setTunnelingEnabled(false))
        }
    }
    _uiState.update { state ->
        state.copy(
            tunnelingEnabled = false,
            // libass draws over the video instead of into it, which this stream's frames can't take.
            libassRenderMode = if (state.libassRenderMode == LibassRenderMode.VIDEO_EFFECT) {
                LibassRenderMode.OVERLAY
            } else {
                state.libassRenderMode
            }
        )
    }
    player.setVideoFrameMetadataListener(renderer.frameMetadataListener)
    // Detaches the SurfaceView from the decoder (blocking until done), so the renderer can draw into it.
    player.setVideoSurface(renderer.inputSurface)
    renderer.followOutput(surfaceView.holder)
    dolbyVisionP5Renderer = renderer
    Log.i(PlayerRuntimeController.TAG, "DV5_HDR: renderer attached")
    watchDolbyVisionP5Start(session, player)
}

private fun PlayerRuntimeController.showDolbyVisionP5NoteOnce(text: String) {
    if (dv5HdrNoteShownForCurrentStream) return
    dv5HdrNoteShownForCurrentStream = true
    showDolbyVisionP5Note(text)
}

private fun PlayerRuntimeController.scheduleDolbyVisionP5Status(session: DolbyVisionP5Session) {
    if (dv5HdrStatusJob != null || dv5HdrNoteShownForCurrentStream) return
    dv5HdrStatusJob = scope.launch(Dispatchers.Main) {
        delay(DV5_STATUS_DELAY_MS)
        if (dolbyVisionP5Session !== session) return@launch
        Log.i(PlayerRuntimeController.TAG, "DV5_HDR: status ${session.status()} ${session.describeForLog()}")
        dv5HdrNoteShownForCurrentStream = true
        showDolbyVisionP5Note(
            when (val status = session.status()) {
                Status.NoFrames -> context.getString(R.string.dv5_hdr_note_no_frames)
                Status.OutputNotHdr10 -> context.getString(R.string.dv5_hdr_note_not_hdr)
                Status.NoRpu -> context.getString(R.string.dv5_hdr_note_no_rpu)
                is Status.OtherProfile -> context.getString(R.string.dv5_hdr_note_other_profile, status.profile)
                Status.RpuUnreadable -> context.getString(R.string.dv5_hdr_note_rpu_unreadable)
                Status.NoLut -> context.getString(R.string.dv5_hdr_note_no_lut)
                Status.NotMapped -> context.getString(R.string.dv5_hdr_note_not_mapped)
                is Status.Mapping -> context.getString(R.string.dv5_hdr_note_on, status.tables)
            }
        )
    }
}

private fun BlockedReason.noteRes(): Int = when (this) {
    BlockedReason.NO_LIBRARY -> R.string.dv5_hdr_note_off_library
    BlockedReason.DISPLAY_DOLBY_VISION -> R.string.dv5_hdr_note_off_display_dv
    BlockedReason.DISPLAY_NOT_HDR10 -> R.string.dv5_hdr_note_off_display_sdr
    BlockedReason.GPU -> R.string.dv5_hdr_note_off_gpu
    BlockedReason.GPU_NO_YUV_TARGET -> R.string.dv5_hdr_note_off_gpu_yuv
    BlockedReason.GPU_NO_HDR_OUTPUT -> R.string.dv5_hdr_note_off_gpu_hdr_output
    BlockedReason.DV81_CONVERSION -> R.string.dv5_hdr_note_off_dv81
    BlockedReason.PLAYBACK_ERROR -> R.string.dv5_hdr_note_off_error
    BlockedReason.EFFECT_STALLED -> R.string.dv5_hdr_note_off_stalled
}

/**
 * Watches a player whose output was just routed through the renderer. If it has data buffered but
 * the renderer never draws a frame, this device can't run it: turn the mapping off and play the
 * stream again without it, instead of leaving the player stuck.
 */
private fun PlayerRuntimeController.watchDolbyVisionP5Start(session: DolbyVisionP5Session, player: ExoPlayer) {
    dv5HdrStartWatchdogJob?.cancel()
    dv5HdrStartWatchdogJob = scope.launch(Dispatchers.Main) {
            var waitedMs = 0L
            while (waitedMs < DV5_WATCH_LIMIT_MS) {
                delay(1_000L)
                waitedMs += 1_000L
                if (session.framesDrawnCount() > 0L || dolbyVisionP5Session !== session || _exoPlayer !== player) {
                    return@launch
                }
                if (waitedMs < DV5_START_TIMEOUT_MS) continue
                val dataReady = player.playbackState == Player.STATE_READY ||
                    player.totalBufferedDuration >= DV5_STALL_BUFFERED_MS
                if (!dataReady) continue
                Log.w(
                    PlayerRuntimeController.TAG,
                    "DV5_HDR: no frame after ${waitedMs}ms with data buffered; turning the mapping off. " +
                        session.describeForLog()
                )
                dv5EffectStalledOnDevice = true
                dv5EffectStalledFramesDrawn = session.framesReleasedCount()
                dv5HdrNoteShownForCurrentStream = false
                val resumePosition = player.currentPosition.takeIf { it > 0L }
                _uiState.update { state ->
                    state.copy(
                        pendingSeekPosition = resumePosition ?: state.pendingSeekPosition,
                        showLoadingOverlay = state.loadingOverlayEnabled
                    )
                }
                // This job is the one releasePlayer would cancel: let go of it first.
                dv5HdrStartWatchdogJob = null
                releasePlayer(flushPlaybackState = true, keepTorrentStream = true)
                reinitializeCurrentStream()
                return@launch
            }
    }
}

private fun PlayerRuntimeController.showDolbyVisionP5Note(text: String) {
    Log.i(PlayerRuntimeController.TAG, "DV5_HDR note: $text")
    com.nuvio.tv.core.player.DolbyVisionP5Status.onNote(text)
    _uiState.update { it.copy(dv5HdrNote = text) }
    dv5HdrNoteJob?.cancel()
    dv5HdrNoteJob = scope.launch(Dispatchers.Main) {
        delay(DV5_NOTE_MS)
        _uiState.update { it.copy(dv5HdrNote = null) }
    }
}

/**
 * A frame-processing or decoding error while mapping: the retry plays this stream without it.
 * Other errors (network, source) leave the mapping on.
 */
internal fun PlayerRuntimeController.onDolbyVisionP5PlayerError(errorCode: Int) {
    val mappingRelated = errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ||
        errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
    if (mappingRelated && dolbyVisionP5Session?.active == true && !dv5HdrFailedForCurrentStream) {
        Log.w(PlayerRuntimeController.TAG, "DV5_HDR: playback error $errorCode while mapping; disabled for this stream")
        dv5HdrFailedForCurrentStream = true
        // The rebuild that follows says so on screen.
        dv5HdrNoteShownForCurrentStream = false
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.releaseDolbyVisionP5Session() {
    dv5HdrStartWatchdogJob?.cancel()
    dv5HdrStartWatchdogJob = null
    dv5HdrAttachRetries = 0
    dolbyVisionP5Renderer?.let { renderer ->
        val player = _exoPlayer
        if (player != null) {
            // The player stays: take the decoder off the renderer before it goes, then give the
            // player its own view back.
            runCatching {
                player.clearVideoFrameMetadataListener(renderer.frameMetadataListener)
                player.clearVideoSurface(renderer.inputSurface)
            }
        }
        renderer.release()
        if (player != null) {
            (exoPlayerView?.takeIf { it.player === player }?.videoSurfaceView as? SurfaceView)
                ?.let { runCatching { player.setVideoSurfaceView(it) } }
        }
    }
    dolbyVisionP5Renderer = null
    dolbyVisionP5Session?.let { session ->
        Log.i(PlayerRuntimeController.TAG, "DV5_HDR: releasing session ${session.describeForLog()}")
        session.release()
    }
    dolbyVisionP5Session = null
}
