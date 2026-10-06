package com.nuvio.tv.core.connection

import android.content.Context
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream

/**
 * Moves streams likely too heavy for the current connection to the bottom of each list and
 * leaves everything else exactly where the addon (or the user's sort) put it. Streams whose
 * bitrate can't be known stay in place: missing metadata is not evidence of a heavy stream.
 * Capture it once per stream load, so the order never shifts while a list is open.
 */
internal class StreamConnectionFit(
    private val runtimeMinutes: Int,
    private val connectionMbps: Double,
) {
    /** Streams whose average bitrate is above this can't sustain playback with headroom. */
    private val maxBitrateMbps = connectionMbps / BITRATE_HEADROOM

    /** Returns [groups] itself, and each unchanged group itself, when nothing needs to move. */
    fun applyToGroups(groups: List<AddonStreams>): List<AddonStreams> {
        var result: ArrayList<AddonStreams>? = null
        for (index in groups.indices) {
            val group = groups[index]
            val streams = apply(group.streams)
            if (streams === group.streams && result == null) continue
            if (result == null) result = ArrayList<AddonStreams>(groups.size).apply { addAll(groups.subList(0, index)) }
            result += if (streams === group.streams) group else group.copy(streams = streams)
        }
        return result ?: groups
    }

    /**
     * Stable partition: kept streams in their order, then heavy streams in their order. Returns
     * the same list when nothing needs to move, which is the common case.
     */
    fun apply(streams: List<Stream>): List<Stream> {
        if (streams.size < 2) return streams
        var firstHeavy = -1
        var mustMove = false
        for (index in streams.indices) {
            if (isHeavy(streams[index])) {
                if (firstHeavy < 0) firstHeavy = index
            } else if (firstHeavy >= 0) {
                mustMove = true
                break
            }
        }
        if (!mustMove) return streams

        val ordered = ArrayList<Stream>(streams.size)
        val heavy = ArrayList<Stream>(streams.size - firstHeavy)
        for (index in 0 until firstHeavy) ordered += streams[index]
        heavy += streams[firstHeavy]
        for (index in firstHeavy + 1 until streams.size) {
            val stream = streams[index]
            if (isHeavy(stream)) heavy += stream else ordered += stream
        }
        ordered.addAll(heavy)
        return ordered
    }

    private fun isHeavy(stream: Stream): Boolean {
        val bitrateMbps = stream.averageBitrateMbps(runtimeMinutes) ?: return false
        return bitrateMbps > maxBitrateMbps
    }

    companion object {
        /** Bitrate peaks run well above a file's average; the player buffer only absorbs part of that. */
        private const val BITRATE_HEADROOM = 1.5

        /** Returns null, leaving order untouched, when disabled or when speed or runtime is unknown. */
        fun capture(context: Context, runtimeMinutes: Int?): StreamConnectionFit? {
            ConnectionSpeedEstimator.ensureLoaded(context)
            if (!ConnectionSpeedEstimator.enabled.value) return null
            val minutes = runtimeMinutes?.takeIf { it > 0 } ?: return null
            val connectionMbps = ConnectionSpeedEstimator.estimateMbps(context) ?: return null
            return StreamConnectionFit(minutes, connectionMbps)
        }

        /** As [capture], taking the runtime from the playing file's duration. */
        fun captureByDuration(context: Context, durationMs: Long): StreamConnectionFit? =
            capture(context, (durationMs / 60_000L).toInt().takeIf { durationMs > 0L })
    }
}

private const val MIN_RUNTIME_MINUTES = 10
private const val MAX_RUNTIME_MINUTES = 600
private const val MIN_SIZE_BYTES = 50L * 1024 * 1024
private const val MIN_PLAUSIBLE_MBPS = 0.2
private const val MAX_PLAUSIBLE_MBPS = 200.0

/**
 * Average bitrate from file size and runtime, or null when either is missing or the result is
 * implausible (for example a season pack's size reported for a single episode).
 */
internal fun Stream.averageBitrateMbps(runtimeMinutes: Int): Double? {
    if (runtimeMinutes !in MIN_RUNTIME_MINUTES..MAX_RUNTIME_MINUTES) return null
    val sizeBytes = clientResolve?.stream?.raw?.size ?: behaviorHints?.videoSize ?: return null
    if (sizeBytes < MIN_SIZE_BYTES) return null
    val mbps = sizeBytes * 8.0 / (runtimeMinutes * 60.0) / 1_000_000.0
    return mbps.takeIf { it in MIN_PLAUSIBLE_MBPS..MAX_PLAUSIBLE_MBPS }
}
