package com.nuvio.tv.ui.screens.player

import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
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
 * returns its session, or null when the setting is off. The session maps to HDR10 ([active]) only
 * on a device that can, once an earlier build of this stream found a Profile 5 track; otherwise it
 * only detects, so the player can say why a Profile 5 stream isn't mapped.
 */
internal fun PlayerRuntimeController.startDolbyVisionP5Session(
    url: String,
    settings: PlayerSettings,
): DolbyVisionP5Session? {
    if (dv5HdrStreamUrl != url) {
        dv5HdrStreamUrl = url
        dv5HdrDetectedForCurrentStream = false
        dv5HdrFailedForCurrentStream = false
        dv5HdrNoteShownForCurrentStream = false
    }
    dv5HdrSwitchInFlight = false
    dv5HdrStatusJob?.cancel()
    dv5HdrStatusJob = null
    releaseDolbyVisionP5Session()
    if (!settings.dv5ToHdr10Enabled) return null
    val blocked = dolbyVisionP5BlockedReason(settings)
    val session = DolbyVisionP5Session(
        active = blocked == null && dv5HdrDetectedForCurrentStream,
        blockedReason = blocked,
    )
    // Profile 5 can show up only once samples are read, after the tracks were reported.
    session.onDetected = { scope.launch(Dispatchers.Main) { maybeEnableDolbyVisionP5Hdr() } }
    dolbyVisionP5Session = session
    Log.i(
        PlayerRuntimeController.TAG,
        "DV5_HDR: session active=${session.active} blocked=$blocked detectedBefore=$dv5HdrDetectedForCurrentStream"
    )
    return session
}

private fun PlayerRuntimeController.dolbyVisionP5BlockedReason(settings: PlayerSettings): BlockedReason? {
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
 * Once a Profile 5 track is found (from the tracks, or later from its first RPUs): rebuilds the
 * player once with the mapping active (video effects can only be set before prepare), or says why
 * it can't map this stream. On a mapped stream it says, a few seconds in, how the mapping went.
 */
internal fun PlayerRuntimeController.maybeEnableDolbyVisionP5Hdr() {
    val session = dolbyVisionP5Session ?: return
    if (!session.detected) return
    // Remembered even when another rebuild (libass) runs first: the next build maps then.
    dv5HdrDetectedForCurrentStream = true

    session.blockedReason?.let { reason ->
        if (!dv5HdrNoteShownForCurrentStream) {
            dv5HdrNoteShownForCurrentStream = true
            showDolbyVisionP5Note(context.getString(reason.noteRes()))
        }
        return
    }
    if (session.active) {
        scheduleDolbyVisionP5Status(session)
        return
    }
    if (dv5HdrSwitchInFlight || libassPipelineSwitchInFlight) return
    val player = _exoPlayer ?: return
    Log.i(PlayerRuntimeController.TAG, "DV5_HDR: Profile 5 track found; rebuilding to map it to HDR10")
    dv5HdrSwitchInFlight = true
    val resumePosition = player.currentPosition.takeIf { it > 0L }
    _uiState.update { state ->
        state.copy(
            pendingSeekPosition = resumePosition ?: state.pendingSeekPosition,
            showLoadingOverlay = state.loadingOverlayEnabled
        )
    }
    scope.launch {
        releasePlayer(flushPlaybackState = true, keepTorrentStream = true)
        reinitializeCurrentStream()
    }
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
    dolbyVisionP5Session?.let { session ->
        Log.i(PlayerRuntimeController.TAG, "DV5_HDR: releasing session ${session.describeForLog()}")
        session.release()
    }
    dolbyVisionP5Session = null
}
