package com.nuvio.tv.ui.screens.player

/**
 * First-frame watchdog codec recovery ladder after [PlayerFirstFrameWatchdogPolicy]
 * force-play is skipped or already applied — DV mode downgrade, then VC1 software,
 * then VC1 track-selection bypass.
 */
internal object PlayerFirstFrameCodecRecoveryPolicy {

    data class Input(
        val playWhenReady: Boolean,
        val isManualDv81Mode2Active: Boolean,
        val dv7Mode1AlreadyForced: Boolean,
        val currentVideoTrackIsLikelyVc1: Boolean,
        val isVc1SoftwareFallbackActive: Boolean,
        val currentVideoTrackSelected: Boolean,
        val isVc1TrackSelectionBypassActive: Boolean,
    )

    sealed class RecoveryAction {
        data object None : RecoveryAction()
        data object RetryDv7Mode1 : RecoveryAction()
        data object RetryVc1Software : RecoveryAction()
        data object RetryVc1TrackBypass : RecoveryAction()
    }

    fun evaluateAfterWatchdogTimeout(input: Input): RecoveryAction {
        if (!input.playWhenReady) return RecoveryAction.None

        if (input.isManualDv81Mode2Active && !input.dv7Mode1AlreadyForced) {
            return RecoveryAction.RetryDv7Mode1
        }
        if (input.currentVideoTrackIsLikelyVc1 && !input.isVc1SoftwareFallbackActive) {
            return RecoveryAction.RetryVc1Software
        }
        if (input.currentVideoTrackIsLikelyVc1 &&
            !input.currentVideoTrackSelected &&
            input.isVc1SoftwareFallbackActive &&
            !input.isVc1TrackSelectionBypassActive
        ) {
            return RecoveryAction.RetryVc1TrackBypass
        }
        return RecoveryAction.None
    }
}
