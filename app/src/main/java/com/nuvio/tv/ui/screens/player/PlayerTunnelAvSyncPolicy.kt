package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.SurroundFormatResolver

internal object PlayerTunnelAvSyncPolicy {

    val deadAudioClasses: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet()

    fun chainSignature(
        fingerprint: String,
        routeKey: String,
        direct: SurroundFormatResolver.DirectSupport?,
        maxPcmChannels: Int?,
    ): String {
        val claims = direct?.let {
            listOf(it.ac3, it.eac3, it.trueHd, it.dts, it.dtsHd)
                .joinToString("") { claimed -> if (claimed) "1" else "0" }
        } ?: UNREAD
        return "$fingerprint|$routeKey|$claims|${maxPcmChannels ?: UNREAD}"
    }

    private const val UNREAD = "na"

    @Volatile
    private var seededSignature: String? = null

    fun seedFromStore(
        classes: Set<String>,
        storedSignature: String?,
        currentSignature: String,
    ): Set<String> {
        if (seededSignature == currentSignature) return emptySet()
        if (seededSignature != null) deadAudioClasses.clear()
        seededSignature = currentSignature
        if (classes.isEmpty() || storedSignature != currentSignature) return emptySet()
        deadAudioClasses.addAll(classes)
        return classes
    }

    fun resetMemo() {
        deadAudioClasses.clear()
        seededSignature = null
    }

    const val MEMO_CONFIRM_SAMPLES = 20

    data class Input(
        val isTunnelingActive: Boolean,
        val hasVideoTrack: Boolean,
        val isReady: Boolean,
        val playWhenReady: Boolean,
        val userPausedManually: Boolean,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val lastPositionMs: Long?,
        val stalledMs: Long,
        val intervalMs: Long,
        val stallThresholdMs: Long,
        val renderedOutputBufferCount: Int?,
        val readyMs: Long,
        val noFrameThresholdMs: Long,
        val tunnelingAlreadyDisarmed: Boolean,
    )

    sealed class Decision {
        data object None : Decision()
        data object Stop : Decision()
        data object DisableTunnelingAndRebuild : Decision()
    }

    enum class Reason { PositionFrozen, NoFramesRendered }

    data class Result(
        val decision: Decision,
        val stalledMs: Long,
        val readyMs: Long,
        val reason: Reason? = null,
    )

    fun evaluate(input: Input): Result {
        if (!input.isTunnelingActive || !input.hasVideoTrack || input.tunnelingAlreadyDisarmed) {
            return Result(Decision.Stop, 0L, 0L)
        }
        if (!input.isReady || !input.playWhenReady || input.userPausedManually) {
            return Result(Decision.None, 0L, 0L)
        }
        val readyMs = input.readyMs + input.intervalMs
        val last = input.lastPositionMs
        val stalledMs = when {
            last == null -> 0L
            input.positionMs != last -> 0L
            input.bufferedPositionMs <= input.positionMs -> 0L
            else -> input.stalledMs + input.intervalMs
        }
        if (stalledMs >= input.stallThresholdMs) {
            return Result(Decision.DisableTunnelingAndRebuild, stalledMs, readyMs, Reason.PositionFrozen)
        }
        if (input.renderedOutputBufferCount == 0 && readyMs >= input.noFrameThresholdMs) {
            return Result(Decision.DisableTunnelingAndRebuild, stalledMs, readyMs, Reason.NoFramesRendered)
        }
        return Result(Decision.None, stalledMs, readyMs)
    }
}
