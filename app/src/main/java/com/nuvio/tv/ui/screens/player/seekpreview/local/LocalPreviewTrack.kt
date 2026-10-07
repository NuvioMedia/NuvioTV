@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player.seekpreview.local

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewCue
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewThumbnail
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewTrack
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seek-preview thumbnails generated on the device from the stream that is playing, one per
 * [SLOT_MS] slot, filled only from the buffer tap ([onKeyframe]): keyframes playback downloads
 * anyway, so it costs no extra requests. Reading keyframes over separate connections was tried
 * and dropped: debrid CDNs rate limit the burst of range requests it makes.
 *
 * Lookups return the nearest filled slot marked approximate (a low-resolution copy that reads as
 * blurred, or replaced by a Seekr frame when one is loaded). Thumbnails are small JPEGs kept in
 * memory and in a disk cache per title/release, so a rewatch starts with every part watched
 * before. Decoding runs on one background thread behind a short queue that drops keyframes when
 * it falls behind, so a slow box never stalls playback's loader.
 *
 * Keyframes are not decoded while the video plays: a software decode of a large keyframe takes
 * every core for a moment and playback drops frames (a judder every 10-20 s). They are kept
 * compressed in a spool file instead and decoded while paused or scrubbing, the ones nearest
 * the scrub position first.
 */
