package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.SurroundFormatResolver

// Tunnelled video releases frames against the platform's hw_av_sync audio clock. When the
// HAL never starts that clock the player sits in STATE_READY with data buffered and no
// media3 or app watchdog fires. Two oracles, because HALs fail differently:
//  - position progress: on Amlogic the codec reports frames rendered even while the tunnel
//    renderer holds them, so a frozen position is the only signal (seen for app-packed
//    IEC 61937 and for PCM under tunnelling);
//  - rendered-frame count: on MediaTek (Fire TV) the counter stays at zero while the picture
//    is held, and is the earlier signal.
// Either disarms tunnelling for the stream. Only a frozen position marks the audio class as
// dead-clocked: a held picture with an advancing position is not an audio-clock fault.
internal object PlayerTunnelAvSyncPolicy {

    // Audio classes (PlaybackSpeedAwareAudioSink.tunnelAudioClass) seen with a dead tunnel
    // clock since the process started. Process scope on purpose: the player controller is
    // recreated per title, and this is a property of the device, not of the stream.
    // Written on the main thread by the watchdog, read on the playback thread by the selector.
    val deadAudioClasses: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet()

    // The persisted copy of the memo carries the signature of the chain that learned it:
    // firmware build, the box's output port and the sink's advertised claims. A different
    // sink, route or firmware gives a different signature, and a memo recorded under one
    // signature is left dormant under another until the watchdog records again.
    fun chainSignature(
        fingerprint: String,
        routeKey: String,
        direct: SurroundFormatResolver.DirectSupport?,
        maxPcmChannels: Int?,
    ): String {
        val claims = direct?.let {
            listOf(it.ac3, it.eac3, it.trueHd, it.dts, it.dtsHd)
                .joinToString("") { claimed -> if (claimed) "1" else "0" }
        } ?: "-"
        return "$fingerprint|$routeKey|$claims|${maxPcmChannels ?: "-"}"
    }

    @Volatile
    private var seededSignature: String? = null

    // Seeds the process memo from the persisted one, once per signature per process and
    // only when the persisted signature is the chain in front of us. A different
    // signature later in the same process (the route changed) drops what the previous
    // chain taught. Returns the classes that were seeded.
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

    // The user's "try again": forget the process memo and re-seed at the next build.
    fun resetMemo() {
        deadAudioClasses.clear()
        seededSignature = null
    }

    // How many watchdog intervals the untunnelled rebuild gets to advance its position
    // before a pending memo is dropped instead of persisted.
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
        // Null when the player has no video decoder counters; treated as unknown, never as zero.
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
