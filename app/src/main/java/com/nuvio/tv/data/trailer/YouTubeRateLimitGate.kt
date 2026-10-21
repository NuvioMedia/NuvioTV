package com.nuvio.tv.data.trailer

/**
 * Stops YouTube extraction for a while once YouTube starts refusing requests (HTTP 429 or
 * the "confirm you're not a bot" check). Every request made while blocked keeps the block
 * going, so backing off is the only way out. Each strike doubles the pause, up to
 * [MAX_COOLDOWN_MS]; a successful extraction resets it.
 */
internal class YouTubeRateLimitGate(
    private val now: () -> Long = System::currentTimeMillis
) {
    private var strikes = 0
    private var blockedUntil = 0L

    @Synchronized
    fun isBlocked(): Boolean = now() < blockedUntil

    @Synchronized
    fun remainingMs(): Long = (blockedUntil - now()).coerceAtLeast(0L)

    /** Starts (or extends) the pause and returns its length. */
    @Synchronized
    fun onRateLimited(): Long {
        strikes++
        val cooldown = (BASE_COOLDOWN_MS shl (strikes - 1).coerceAtMost(MAX_DOUBLINGS))
            .coerceAtMost(MAX_COOLDOWN_MS)
        blockedUntil = now() + cooldown
        return cooldown
    }

    @Synchronized
    fun onSuccess() {
        strikes = 0
        blockedUntil = 0L
    }

    companion object {
        const val BASE_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 15 * 60_000L
        private const val MAX_DOUBLINGS = 4
    }
}
