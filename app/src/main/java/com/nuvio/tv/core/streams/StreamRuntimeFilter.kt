package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import kotlin.math.abs

/**
 * Filters fetched stream results down to the ones whose known duration is
 * close to the content's actual runtime (e.g. the movie's runtime from its
 * metadata), so mislabeled or wrong-length releases get dropped.
 */
object StreamRuntimeFilter {

    private const val SECONDS_PER_MINUTE = 60.0

    fun filter(
        groups: List<AddonStreams>,
        expectedRuntimeMinutes: Int?,
        toleranceMinutes: Int = 10
    ): List<AddonStreams> {
        if (expectedRuntimeMinutes == null || expectedRuntimeMinutes <= 0) return groups

        return groups.mapNotNull { group ->
            val filteredStreams = group.streams.filter { stream ->
                matchesRuntime(stream, expectedRuntimeMinutes, toleranceMinutes)
            }
            if (filteredStreams.isEmpty()) null else group.copy(streams = filteredStreams)
        }
    }

    private fun matchesRuntime(
        stream: Stream,
        expectedRuntimeMinutes: Int,
        toleranceMinutes: Int
    ): Boolean {
        val durationSeconds = stream.clientResolve?.stream?.raw?.parsed?.duration ?: return true
        val durationMinutes = durationSeconds / SECONDS_PER_MINUTE
        return abs(durationMinutes - expectedRuntimeMinutes) <= toleranceMinutes
    }
}
