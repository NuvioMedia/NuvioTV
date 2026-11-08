package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.shouldBuildWithKeystoneZoom(
    aspectMode: AspectMode,
    keepZoomInsideScreen: Boolean,
    internalPlayerEngine: InternalPlayerEngine
): Boolean {
    if (keystoneZoomStreamUrl != currentStreamUrl) {
        keystoneZoomStreamUrl = currentStreamUrl
        keystoneZoomForCurrentStream = false
        keystoneZoomBlockedForCurrentStream = false
    }
    keystoneZoomSwitchInFlight = false
    if (
        !keepZoomInsideScreen ||
        keystoneZoomBlockedForCurrentStream ||
        dv5HdrProfile5ForCurrentStream ||
        internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER
    ) {
        return false
    }
    if (aspectModeAlwaysOverflowsView(aspectMode)) {
        keystoneZoomForCurrentStream = true
    }
    return keystoneZoomForCurrentStream
}

internal fun PlayerRuntimeController.createKeystoneZoomEffect(): KeystoneZoomEffect {
    return KeystoneZoomEffect().also { keystoneZoomEffect = it }
}

internal fun PlayerRuntimeController.requestKeystoneZoom() {
    if (keystoneZoomEffect != null || keystoneZoomSwitchInFlight || keystoneZoomBlockedForCurrentStream) return
    val state = _uiState.value
    if (!state.keepZoomInsideScreen || state.internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) return
    if (dv5HdrProfile5ForCurrentStream || dolbyVisionP5Renderer != null || libassPipelineSwitchInFlight) return
    if (_exoPlayer?.videoFormat?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
        keystoneZoomBlockedForCurrentStream = true
        return
    }
    keystoneZoomForCurrentStream = true
    rebuildPlayerForKeystoneZoom()
}

internal fun PlayerRuntimeController.disableKeystoneZoomForCurrentStream() {
    keystoneZoomForCurrentStream = false
    if (keystoneZoomEffect == null || keystoneZoomSwitchInFlight) return
    rebuildPlayerForKeystoneZoom()
}

internal fun PlayerRuntimeController.dropKeystoneZoomForCurrentStream(): Boolean {
    keystoneZoomBlockedForCurrentStream = true
    keystoneZoomForCurrentStream = false
    if (keystoneZoomEffect == null || keystoneZoomSwitchInFlight) return false
    return rebuildPlayerForKeystoneZoom()
}

internal fun PlayerRuntimeController.maybeDropKeystoneZoomForTracks(tracks: Tracks): Boolean {
    val hasDvVideoTrack = tracks.groups.any { group ->
        group.isSelected && group.type == C.TRACK_TYPE_VIDEO &&
            (0 until group.length).any { i ->
                group.getTrackFormat(i).sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
            }
    }
    if (!hasDvVideoTrack) return false
    return dropKeystoneZoomForCurrentStream()
}

private fun PlayerRuntimeController.rebuildPlayerForKeystoneZoom(): Boolean {
    val player = _exoPlayer ?: return false
    val resumePosition = player.currentPosition.takeIf { it > 0L }
    keystoneZoomSwitchInFlight = true
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
    return true
}
