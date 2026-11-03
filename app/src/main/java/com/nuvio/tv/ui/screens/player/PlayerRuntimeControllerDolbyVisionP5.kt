package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.DolbyVisionP5Native
import com.nuvio.tv.core.player.DolbyVisionP5Session
import com.nuvio.tv.core.player.DoviBridge
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.PlayerSettings
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * returns its session, or null when the mapping can't be used here. The session is active (maps
 * to HDR10) only once an earlier build of this stream found a Profile 5 track.
 */
internal fun PlayerRuntimeController.startDolbyVisionP5Session(
    url: String,
    settings: PlayerSettings,
): DolbyVisionP5Session? {
    if (dv5HdrStreamUrl != url) {
        dv5HdrStreamUrl = url
        dv5HdrDetectedForCurrentStream = false
        dv5HdrFailedForCurrentStream = false
    }
    dv5HdrSwitchInFlight = false
    dolbyVisionP5Session?.release()
    dolbyVisionP5Session = null
    if (!isDolbyVisionP5HdrEligible(settings)) return null
    return DolbyVisionP5Session(active = dv5HdrDetectedForCurrentStream).also {
        dolbyVisionP5Session = it
        if (it.active) Log.i(PlayerRuntimeController.TAG, "DV5_HDR: mapping Profile 5 to HDR10 for this stream")
    }
}

private fun PlayerRuntimeController.isDolbyVisionP5HdrEligible(settings: PlayerSettings): Boolean {
    if (!settings.dv5ToHdr10Enabled || dv5HdrFailedForCurrentStream) return false
    // The DV5 -> 8.1 RPU conversion would rewrite the RPU this needs.
    if (settings.dv5ToDv81Enabled && settings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI) return false
    if (!DolbyVisionP5Native.isSupported) return false
    val display = DolbyVisionBaseLayerPolicy.resolve(context, bridgeReady = DoviBridge.isLibraryLoaded)
    // A Dolby Vision display or a Profile 5 decoder shows Profile 5 correctly by itself.
    if (!display.hdrCapsKnown || display.displayDv || display.codecSupportsDvheStn) return false
    if (!display.displayHdr10) return false
    return hdrEffectPathSupported
}

/**
 * After the tracks of a detecting build are known: if they have a Profile 5 video track, rebuilds
 * the player once with the mapping active (video effects can only be set before prepare).
 */
internal fun PlayerRuntimeController.maybeEnableDolbyVisionP5Hdr() {
    val session = dolbyVisionP5Session ?: return
    // Remembered even when another rebuild (libass) runs first: the next build maps then.
    if (session.detected) dv5HdrDetectedForCurrentStream = true
    if (session.active || !session.detected || dv5HdrSwitchInFlight || libassPipelineSwitchInFlight) return
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

/**
 * A frame-processing or decoding error while mapping: the retry plays this stream without it.
 * Other errors (network, source) leave the mapping on.
 */
internal fun PlayerRuntimeController.onDolbyVisionP5PlayerError(errorCode: Int) {
    val mappingRelated = errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ||
        errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
    if (mappingRelated && dolbyVisionP5Session?.active == true && !dv5HdrFailedForCurrentStream) {
        Log.w(PlayerRuntimeController.TAG, "DV5_HDR: playback error while mapping; disabled for this stream")
        dv5HdrFailedForCurrentStream = true
    }
}

internal fun PlayerRuntimeController.releaseDolbyVisionP5Session() {
    dolbyVisionP5Session?.release()
    dolbyVisionP5Session = null
}
