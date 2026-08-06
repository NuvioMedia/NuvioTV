package com.nuvio.tv.ui.screens.player

/**
 * Pure startup playback decisions for [Player.STATE_READY] before and after the first
 * rendered frame. Extracted so instrumented fixture tests can mirror production 1:1.
 */
internal object PlayerStartupPlaybackPolicy {

    data class ReadyState(
        val shouldEnforceAutoplayOnFirstReady: Boolean,
        val hasRenderedFirstFrame: Boolean,
        val userPausedManually: Boolean,
        val startPaused: Boolean,
        val isTunneledPlayback: Boolean,
    )

    sealed class ReadyAction {
        data object None : ReadyAction()
        data class TunneledFirstReady(
            val setPlayWhenReady: Boolean,
            val callPlay: Boolean,
        ) : ReadyAction()
        /**
         * Initial cold start reached READY: prime the decoder with [setPlayWhenReady] but defer
         * [callPlay] until [onRenderedFirstFrame] so audio does not race ahead of video.
         */
        data class ColdStartPrime(
            val setPlayWhenReady: Boolean,
            val callPlay: Boolean,
        ) : ReadyAction()
        data class PostFirstFrameResume(val callPlay: Boolean) : ReadyAction()
        /** Resume seek / track rebuild reached READY again before first frame. */
        data class PreFirstFrameResume(
            val setPlayWhenReady: Boolean,
            val callPlay: Boolean,
        ) : ReadyAction()
    }

    data class ReadyTransition(
        val nextState: ReadyState,
        val action: ReadyAction,
    )

    fun onStateReady(state: ReadyState): ReadyTransition {
        if (state.shouldEnforceAutoplayOnFirstReady) {
            val next = state.copy(shouldEnforceAutoplayOnFirstReady = false)
            return if (state.isTunneledPlayback) {
                ReadyTransition(
                    nextState = next.copy(hasRenderedFirstFrame = true),
                    action = ReadyAction.TunneledFirstReady(
                        setPlayWhenReady = !state.startPaused && !state.userPausedManually,
                        callPlay = !state.startPaused && !state.userPausedManually,
                    )
                )
            } else if (!state.startPaused && !state.userPausedManually) {
                ReadyTransition(
                    nextState = next,
                    action = ReadyAction.ColdStartPrime(
                        setPlayWhenReady = true,
                        callPlay = false,
                    ),
                )
            } else {
                ReadyTransition(nextState = next, action = ReadyAction.None)
            }
        }

        if (state.userPausedManually || state.startPaused) {
            return ReadyTransition(state, ReadyAction.None)
        }

        return if (state.hasRenderedFirstFrame) {
            ReadyTransition(state, ReadyAction.PostFirstFrameResume(callPlay = true))
        } else {
            ReadyTransition(
                state,
                ReadyAction.PreFirstFrameResume(setPlayWhenReady = true, callPlay = true)
            )
        }
    }
}
