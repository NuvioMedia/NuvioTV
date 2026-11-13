package com.nuvio.tv.ui.screens.player.iec

internal class IecFlushSettleGate(
    private val settleNanos: Long = DEFAULT_SETTLE_NANOS,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private var armed = false
    private var flushedAtNanos = 0L

    fun onFlush() {
        armed = true
        flushedAtNanos = nanoTime()
    }

    fun isHolding(): Boolean {
        if (!armed) return false
        return nanoTime() - flushedAtNanos < settleNanos
    }

    fun mayPlay(): Boolean {
        if (!isHolding()) {
            armed = false
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_SETTLE_NANOS = 150_000_000L
    }
}
