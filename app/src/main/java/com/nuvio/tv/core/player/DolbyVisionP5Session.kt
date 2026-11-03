package com.nuvio.tv.core.player

import android.util.Log
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * One player's state for mapping Dolby Vision Profile 5 to HDR10.
 *
 * Profile 5 has no HDR10 base layer: its pixels only look right once each frame's RPU (reshaping
 * curves and colour matrices) is applied. [DolbyVisionP5Extractor] reads the RPU of every video
 * sample and records which mapping it carries at that sample's time; identical mappings (in
 * practice a scene) share one hash, so a LUT is only built when the mapping changes.
 * [DolbyVisionP5SurfaceRenderer] then looks up the mapping for the time of each frame it draws.
 *
 * An [active] session is set up before the player is built, whenever the device can map. When a
 * Profile 5 track shows up, the extractor rewrites it to plain HEVC and the player routes the
 * decoder's output through the renderer, which draws it as HDR10. A session with a
 * [blockedReason] only detects Profile 5, so the player can say why the stream isn't mapped.
 */
internal class DolbyVisionP5Session(
    val active: Boolean,
    /** Why this device can't map Profile 5, or null when it can. */
    val blockedReason: BlockedReason? = null,
) {

    enum class BlockedReason {
        NO_LIBRARY, DISPLAY_DOLBY_VISION, DISPLAY_NOT_HDR10, DV81_CONVERSION, PLAYBACK_ERROR,
        /** The GPU can't sample decoded 10-bit YUV as is (no EXT_YUV_target). */
        GPU_NO_YUV_TARGET,
        /** The GPU can't output HDR10 (no EGL BT.2020 PQ colour space). */
        GPU_NO_HDR_OUTPUT,
        /** The GPU check itself failed. */
        GPU,
        /** An earlier stream never showed a frame through the effect: this device can't run it. */
        EFFECT_STALLED,
    }

    /** What an active session found, for the player's note a few seconds into playback. */
    sealed class Status {
        object NoFrames : Status()
        /** The output surface couldn't be marked as HDR10. */
        object OutputNotHdr10 : Status()
        object NoRpu : Status()
        data class OtherProfile(val profile: Int) : Status()
        object RpuUnreadable : Status()
        object NoLut : Status()
        object NotMapped : Status()
        data class Mapping(val tables: Int) : Status()
    }

    /** True once a Profile 5 video track was seen (by its codecs or by its RPU). */
    @Volatile
    var detected: Boolean = false
        private set

    /** Called once, on a loader thread, when [detected] becomes true. */
    @Volatile
    var onDetected: (() -> Unit)? = null

    /** Set by the Matroska transformer, which sees the Dolby Vision config before the output does. */
    @Volatile
    var containerSignalledProfile5: Boolean = false
        private set

    // The media time of each frame the decoder released to the renderer, by its release time
    // (what the renderer's SurfaceTexture reports for the frame).
    private val releaseLock = Any()
    private val releasedFrames = object : LinkedHashMap<Long, Long>(64) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean =
            size > MAX_RELEASED_FRAMES
    }
    @Volatile
    private var lastReleasedFrameUs = -1L

    private val rpuSamples = AtomicLong()
    private val profile5Rpus = AtomicLong()
    private val unreadableRpus = AtomicLong()
    @Volatile
    private var otherProfile = -1
    private val lutsBuilt = AtomicLong()
    private val framesDrawn = AtomicLong()
    private val framesReleased = AtomicLong()
    private val framesMapped = AtomicLong()
    @Volatile
    private var outputHdr10: Boolean? = null

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

    fun onProfile5Track() = markDetected()

    fun onContainerProfile5() {
        containerSignalledProfile5 = true
        markDetected()
    }

    /** Records one sample's [DolbyVisionP5Native.ingestRpu] result. */
    fun onRpuResult(result: Long) {
        rpuSamples.incrementAndGet()
        when {
            result > 0L -> {
                profile5Rpus.incrementAndGet()
                markDetected()
            }
            result <= DolbyVisionP5Native.OTHER_PROFILE_BASE ->
                otherProfile = (DolbyVisionP5Native.OTHER_PROFILE_BASE - result).toInt()
            result != DolbyVisionP5Native.NOT_PARSED -> unreadableRpus.incrementAndGet()
        }
    }

    /** Records that the sample at [timeUs] carries mapping [hash] (0 = none: draw it as is). */
    fun onSampleMapping(timeUs: Long, hash: Long) {
        timeline[timeUs] = hash
        while (timeline.size > MAX_TIMELINE_ENTRIES) timeline.pollFirstEntry()
    }

    /**
     * The mapping of the sample shown at [mediaTimeUs]. A frame earlier than every recorded
     * sample takes the first mapping: a close match beats leaving it unmapped.
     */
    fun mappingAt(mediaTimeUs: Long): Long =
        (timeline.floorEntry(mediaTimeUs + MATCH_TOLERANCE_US) ?: timeline.ceilingEntry(mediaTimeUs))
            ?.value ?: 0L

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

    fun onOutputCreated(hdr10: Boolean) {
        outputHdr10 = hdr10
    }

    /** From the player's frame metadata listener: the frame at [mediaTimeUs] is released at [releaseTimeNs]. */
    fun onFrameReleased(mediaTimeUs: Long, releaseTimeNs: Long) {
        framesReleased.incrementAndGet()
        synchronized(releaseLock) { releasedFrames[releaseTimeNs] = mediaTimeUs }
        lastReleasedFrameUs = mediaTimeUs
    }

    /** The media time of the frame released at [releaseTimeNs], or of the latest one if unknown. */
    fun mediaTimeOfRelease(releaseTimeNs: Long): Long =
        synchronized(releaseLock) { releasedFrames[releaseTimeNs] } ?: lastReleasedFrameUs

    fun onFrameDrawn(mapped: Boolean) {
        framesDrawn.incrementAndGet()
        if (mapped) framesMapped.incrementAndGet()
    }

    /** What has happened so far, most basic problem first. */
    fun status(): Status = when {
        framesDrawn.get() == 0L -> Status.NoFrames
        outputHdr10 == false -> Status.OutputNotHdr10
        profile5Rpus.get() == 0L && otherProfile >= 0 -> Status.OtherProfile(otherProfile)
        profile5Rpus.get() == 0L && unreadableRpus.get() > 0L -> Status.RpuUnreadable
        profile5Rpus.get() == 0L -> Status.NoRpu
        lutsBuilt.get() == 0L -> Status.NoLut
        framesMapped.get() == 0L -> Status.NotMapped
        else -> Status.Mapping(lutsBuilt.get().toInt())
    }

    fun framesDrawnCount(): Long = framesDrawn.get()

    /** Frames the decoder released to the renderer. */
    fun framesReleasedCount(): Long = framesReleased.get()

    fun describeForLog(): String =
        "rpuSamples=${rpuSamples.get()} p5=${profile5Rpus.get()} unreadable=${unreadableRpus.get()} " +
            "otherProfile=$otherProfile luts=${lutsBuilt.get()} drawn=${framesDrawn.get()} " +
            "mapped=${framesMapped.get()} released=${framesReleased.get()} outputHdr10=$outputHdr10"

    fun release() {
        released = true
        onDetected = null
        worker?.interrupt()
        wanted.clear()
        timeline.clear()
        synchronized(lutLock) { luts.clear() }
    }

    private fun markDetected() {
        if (detected) return
        detected = true
        onDetected?.invoke()
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
        if (hash <= 0L || released) return null
        lutOrNull(hash)?.let { return it }
        val out = ShortArray(LUT_SIZE * LUT_SIZE * LUT_SIZE * 4)
        if (!DolbyVisionP5Native.buildLut(hash, LUT_SIZE, out)) {
            Log.w(TAG, "No mapping for hash ${java.lang.Long.toHexString(hash)}")
            return null
        }
        synchronized(lutLock) { luts[hash] = out }
        lutsBuilt.incrementAndGet()
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
        private const val MAX_RELEASED_FRAMES = 120
    }
}
