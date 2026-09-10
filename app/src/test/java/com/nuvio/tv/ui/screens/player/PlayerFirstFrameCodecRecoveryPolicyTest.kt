package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerFirstFrameCodecRecoveryPolicyTest {

    @Test
    fun evaluate_whenNotPlayWhenReady_returnsNone() {
        val input = PlayerFirstFrameCodecRecoveryPolicy.Input(
            playWhenReady = false,
            isManualDv81Mode2Active = false,
            dv7Mode1AlreadyForced = false,
            currentVideoTrackIsLikelyVc1 = true,
        )
        assertEquals(PlayerFirstFrameCodecRecoveryPolicy.RecoveryAction.None, PlayerFirstFrameCodecRecoveryPolicy.evaluateAfterWatchdogTimeout(input))
    }

    @Test
    fun evaluate_whenVc1_failsWithoutRetry() {
        val input = PlayerFirstFrameCodecRecoveryPolicy.Input(
            playWhenReady = true,
            isManualDv81Mode2Active = false,
            dv7Mode1AlreadyForced = false,
            currentVideoTrackIsLikelyVc1 = true,
        )
        assertEquals(
            PlayerFirstFrameCodecRecoveryPolicy.RecoveryAction.FailVc1Unsupported,
            PlayerFirstFrameCodecRecoveryPolicy.evaluateAfterWatchdogTimeout(input)
        )
    }
}
