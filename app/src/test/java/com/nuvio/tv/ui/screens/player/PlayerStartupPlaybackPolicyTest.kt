package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartupPlaybackPolicyTest {

    private fun baseState(
        shouldEnforceAutoplayOnFirstReady: Boolean = true,
        hasRenderedFirstFrame: Boolean = false,
        userPausedManually: Boolean = false,
        startPaused: Boolean = false,
        isTunneledPlayback: Boolean = false,
    ) = PlayerStartupPlaybackPolicy.ReadyState(
        shouldEnforceAutoplayOnFirstReady = shouldEnforceAutoplayOnFirstReady,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        userPausedManually = userPausedManually,
        startPaused = startPaused,
        isTunneledPlayback = isTunneledPlayback,
    )

    @Test
    fun firstReadyColdStartPrimesDecoderWithoutPlay() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(baseState())

        assertFalse(transition.nextState.shouldEnforceAutoplayOnFirstReady)
        val action = transition.action as PlayerStartupPlaybackPolicy.ReadyAction.ColdStartPrime
        assertTrue(action.setPlayWhenReady)
        assertFalse(action.callPlay)
    }

    @Test
    fun firstReadyTunneledStartsPlaybackImmediately() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(isTunneledPlayback = true)
        )

        assertTrue(transition.nextState.hasRenderedFirstFrame)
        val action = transition.action as PlayerStartupPlaybackPolicy.ReadyAction.TunneledFirstReady
        assertTrue(action.setPlayWhenReady)
        assertTrue(action.callPlay)
    }

    @Test
    fun firstReadyStartPausedDoesNothing() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(startPaused = true)
        )

        assertEquals(PlayerStartupPlaybackPolicy.ReadyAction.None, transition.action)
    }

    @Test
    fun secondReadyBeforeFirstFrameResumesPlayback() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(
                shouldEnforceAutoplayOnFirstReady = false,
                hasRenderedFirstFrame = false,
            )
        )

        val action = transition.action as PlayerStartupPlaybackPolicy.ReadyAction.PreFirstFrameResume
        assertTrue(action.setPlayWhenReady)
        assertTrue(action.callPlay)
    }

    @Test
    fun secondReadyAfterFirstFrameCallsPlayOnly() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(
                shouldEnforceAutoplayOnFirstReady = false,
                hasRenderedFirstFrame = true,
            )
        )

        val action = transition.action as PlayerStartupPlaybackPolicy.ReadyAction.PostFirstFrameResume
        assertTrue(action.callPlay)
    }

    @Test
    fun userPausedAfterFirstReadyGateDoesNothing() {
        val transition = PlayerStartupPlaybackPolicy.onStateReady(
            baseState(
                shouldEnforceAutoplayOnFirstReady = false,
                hasRenderedFirstFrame = true,
                userPausedManually = true,
            )
        )

        assertEquals(PlayerStartupPlaybackPolicy.ReadyAction.None, transition.action)
    }
}
