package com.nuvio.tv.ui.screens.player

/** Hands each AFR preflight a token. A stream switch or a player release makes older tokens stale. */
internal class AfrPreflightGate {
    @Volatile private var generation = 0L

    fun token(): Long = generation

    fun cancel() {
        generation++
    }

    fun isCurrent(token: Long): Boolean = token == generation
}
