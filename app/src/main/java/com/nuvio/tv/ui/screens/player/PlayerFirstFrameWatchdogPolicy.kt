package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player

/**
 * First-frame watchdog recovery before codec-specific fallbacks run.
 */
internal object PlayerFirstFrameWatchdogPolicy {

    enum class RecoveryAction {
        None,
        ForcePlayWhenReady,
    }

    data class Input(
        val hasRenderedFirstFrame: Boolean,
        val currentStreamHasVideoTrack: Boolean,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val userPausedManually: Boolean,
    )

    fun evaluate(input: Input): RecoveryAction {
        if (input.hasRenderedFirstFrame || !input.currentStreamHasVideoTrack) {
            return RecoveryAction.None
        }
        if (input.playbackState != Player.STATE_READY) {
            return RecoveryAction.None
        }
        if (!input.playWhenReady && !input.userPausedManually) {
            return RecoveryAction.ForcePlayWhenReady
        }
        return RecoveryAction.None
    }
}
