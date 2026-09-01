package com.nuvio.tv.ui.screens.player

// Tunnelled video releases frames against the platform's hw_av_sync audio clock. When the
// HAL never starts that clock (seen on Amlogic for app-packed IEC 61937 and for PCM) the
// player sits in STATE_READY with data buffered and its position never advances, and no
// media3 or app watchdog fires. Rendered-frame counters do not detect it: the codec reports
// frames rendered while the tunnel renderer holds them. Position progress is the oracle.
internal object PlayerTunnelAvSyncPolicy {

    // Audio classes (PlaybackSpeedAwareAudioSink.tunnelAudioClass) seen with a dead tunnel
    // clock since the process started. Process scope on purpose: the player controller is
    // recreated per title, and this is a property of the device, not of the stream.
    // Written on the main thread by the watchdog, read on the playback thread by the selector.
    val deadAudioClasses: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet()

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
        val tunnelingAlreadyDisarmed: Boolean,
    )

    sealed class Decision {
        data object None : Decision()
        data object Stop : Decision()
        data object DisableTunnelingAndRebuild : Decision()
    }

    data class Result(val decision: Decision, val stalledMs: Long)

    fun evaluate(input: Input): Result {
        if (!input.isTunnelingActive || !input.hasVideoTrack || input.tunnelingAlreadyDisarmed) {
            return Result(Decision.Stop, 0L)
        }
        if (!input.isReady || !input.playWhenReady || input.userPausedManually) {
            return Result(Decision.None, 0L)
        }
        val last = input.lastPositionMs ?: return Result(Decision.None, 0L)
        if (input.positionMs != last) return Result(Decision.None, 0L)
        if (input.bufferedPositionMs <= input.positionMs) return Result(Decision.None, 0L)
        val stalled = input.stalledMs + input.intervalMs
        return if (stalled >= input.stallThresholdMs) {
            Result(Decision.DisableTunnelingAndRebuild, stalled)
        } else {
            Result(Decision.None, stalled)
        }
    }
}
