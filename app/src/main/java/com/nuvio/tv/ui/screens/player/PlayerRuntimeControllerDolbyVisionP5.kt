package com.nuvio.tv.ui.screens.player

import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DolbyVisionP5Native
import com.nuvio.tv.core.player.DolbyVisionP5Session
import com.nuvio.tv.core.player.DolbyVisionP5Session.BlockedReason
import com.nuvio.tv.core.player.DolbyVisionP5Session.Status
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.PlayerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DV5_NOTE_MS = 7_000L
/** A mapped player with this much buffered and still no frame has stalled in the effect path. */
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

/** Whether this device's GL can run Media3's 10-bit HDR effect path to an HDR10 output. */
@androidx.annotation.OptIn(UnstableApi::class)
private val hdrEffectPathSupported: Boolean by lazy {
    runCatching {
        GlUtil.isYuvTargetExtensionSupported() && GlUtil.isColorTransferSupported(C.COLOR_TRANSFER_ST2084)
    }.onFailure { Log.w(PlayerRuntimeController.TAG, "DV5_HDR: GL capability check failed: ${it.message}") }
        .getOrDefault(false)
}

/**
 * Starts the Dolby Vision Profile 5 -> HDR10 state of a (re)build of the player for [url], and
 * returns its session, or null when the setting is off. On a device that can map, the session is
 * [active] from the start: its effect is installed before the player is prepared and a Profile 5
 * track is mapped as soon as it shows up, with no restart. Otherwise it only detects Profile 5, so
 * the player can say why the stream isn't mapped.
 */
internal fun PlayerRuntimeController.startDolbyVisionP5Session(
    url: String,
    settings: PlayerSettings,
): DolbyVisionP5Session? {
    if (dv5HdrStreamUrl != url) {
        dv5HdrStreamUrl = url
        dv5HdrFailedForCurrentStream = false
        dv5HdrNoteShownForCurrentStream = false
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
    if (!hdrEffectPathSupported) return BlockedReason.GPU
    return null
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
 * Once a Profile 5 track is found (from the tracks, or later from its first RPU): says why this
 * stream can't be mapped, or, a few seconds into a mapped stream, how the mapping went.
 */
internal fun PlayerRuntimeController.maybeEnableDolbyVisionP5Hdr() {
    val session = dolbyVisionP5Session ?: return
    if (!session.detected) return
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
    if (session.active) scheduleDolbyVisionP5Status(session)
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
                Status.PipelineNotHdr -> context.getString(R.string.dv5_hdr_note_not_hdr)
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
    BlockedReason.DV81_CONVERSION -> R.string.dv5_hdr_note_off_dv81
    BlockedReason.PLAYBACK_ERROR -> R.string.dv5_hdr_note_off_error
    BlockedReason.EFFECT_STALLED -> R.string.dv5_hdr_note_off_stalled
}

/**
 * Watches a freshly built player that has the mapping effect installed. If it has data buffered
 * but never shows a frame, the effect path doesn't work on this device: turn the mapping off and
 * play the stream again without it, instead of leaving the player stuck.
 */
internal fun PlayerRuntimeController.watchDolbyVisionP5Start() {
    val session = dolbyVisionP5Session?.takeIf { it.active } ?: return
    val player = _exoPlayer ?: return
    dv5HdrStartWatchdogJob?.cancel()
    var rendered = false
    val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            rendered = true
        }
    }
    player.addListener(listener)
    dv5HdrStartWatchdogJob = scope.launch(Dispatchers.Main) {
        try {
            var waitedMs = 0L
            while (waitedMs < DV5_WATCH_LIMIT_MS) {
                delay(1_000L)
                waitedMs += 1_000L
                if (rendered || dolbyVisionP5Session !== session || _exoPlayer !== player) return@launch
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
                dv5EffectStalledFramesDrawn = session.framesDrawnCount()
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
        } finally {
            runCatching { player.removeListener(listener) }
        }
    }
}

private fun PlayerRuntimeController.showDolbyVisionP5Note(text: String) {
    Log.i(PlayerRuntimeController.TAG, "DV5_HDR note: $text")
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

internal fun PlayerRuntimeController.releaseDolbyVisionP5Session() {
    dv5HdrStartWatchdogJob?.cancel()
    dv5HdrStartWatchdogJob = null
    dolbyVisionP5Session?.let { session ->
        Log.i(PlayerRuntimeController.TAG, "DV5_HDR: releasing session ${session.describeForLog()}")
        session.release()
    }
    dolbyVisionP5Session = null
}
