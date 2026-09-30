package com.nuvio.tv.ui.screens.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Wraps a [Player] so that [getBufferedPercentage] never throws when
 * Media3 [MediaSession] internals call [androidx.media3.common.util.Util.percentInt].
 */
internal class SafeMediaSessionPlayer(player: Player) : ForwardingPlayer(player) {

    override fun getBufferedPercentage(): Int {
        return try {
            super.getBufferedPercentage().coerceIn(0, 100)
        } catch (_: IllegalArgumentException) {
            // Util.percentInt overflow — live stream with huge bufferedPosition
            100
        }
    }
}
