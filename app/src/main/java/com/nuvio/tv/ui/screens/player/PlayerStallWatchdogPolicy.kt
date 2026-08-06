package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C

/**
 * Pure stall-watchdog decisions extracted from [PlayerRuntimeControllerObservers]
 * so device/JVM fixture tests can mirror production 1:1.
 */
internal object PlayerStallWatchdogPolicy {

    const val SKIP_PAST_BUFFERED_MS = 250L

    data class Input(
        val bufferedPositionMs: Long,
        val playheadMs: Long,
        val durationMs: Long,
        val stalledForMs: Long,
        val thresholdMs: Long = PlayerRuntimeController.STALL_WATCHDOG_THRESHOLD_MS,
        val skipPastBufferedMs: Long = SKIP_PAST_BUFFERED_MS,
    )

    sealed class Decision {
        data object KeepWaiting : Decision()
        data object SkipUnknownDuration : Decision()
        data object SkipBufferedNotAhead : Decision()
        data object SkipTargetNotForward : Decision()
        data class SeekPastBufferedEdge(val targetMs: Long) : Decision()
    }

    fun evaluate(input: Input): Decision {
        if (input.stalledForMs < input.thresholdMs) {
            return Decision.KeepWaiting
        }
        val playheadMs = input.playheadMs.coerceAtLeast(0L)
        val durationMs = input.durationMs
        if (durationMs == C.TIME_UNSET || durationMs <= 0L) {
            return Decision.SkipUnknownDuration
        }
        val bufferedNow = input.bufferedPositionMs
        if (bufferedNow <= playheadMs) {
            return Decision.SkipBufferedNotAhead
        }
        val targetPastBufferedMs = if (bufferedNow > Long.MAX_VALUE - input.skipPastBufferedMs) {
            Long.MAX_VALUE
        } else {
            bufferedNow + input.skipPastBufferedMs
        }
        val seekTargetMs = targetPastBufferedMs.coerceAtMost((durationMs - 1L).coerceAtLeast(0L))
        if (seekTargetMs <= playheadMs || seekTargetMs <= 0L) {
            return Decision.SkipTargetNotForward
        }
        return Decision.SeekPastBufferedEdge(seekTargetMs)
    }
}
