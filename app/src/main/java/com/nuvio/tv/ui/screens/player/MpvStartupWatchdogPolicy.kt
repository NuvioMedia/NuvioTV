package com.nuvio.tv.ui.screens.player

internal object MpvStartupWatchdogPolicy {
    const val POLL_INTERVAL_MS = 500L
    const val SURFACE_WAIT_MS = 8_000L
    const val IDLE_AFTER_LOAD_MS = 4_000L
    const val STALL_TIMEOUT_MS = 20_000L
    const val ABSOLUTE_TIMEOUT_MS = 30_000L
    const val MIN_CACHE_GROWTH_SEC = 0.25

    val surfaceWaitTicks: Int = (SURFACE_WAIT_MS / POLL_INTERVAL_MS).toInt()
    val idleTicksLimit: Int = (IDLE_AFTER_LOAD_MS / POLL_INTERVAL_MS).toInt()
    val stallTicksLimit: Int = (STALL_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
    val absoluteTicksLimit: Int = (ABSOLUTE_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()

    // An MPEG-TS from the Usenet engine fills no demuxer cache while lavf probes it: it reads
    // until every stream, Blu-ray PGS subtitles included, has parameters, then reads the last
    // timestamps at the end of the file for the duration. A 76 GB disc clip took 16 s with
    // part of it already fetched.
    const val SLOW_PROBE_STALL_TIMEOUT_MS = 60_000L
    const val SLOW_PROBE_ABSOLUTE_TIMEOUT_MS = 90_000L

    data class Limits(val stallTicks: Int, val absoluteTicks: Int)

    val defaultLimits = Limits(stallTicksLimit, absoluteTicksLimit)
    val slowProbeLimits = Limits(
        (SLOW_PROBE_STALL_TIMEOUT_MS / POLL_INTERVAL_MS).toInt(),
        (SLOW_PROBE_ABSOLUTE_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
    )

    fun limitsFor(url: String, usenetSession: Boolean): Limits {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val transportStream = path.endsWith(".m2ts") || path.endsWith(".mts") || path.endsWith(".ts")
        return if (usenetSession && transportStream) slowProbeLimits else defaultLimits
    }

    enum class Action {
        Continue,
        SurfaceTimeout,
        IdleError,
        StallTimeout,
        AbsoluteTimeout,
    }

    data class Counters(
        val surfaceWaitTicks: Int = 0,
        val idleTicks: Int = 0,
        val stallTicks: Int = 0,
        val absoluteTicks: Int = 0,
    )

    data class Input(
        val enabled: Boolean,
        val waitingForSurface: Boolean,
        val idleActive: Boolean,
        val cacheProgressing: Boolean,
        val counters: Counters,
        val limits: Limits = defaultLimits,
    )

    data class Step(
        val action: Action,
        val counters: Counters,
    )

    fun cacheIsProgressing(previousSec: Double, currentSec: Double): Boolean {
        return currentSec > previousSec + MIN_CACHE_GROWTH_SEC
    }

    fun step(input: Input): Step {
        if (!input.enabled) {
            return Step(Action.Continue, Counters())
        }
        if (input.waitingForSurface) {
            val next = input.counters.surfaceWaitTicks + 1
            if (next >= surfaceWaitTicks) {
                return Step(Action.SurfaceTimeout, Counters())
            }
            return Step(Action.Continue, Counters(surfaceWaitTicks = next))
        }

        val absolute = input.counters.absoluteTicks + 1
        if (input.idleActive) {
            val idle = input.counters.idleTicks + 1
            if (idle >= idleTicksLimit) {
                return Step(Action.IdleError, Counters())
            }
            if (absolute >= input.limits.absoluteTicks) {
                return Step(Action.AbsoluteTimeout, Counters())
            }
            return Step(
                Action.Continue,
                Counters(
                    idleTicks = idle,
                    stallTicks = input.counters.stallTicks,
                    absoluteTicks = absolute
                )
            )
        }

        if (absolute >= input.limits.absoluteTicks) {
            return Step(Action.AbsoluteTimeout, Counters())
        }
        val stall = if (input.cacheProgressing) 0 else input.counters.stallTicks + 1
        if (stall >= input.limits.stallTicks) {
            return Step(Action.StallTimeout, Counters())
        }
        return Step(
            Action.Continue,
            Counters(stallTicks = stall, absoluteTicks = absolute)
        )
    }
}