internal class LocalPreviewTrack(
    context: Context,
    private val cacheKey: String,
    private val durationMs: Long,
    /** The playing file's keyframe near a time, from its index (see [LocalPreviewSource.keyframeNear]). */
    private val keyframeIndex: (positionMs: Long, toleranceMs: Long) -> Long? = { _, _ -> null },
) : SeekPreviewTrack {
    /**
     * Largest picture decoded for previews. Boxes with under 3 GB of RAM skip streams above
     * 1080p: a software decoder's 4K buffers next to 4K playback risk the low-memory killer.
     */
    private val maxPixels: Long = run {
        val memory = ActivityManager.MemoryInfo()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.getMemoryInfo(memory)
        val lowMemory = manager == null || manager.isLowRamDevice || memory.totalMem < LOW_MEMORY_BYTES
        if (lowMemory) FULL_HD_PIXELS else Long.MAX_VALUE
    }

    /** Set when the stream cannot be previewed on this device; stops copying its keyframes. */
    @Volatile private var unsupported = false

    override val isLocal: Boolean get() = true

    @Volatile
    override var offsetMs: Long = 0L

    private val slotCount = ((durationMs + SLOT_MS - 1) / SLOT_MS).toInt().coerceAtLeast(1)
    private val lock = Any()
    private val jpegs = arrayOfNulls<ByteArray>(slotCount)
    private val frameMs = LongArray(slotCount) { -1L }
    private val slotState = ByteArray(slotCount)
    private var filledCount = 0
    /**
     * Slots holding a nearly black frame (a fade or a cut to black). Such a frame stays on show
     * but its slot stays open, so a brighter keyframe of the same slot replaces it. Guarded by [lock].
     */
    private val darkSlots = BooleanArray(slotCount)
    private var darkCount = 0
    /** Keyframe time (ms) → slot holding its thumbnail, so a keyframe is never fetched twice. */
    private val keyframeSlots = HashMap<Long, Int>()
    private val decoded = object : LinkedHashMap<Int, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) = size > MAX_DECODED
    }
    private val blurred = object : LinkedHashMap<Int, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?) = size > MAX_DECODED
    }
    @Volatile private var closed = false

    private val sinceSave = AtomicInteger()

    private val decoder = KeyframeThumbnailDecoder()
    private val tapExecutor = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(TAP_QUEUE),
        { runnable -> Thread(runnable, "NuvioPreviewTap").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = Channel<Unit>(Channel.CONFLATED)

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    private val cacheFile: File = File(File(context.applicationContext.cacheDir, CACHE_DIR), sha1(cacheKey) + ".bin")

    /**
     * False while the viewer has paused or is scrubbing (set by the owner from the player's
     * state); keyframes are only decoded then.
     */
    @Volatile var playbackActive = true
        set(value) {
            field = value
            if (!value) scheduleDrain()
        }

    /**
     * The spool had no room for the last keyframe. While playing, copying the stream's video
     * for keyframes that would only be dropped is skipped until a pause drains the spool.
     */
    @Volatile private var spoolFull = false

    /** A keyframe copied while playing, waiting in the spool to be decoded. */
    private class SpooledKeyframe(val format: Format, val timeUs: Long, val position: Long, val size: Int)

    /** A keyframe waiting to see whether the next one is nearer its slot's time. */
    private class Candidate(val slot: Int, val format: Format, val timeUs: Long, val bytes: ByteArray) {
        val keyMs: Long get() = timeUs / 1_000L
        val slotMs: Long get() = slot * SLOT_MS
    }

    /** The keyframe before its slot's time that is still in the running. Guarded by [lock]. */
    private var candidate: Candidate? = null

    /** Slot → its spooled keyframe; the slot stays CLAIMED meanwhile. Guarded by [lock]. */
    private val spooled = HashMap<Int, SpooledKeyframe>()
    private val spoolFile = File(
        File(context.applicationContext.cacheDir, CACHE_DIR),
        sha1(cacheKey) + "-" + SystemClock.elapsedRealtimeNanos() + SPOOL_SUFFIX,
    )
    // Spool file state: only touched on the tap thread.
    private var spool: RandomAccessFile? = null
    private var spoolEnd = 0L
    private var spoolLimit = SPOOL_LIMIT_BYTES
    private val drainScheduled = AtomicBoolean(false)
    /** Slot the viewer last looked at, so its keyframes are decoded first. */
    @Volatile private var focusSlot = -1

    /**
     * +1 while the viewer scrubs forward, -1 backward, 0 otherwise (set by the owner from the
     * scrub position): spooled frames in that direction are decoded first.
     */
    @Volatile var scrubDirection = 0

    fun start() {
        // Coalesce UI updates: at most a few revisions per second however fast frames land.
        scope.launch {
            for (unit in changes) {
                _revision.value = _revision.value + 1
                delay(UI_UPDATE_INTERVAL_MS)
            }
        }
        scope.launch(Dispatchers.IO) {
            deleteStaleSpools()
            loadCache()
            notifyChanged()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) { candidate = null }
        tapExecutor.shutdownNow()
        Thread({
            runCatching { tapExecutor.awaitTermination(3, TimeUnit.SECONDS) }
            decoder.release()
            runCatching { spool?.close() }
            spool = null
            spoolFile.delete()
            saveCache()
            scope.cancel()
        }, "NuvioPreviewClose").apply { isDaemon = true }.start()
    }

    // ---- Lookups -------------------------------------------------------------------------

    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? = lookup(positionMs, focus = true)

    override suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? = lookup(positionMs, focus = false)

    private suspend fun lookup(positionMs: Long, focus: Boolean): SeekPreviewThumbnail? {
        val corrected = (positionMs + offsetMs).coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
        val slot = (corrected / SLOT_MS).toInt().coerceIn(0, slotCount - 1)
        if (focus) {
            focusSlot = slot
            if (synchronized(lock) { spooled.isNotEmpty() }) scheduleDrain()
        }
        val exact = synchronized(lock) { exactFrameFor(corrected) }
        val found = exact ?: synchronized(lock) { nearestFilled(slot) } ?: return null
        val approximate = exact == null
        val bitmap = bitmapFor(found, small = approximate) ?: return null
        val cue = if (exact != null) synchronized(lock) { frameCue(exact) } else gridCue(slot)
        return SeekPreviewThumbnail(
            bitmap = bitmap,
            cueStartMs = cue.startMs,
            cueEndMs = cue.endMs,
            approximate = approximate,
        )
    }

    override fun keyframeNear(positionMs: Long, toleranceMs: Long): Long? = keyframeIndex(positionMs, toleranceMs)

    /**
     * The filled slot whose frame is the latest at or before [positionMs], or null when that
     * frame does not reach [positionMs] (see [frameCue]). Guarded by [lock].
     *
     * A frame is filed under the slot its keyframe is nearest to, so it lies within half a slot
     * of the slot's time and frame times rise with the slot index: the latest one at or before
     * [positionMs] is in the slot after [positionMs]'s own, that slot, or the one before.
     */
    private fun exactFrameFor(positionMs: Long): Int? {
        val base = (positionMs / SLOT_MS).toInt()
        for (slot in minOf(base + 1, slotCount - 1) downTo maxOf(base - 1, 0)) {
            if (jpegs[slot] == null || frameMs[slot] > positionMs) continue
            return slot.takeIf { positionMs < frameCue(it).endMs }
        }
        return null
    }

    /**
     * The window a filled slot's frame stands for: from the keyframe's own time to the next
     * slot's frame, or one slot long when the next slot has none. Cue starts are real frame
     * times, so grid-locked scrubbing parks the seek on the very frame shown. Guarded by [lock].
     */
    private fun frameCue(slot: Int): SeekPreviewCue {
        val startMs = frameMs[slot]
        val next = slot + 1
        val endMs = if (next < slotCount && jpegs[next] != null) frameMs[next] else startMs + SLOT_MS
        return SeekPreviewCue(startMs, endMs.coerceAtLeast(startMs + 1))
    }

    /** A stand-in's window: its slot on the fixed grid, since its frame is from elsewhere. */
    private fun gridCue(slot: Int): SeekPreviewCue {
        val startMs = slot * SLOT_MS
        return SeekPreviewCue(startMs, minOf(startMs + SLOT_MS, durationMs).coerceAtLeast(startMs + 1))
    }

    private fun nearestFilled(slot: Int): Int? {
        if (jpegs[slot] != null) return slot
        for (distance in 1 until slotCount) {
            val before = slot - distance
            val after = slot + distance
            if (before < 0 && after >= slotCount) break
            if (before >= 0 && jpegs[before] != null) return before
            if (after < slotCount && jpegs[after] != null) return after
        }
        return null
    }

    /**
     * The slot's thumbnail, decoded off the main thread as RGB_565 (half the memory). [small]
     * decodes it at a quarter of its width instead: drawn at preview size it looks blurred,
     * which marks a stand-in without a blur shader (API 31+ only, and costly on TV boxes).
     */
    private suspend fun bitmapFor(slot: Int, small: Boolean): Bitmap? {
        val cache = if (small) blurred else decoded
        synchronized(lock) { cache[slot] }?.let { return it }
        val bytes = synchronized(lock) { jpegs[slot] } ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            runCatching {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = if (small) STAND_IN_SAMPLE_SIZE else 1
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }.getOrNull()
        } ?: return null
        synchronized(lock) { cache[slot] = bitmap }
        return bitmap
    }

    // ---- Buffer tap ----------------------------------------------------------------------

    fun wantsKeyframes(): Boolean =
        !closed && !unsupported && !decoder.gaveUp && !(spoolFull && playbackActive) &&
            synchronized(lock) { filledCount < slotCount || darkCount > 0 }

    fun wantsKeyframe(timeUs: Long): Boolean {
        if (closed) return false
        val keyMs = timeUs / 1_000L
        val slot = slotFor(keyMs) ?: return false
        return synchronized(lock) {
            // Any keyframe settles a waiting candidate, so it is wanted while one waits.
            candidate != null || (isOpen(slot) && keyMs !in keyframeSlots)
        }
    }

    /**
     * Whether a keyframe from just before to [KEYFRAME_LOOKAHEAD_MS] after [timeUs] could still be
     * wanted ([wantsKeyframe]). False lets the tap skip copying the video until the next slot that
     * still needs a frame comes within reach. Only ever false where [wantsKeyframe] would be too.
     */
    fun mayWantKeyframesNear(timeUs: Long): Boolean {
        if (closed) return false
        val fromMs = timeUs / 1_000L - KEYFRAME_LOOKBEHIND_MS
        val toMs = timeUs / 1_000L + KEYFRAME_LOOKAHEAD_MS
        if (toMs < 0L || fromMs > durationMs + SLOT_MS) return false
        val first = ((fromMs.coerceAtLeast(0L) + SLOT_MS / 2) / SLOT_MS).toInt().coerceIn(0, slotCount - 1)
        val last = ((toMs.coerceAtLeast(0L) + SLOT_MS / 2) / SLOT_MS).toInt().coerceIn(0, slotCount - 1)
        return synchronized(lock) {
            candidate != null || (first..last).any { isOpen(it) }
        }
    }

    /**
     * A keyframe playback downloaded. Each slot keeps the keyframe nearest its time rather than
     * the first one that arrives: keyframes come in playback order, so one before the slot's
     * time waits as the candidate until the next keyframe shows whether it is closer. One at or
     * after the slot's time is final, since later ones only get farther. A candidate is never
     * decoded before it is chosen, so this costs one copy of compressed bytes, not a decode.
     */
    fun onKeyframe(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
        if (format.width.toLong() * format.height.toLong() > maxPixels) {
            unsupported = true
            return
        }
        val keyMs = timeUs / 1_000L
        val slot = slotFor(keyMs) ?: return
        val wanted = synchronized(lock) { isOpen(slot) && keyMs !in keyframeSlots }
        val incoming = if (wanted) Candidate(slot, format, timeUs, data.copyOfRange(offset, offset + size)) else null
        val settled = ArrayList<Candidate>(2)
        synchronized(lock) {
            val waiting = candidate
            candidate = null
            var next = incoming
            if (waiting != null) {
                if (next == null || next.slot != waiting.slot) {
                    settled += waiting
                } else if (abs(waiting.keyMs - waiting.slotMs) < abs(next.keyMs - next.slotMs)) {
                    // The new keyframe is past the slot's time and farther than the waiting one.
                    settled += waiting
                    next = null
                }
            }
            if (next != null) {
                if (next.keyMs >= next.slotMs) settled += next else candidate = next
            }
        }
        for (chosen in settled) submit(chosen)
    }

    /** Decodes (or spools) a chosen keyframe into its slot. */
    private fun submit(chosen: Candidate) {
        // Decoding runs behind playback's loader thread; when it falls behind, skip frames.
        if (tapExecutor.queue.remainingCapacity() == 0) return
        val slot = chosen.slot
        if (!claim(slot)) return
        val submitted = runCatching {
            tapExecutor.execute {
                if (mayDecodeNow()) {
                    decodeInto(slot, chosen.format, chosen.bytes, chosen.timeUs)
                } else if (!spoolKeyframe(slot, chosen.format, chosen.timeUs, chosen.bytes)) {
                    release(slot)
                }
                if (mayDecodeNow()) scheduleDrain()
            }
        }.isSuccess
        if (!submitted) release(slot)
    }

    /** Tap thread. */
    private fun decodeInto(slot: Int, format: Format, bytes: ByteArray, timeUs: Long) {
        val frame = runCatching {
            decoder.decode(MediaFormatUtil.createMediaFormatFromFormat(format), bytes, 0, bytes.size, timeUs)
        }.getOrNull()
        if (frame != null) {
            store(slot, frame.jpeg, timeUs / 1_000L, frame.dark)
        } else {
            release(slot)
        }
    }

    /** Paused, or scrubbing: the viewer is not watching frames go by. */
    private fun mayDecodeNow(): Boolean = !playbackActive

    // ---- Spool (tap thread only) -------------------------------------------------------

    /** Keeps [bytes] compressed until it may be decoded; false when the spool is full or unwritable. */
    private fun spoolKeyframe(slot: Int, format: Format, timeUs: Long, bytes: ByteArray): Boolean {
        if (spoolEnd + bytes.size > spoolLimit) {
            spoolFull = true
            return false
        }
        return runCatching {
            val file = spool ?: run {
                val dir = spoolFile.parentFile
                dir?.mkdirs()
                // TV boxes have little storage: never take more than a quarter of what is free.
                val free = runCatching { dir?.usableSpace ?: 0L }.getOrDefault(0L)
                spoolLimit = minOf(SPOOL_LIMIT_BYTES, free / 4)
                if (spoolEnd + bytes.size > spoolLimit) {
                    spoolFull = true
                    return false
                }
                RandomAccessFile(spoolFile, "rw").also { it.setLength(0L); spool = it }
            }
            file.seek(spoolEnd)
            file.write(bytes)
            synchronized(lock) { spooled[slot] = SpooledKeyframe(format, timeUs, spoolEnd, bytes.size) }
            spoolEnd += bytes.size
        }.onFailure { Log.w(TAG, "spool not written: ${it.message}") }.isSuccess
    }

    private fun scheduleDrain() {
        if (closed || !drainScheduled.compareAndSet(false, true)) return
        val submitted = runCatching { tapExecutor.execute(::drainOne) }.isSuccess
        if (!submitted) drainScheduled.set(false)
    }

    /** Decodes one spooled keyframe, nearest the scrub position first, then schedules the next. */
    private fun drainOne() {
        drainScheduled.set(false)
        if (closed || !mayDecodeNow()) return
        val next = synchronized(lock) {
            val focus = focusSlot
            val slot = if (focus >= 0) spooled.keys.minByOrNull { drainCost(it, focus) } else spooled.keys.minOrNull()
            slot?.let { it to spooled.remove(it)!! }
        }
        if (next == null) {
            // Everything decoded: start the spool over so it never grows past one pause's worth.
            if (spoolEnd > 0L) {
                spoolEnd = 0L
                runCatching { spool?.setLength(0L) }
            }
            spoolFull = false
            return
        }
        val (slot, entry) = next
        val bytes = runCatching {
            ByteArray(entry.size).also { buffer ->
                val file = spool ?: error("no spool")
                file.seek(entry.position)
                file.readFully(buffer)
            }
        }.getOrNull()
        if (bytes != null) decodeInto(slot, entry.format, bytes, entry.timeUs) else release(slot)
        scheduleDrain()
    }

    /**
     * Order of decoding around the scrub position: the slot itself, then the ones in the direction
     * of the scrub before those behind it, so the next steps already have their frames.
     */
    private fun drainCost(slot: Int, focus: Int): Int {
        val distance = abs(slot - focus)
        val direction = scrubDirection
        val behind = direction != 0 && (slot - focus) * direction < 0
        return if (behind) distance * 2 else distance * 2 - 1
    }

    private fun deleteStaleSpools() {
        val now = System.currentTimeMillis()
        spoolFile.parentFile?.listFiles { file ->
            file.name.endsWith(SPOOL_SUFFIX) && file != spoolFile && now - file.lastModified() > STALE_SPOOL_MS
        }?.forEach { it.delete() }
    }

    /** Nearest slot to a keyframe, or null when the keyframe is closer to no slot start. */
    private fun slotFor(keyMs: Long): Int? {
        if (keyMs < 0 || keyMs > durationMs + SLOT_MS) return null
        return ((keyMs + SLOT_MS / 2) / SLOT_MS).toInt().coerceIn(0, slotCount - 1)
    }

    // ---- Slot bookkeeping ----------------------------------------------------------------

    /** No frame yet, or only a dark one that a brighter keyframe may replace. Guarded by [lock]. */
    private fun isOpen(slot: Int): Boolean =
        slotState[slot] == EMPTY || (slotState[slot] == FILLED && darkSlots[slot])

    private fun claim(slot: Int): Boolean = synchronized(lock) {
        if (!isOpen(slot)) return@synchronized false
        if (slotState[slot] == FILLED) {
            // Replacing a dark frame: it stays on show until the new one is stored.
            filledCount--
            setDark(slot, false)
        }
        slotState[slot] = CLAIMED
        true
    }

    /** Guarded by [lock]. */
    private fun setDark(slot: Int, dark: Boolean) {
        if (darkSlots[slot] == dark) return
        darkSlots[slot] = dark
        darkCount += if (dark) 1 else -1
    }

    private fun release(slot: Int) {
        synchronized(lock) {
            if (slotState[slot] != CLAIMED) return
            if (jpegs[slot] != null) {
                // The dark frame a replacement was tried for stays, still replaceable.
                filledCount++
                slotState[slot] = FILLED
                setDark(slot, true)
            } else {
                slotState[slot] = EMPTY
            }
        }
    }

    private fun store(slot: Int, jpeg: ByteArray, keyMs: Long, dark: Boolean) {
        synchronized(lock) {
            if (slotState[slot] != FILLED) filledCount++
            slotState[slot] = FILLED
            setDark(slot, dark)
            jpegs[slot] = jpeg
            frameMs[slot] = keyMs
            keyframeSlots.putIfAbsent(keyMs, slot)
            decoded.remove(slot)
            blurred.remove(slot)
        }
        if (sinceSave.incrementAndGet() >= SAVE_EVERY) {
            sinceSave.set(0)
            scope.launch(Dispatchers.IO) { saveCache() }
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        changes.trySend(Unit)
    }

    // ---- Disk cache ----------------------------------------------------------------------

    private fun loadCache() {
        if (!cacheFile.exists()) return
        runCatching {
            DataInputStream(cacheFile.inputStream().buffered()).use { input ->
                val magic = input.readInt()
                if ((magic != CACHE_MAGIC && magic != CACHE_MAGIC_V1) || input.readInt().toLong() != SLOT_MS) return
                val count = input.readInt()
                repeat(count) {
                    val slot = input.readInt()
                    val keyMs = input.readLong()
                    val bytes = ByteArray(input.readInt())
                    input.readFully(bytes)
                    // Caches from before dark frames were marked count every frame as bright.
                    val dark = magic == CACHE_MAGIC && input.readBoolean()
                    if (slot in 0 until slotCount && claim(slot)) {
                        synchronized(lock) {
                            filledCount++
                            slotState[slot] = FILLED
                            setDark(slot, dark)
                            jpegs[slot] = bytes
                            frameMs[slot] = keyMs
                            keyframeSlots.putIfAbsent(keyMs, slot)
                        }
                    }
                }
            }
            cacheFile.setLastModified(System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "cache unreadable: ${it.message}") }
    }

    @Synchronized
    private fun saveCache() {
        val entries = synchronized(lock) {
            (0 until slotCount).mapNotNull { slot ->
                val bytes = jpegs[slot]
                if (slotState[slot] == FILLED && bytes != null) CachedFrame(slot, frameMs[slot], bytes, darkSlots[slot]) else null
            }
        }
        if (entries.isEmpty()) return
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.path + ".tmp")
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeInt(SLOT_MS.toInt())
                output.writeInt(entries.size)
                for ((slot, keyMs, bytes, dark) in entries) {
                    output.writeInt(slot)
                    output.writeLong(keyMs)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.writeBoolean(dark)
                }
            }
            temp.renameTo(cacheFile)
            pruneCache(cacheFile.parentFile)
        }.onFailure { Log.w(TAG, "cache not saved: ${it.message}") }
    }

    private fun pruneCache(dir: File?) {
        val files = dir?.listFiles { file -> file.name.endsWith(".bin") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > CACHE_LIMIT_BYTES && file != cacheFile) file.delete()
        }
    }

    companion object {
        private const val TAG = "NuvioLocalPreviews"
        const val SLOT_MS = 10_000L
        /**
         * How far past the latest sample time the next keyframe is looked for. In decode order a
         * keyframe's time is later than every sample before it, by at most the B-frame reorder span
         * (well under a second); the margins leave room for that and for timestamps' jitter.
         */
        private const val KEYFRAME_LOOKAHEAD_MS = 3_000L
        private const val KEYFRAME_LOOKBEHIND_MS = 1_000L
        private const val MAX_DECODED = 48
        private const val STAND_IN_SAMPLE_SIZE = 4
        private const val LOW_MEMORY_BYTES = 3L * 1024L * 1024L * 1024L
        private const val FULL_HD_PIXELS = 1920L * 1088L
        private const val TAP_QUEUE = 3
        private const val UI_UPDATE_INTERVAL_MS = 250L
        private const val SAVE_EVERY = 60
        private const val CACHE_DIR = "seek_previews"
        private const val CACHE_MAGIC = 0x4E535032 // "NSP2": each frame ends with its dark flag
        private const val CACHE_MAGIC_V1 = 0x4E535031 // "NSP1"
        private const val CACHE_LIMIT_BYTES = 200L * 1_000_000L
        private const val SPOOL_SUFFIX = ".spool"
        /** About an hour of 1080p keyframes. */
        private const val SPOOL_LIMIT_BYTES = 96L * 1024 * 1024
        /** A spool left by a crash; a live one is never this old without being written. */
        private const val STALE_SPOOL_MS = 12L * 60 * 60 * 1000

        private const val EMPTY: Byte = 0
        private const val CLAIMED: Byte = 1
        private const val FILLED: Byte = 2

        private fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private data class CachedFrame(val slot: Int, val keyMs: Long, val bytes: ByteArray, val dark: Boolean)
