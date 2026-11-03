package com.nuvio.tv.core.player

import android.util.Log
import com.nuvio.tv.libass.media.effect.AssFrameTimeMapper
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.LinkedBlockingDeque

/**
 * One player's state for mapping Dolby Vision Profile 5 to HDR10.
 *
 * Profile 5 has no HDR10 base layer: its pixels only look right once each frame's RPU (reshaping
 * curves and colour matrices) is applied. [DolbyVisionP5Extractor] reads the RPU of every video
 * sample and records which mapping it carries at that sample's time; identical mappings (in
 * practice a scene) share one hash, so a LUT is only built when the mapping changes.
 * [DolbyVisionP5HdrEffect] then looks up the mapping for the time of each frame it draws.
 *
 * A stream starts with an inactive session that only detects Profile 5 ([detected]); the player
 * is then rebuilt once with an [active] session, which rewrites the track to HDR10 and installs
 * the effect (video effects can only be set before the player is prepared).
 */
internal class DolbyVisionP5Session(val active: Boolean) {

    /** True once a Profile 5 video track was seen. */
    @Volatile
    var detected: Boolean = false
        private set

    /** Set by the Matroska transformer, which sees the Dolby Vision config before the output does. */
    @Volatile
    var containerSignalledProfile5: Boolean = false
        private set

    /** Maps the effect's frame timestamps back to media time (fed by the video renderer). */
    val frameTimeMapper = AssFrameTimeMapper()

    private val timeline = ConcurrentSkipListMap<Long, Long>()
    private val lutLock = Any()
    private val luts = object : LinkedHashMap<Long, ShortArray>(MAX_LUTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ShortArray>?): Boolean =
            size > MAX_LUTS
    }
    private val wanted = LinkedBlockingDeque<Long>()
    @Volatile
    private var released = false
    private val worker: Thread? = if (active) {
        DolbyVisionP5Native.reset()
        Thread(::buildLoop, "DvP5Lut").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    } else {
        null
    }

    fun onProfile5Track() {
        detected = true
    }

    fun onContainerProfile5() {
        containerSignalledProfile5 = true
        detected = true
    }

    /** Records that the sample at [timeUs] carries mapping [hash] (0 = none: draw it as is). */
    fun onSampleMapping(timeUs: Long, hash: Long) {
        timeline[timeUs] = hash
        while (timeline.size > MAX_TIMELINE_ENTRIES) timeline.pollFirstEntry()
    }

    /** The mapping of the sample shown at [mediaTimeUs]. */
    fun mappingAt(mediaTimeUs: Long): Long =
        timeline.floorEntry(mediaTimeUs + MATCH_TOLERANCE_US)?.value ?: 0L

    /** The distinct mappings due soon after [mediaTimeUs], so their LUTs are ready in time. */
    fun upcomingMappings(mediaTimeUs: Long): List<Long> {
        val result = ArrayList<Long>(MAX_PREFETCH)
        for (hash in timeline.subMap(mediaTimeUs, true, mediaTimeUs + LOOKAHEAD_US, true).values) {
            if (hash != 0L && hash !in result) {
                result += hash
                if (result.size == MAX_PREFETCH) break
            }
        }
        return result
    }

    fun lutOrNull(hash: Long): ShortArray? = synchronized(lutLock) { luts[hash] }

    /** Asks the background builder for these LUTs, first one first; replaces older requests. */
    fun request(hashes: List<Long>) {
        if (worker == null || released) return
        val missing = hashes.filter { it != 0L && lutOrNull(it) == null }
        if (missing.isEmpty()) return
        wanted.clear()
        missing.forEach(wanted::offer)
    }

    /** Builds a LUT on the calling thread (used once, before the first mapped frame). */
    fun buildNow(hash: Long): ShortArray? = build(hash)

    fun release() {
        released = true
        worker?.interrupt()
        wanted.clear()
        timeline.clear()
        synchronized(lutLock) { luts.clear() }
    }

    private fun buildLoop() {
        while (!released) {
            val hash = try {
                wanted.take()
            } catch (_: InterruptedException) {
                return
            }
            build(hash)
        }
    }

    private fun build(hash: Long): ShortArray? {
        if (hash == 0L || released) return null
        lutOrNull(hash)?.let { return it }
        val out = ShortArray(LUT_SIZE * LUT_SIZE * LUT_SIZE * 4)
        if (!DolbyVisionP5Native.buildLut(hash, LUT_SIZE, out)) {
            Log.w(TAG, "No mapping for hash ${java.lang.Long.toHexString(hash)}")
            return null
        }
        synchronized(lutLock) { luts[hash] = out }
        return out
    }

    companion object {
        private const val TAG = "DolbyVisionP5"

        /** LUT points per axis. 33 is what TVs use for their own colour LUTs. */
        const val LUT_SIZE = 33
        private const val MAX_LUTS = 12
        /** About 20 minutes of 24 fps samples; the extractor reads far less ahead than that. */
        private const val MAX_TIMELINE_ENTRIES = 30_000
        private const val MATCH_TOLERANCE_US = 500L
        private const val LOOKAHEAD_US = 3_000_000L
        private const val MAX_PREFETCH = 3
    }
}
