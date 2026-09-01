package com.nuvio.tv.ui.screens.player

/**
 * Tunnelled video releases frames against the hw_av_sync audio clock. When the HBR
 * IEC passthrough track is not bound to that session no frame is ever released
 * (black screen, audio plays) and Media3's stuck detector does not fire while the
 * player stays in STATE_READY. Detected via videoDecoderCounters staying at zero.
 */
internal object PlayerTunnelAvSyncPolicy {

    data class Input(
        val isTunnelingActive: Boolean,
        val isIecHbrActive: Boolean,
        val hasVideoTrack: Boolean,
        val isReady: Boolean,
        val playWhenReady: Boolean,
        val userPausedManually: Boolean,
        val renderedOutputBufferCount: Int,
        val tunnelingAlreadyDisarmed: Boolean,
    )

    sealed class Decision {
        data object None : Decision()
        data object DisableTunnelingAndRebuild : Decision()
    }

    fun evaluate(input: Input): Decision {
        val noAction = !input.isTunnelingActive ||
            !input.isIecHbrActive ||
            !input.hasVideoTrack ||
            input.tunnelingAlreadyDisarmed ||
            !input.isReady ||
            !input.playWhenReady ||
            input.userPausedManually ||
            input.renderedOutputBufferCount > 0
        return if (noAction) Decision.None else Decision.DisableTunnelingAndRebuild
    }
}
