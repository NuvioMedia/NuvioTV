package com.nuvio.tv.reshaped.livetv

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Up to a week of past programmes of catch-up channels, kept on disk so that weak TVs hold no more
 * of the guide in memory than the last day (see [LiveTvGuideWindow]). A guide read writes what it
 * meets to a [LiveTvArchiveSpill] as it goes; after a good read the archive takes them in, keeping
 * the days the new guide no longer lists. A channel's older days are read only when they are
 * looked at (the guide scrolled back, the programme list, the rewind bar), one channel at a time.
 *
 * The file: a header, then one block per channel (its titles once, then start, length and title of
 * each programme), then an index of where each channel's block is, and where that index starts.
 */
internal object LiveTvArchive {
    const val MAX_DAYS = 7
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MAGIC = 0x4E52_4741 // "NRGA"
    private const val VERSION = 1
    internal const val BUCKETS = 16
    /** A guide's past is not taken in when the TV is this short of space; the last archive stays. */
    private const val MIN_FREE_BYTES = 64L * 1024 * 1024
    private const val TAG = "LiveTvArchive"

    /** How far back a channel with [catchupDays] of catch-up is archived: its days, a week at most. */
    fun pastMsFor(catchupDays: Int?): Long = (catchupDays ?: 0).coerceIn(0, MAX_DAYS) * DAY_MS

    private class Index(val file: File, val modified: Long, val blocks: Map<String, LongArray>)

    private val lock = Any()
    @Volatile private var index: Index? = null

    /** Where each channel's block is in [file]; read once per version of the file. */
    private fun indexOf(file: File): Index? {
        val modified = file.lastModified()
        index?.let { if (it.file == file && it.modified == modified) return it }
        if (modified == 0L) return null
        return synchronized(lock) {
            index?.takeIf { it.file == file && it.modified == modified } ?: readIndex(file)?.also { index = it }
        }
    }

    private fun readIndex(file: File): Index? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 16 || raf.readInt() != MAGIC || raf.readInt() != VERSION) return null
            raf.seek(raf.length() - 8)
            val at = raf.readLong()
            if (at < 8 || at >= raf.length() - 8) return null
            raf.seek(at)
            val bytes = ByteArray((raf.length() - 8 - at).toInt())
            raf.readFully(bytes)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val count = input.readInt()
            val blocks = HashMap<String, LongArray>(count * 2)
            repeat(count) { blocks[input.readUTF()] = longArrayOf(input.readLong(), input.readInt().toLong()) }
            Index(file, file.lastModified(), blocks)
        }
    }.onFailure { Log.w(TAG, "Archive unreadable", it) }.getOrNull()

    /** [key]'s archived programmes, oldest first; empty when there are none. Blocking: call off the main thread. */
    fun read(file: File, key: String): List<LiveTvProgramme> {
        val at = indexOf(file)?.blocks?.get(key) ?: return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(at[0])
                val bytes = ByteArray(at[1].toInt())
                raf.readFully(bytes)
                readBlock(DataInputStream(ByteArrayInputStream(bytes)), HashMap())
            }
        }.onFailure { Log.w(TAG, "Archive block unreadable", it) }.getOrDefault(emptyList())
    }

    private fun readBlock(input: DataInputStream, shared: HashMap<String, String>): List<LiveTvProgramme> {
        val count = input.readInt()
        val titles = Array(input.readInt()) { input.readUTF().let { title -> shared.getOrPut(title) { title } } }
        return List(count) {
            val start = input.readLong()
            val stop = start + input.readInt() * 1000L
            LiveTvProgramme(title = titles[input.readInt()], startEpochMs = start, stopEpochMs = stop)
        }
    }

    private fun writeBlock(out: DataOutputStream, programmes: List<LiveTvProgramme>) {
        val titles = LinkedHashMap<String, Int>()
        programmes.forEach { titles.getOrPut(it.title.take(MAX_TITLE)) { titles.size } }
        out.writeInt(programmes.size)
        out.writeInt(titles.size)
        titles.keys.forEach(out::writeUTF)
        programmes.forEach { programme ->
            out.writeLong(programme.startEpochMs)
            out.writeInt(((programme.stopEpochMs - programme.startEpochMs) / 1000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
            out.writeInt(titles.getValue(programme.title.take(MAX_TITLE)))
        }
    }

    private const val MAX_TITLE = 1_000

    /**
     * Takes in a good guide read: for each catch-up channel ([days]), the programmes the chosen
     * guide's spill ([chosen]) has, plus the archived ones that ended before its first, within the
     * channel's days. Channels the read had nothing for keep what was archived; channels without
     * catch-up now are dropped. Works one bucket of channels at a time, so memory stays small.
     * Returns whether the archive was replaced; on any failure the last one stays as it was.
     */
    fun write(file: File, chosen: Map<String, LiveTvArchiveSpill>, days: Map<String, Int>, nowMs: Long): Boolean {
        val dir = file.absoluteFile.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        if (dir.usableSpace < MIN_FREE_BYTES) {
            Log.w(TAG, "Not enough space to keep past programmes; the last archive stays")
            return false
        }
        val old = indexOf(file)
        val temp = File(file.path + ".tmp")
        val written = try {
            (if (old != null) RandomAccessFile(file, "r") else null).use { source ->
                fun oldBlock(key: String, shared: HashMap<String, String>): List<LiveTvProgramme> {
                    val at = old?.blocks?.get(key) ?: return emptyList()
                    source ?: return emptyList()
                    source.seek(at[0])
                    val bytes = ByteArray(at[1].toInt())
                    source.readFully(bytes)
                    return readBlock(DataInputStream(ByteArrayInputStream(bytes)), shared)
                }
                DataOutputStream(BufferedOutputStream(FileOutputStream(temp), 64 * 1024)).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(VERSION)
                    val blocks = LinkedHashMap<String, LongArray>()
                    fun put(key: String, programmes: List<LiveTvProgramme>) {
                        if (programmes.isEmpty()) return
                        val at = out.size().toLong()
                        writeBlock(out, programmes)
                        blocks[key] = longArrayOf(at, out.size() - at)
                    }
                    val spills = chosen.values.distinct()
                    for (bucket in 0 until BUCKETS) {
                        val fresh = HashMap<String, ArrayList<LiveTvProgramme>>()
                        val shared = HashMap<String, String>()
                        for (spill in spills) {
                            spill.readBucket(bucket) { key, generation, start, stop, title ->
                                if (chosen[key] !== spill || generation != spill.generation(key) || key !in days) return@readBucket
                                fresh.getOrPut(key) { ArrayList() } +=
                                    LiveTvProgramme(title = shared.getOrPut(title) { title }, startEpochMs = start, stopEpochMs = stop)
                            }
                        }
                        fresh.forEach { (key, list) ->
                            val cutoff = nowMs - pastMsFor(days[key])
                            list.sortBy { it.startEpochMs }
                            val taken = liveTvWithoutOverlaps(list).filter { it.stopEpochMs > cutoff && it.stopEpochMs <= nowMs }
                            val firstStart = taken.firstOrNull()?.startEpochMs ?: Long.MAX_VALUE
                            // Days the new guide no longer lists stay, up to the channel's catch-up.
                            val kept = oldBlock(key, shared).filter { it.stopEpochMs > cutoff && it.stopEpochMs <= firstStart }
                            put(key, kept + taken)
                        }
                        old?.blocks?.keys?.forEach { key ->
                            if (key in fresh || key in blocks || Math.floorMod(key.hashCode(), BUCKETS) != bucket) return@forEach
                            val catchupDays = days[key] ?: return@forEach
                            val cutoff = nowMs - pastMsFor(catchupDays)
                            put(key, oldBlock(key, shared).filter { it.stopEpochMs > cutoff })
                        }
                    }
                    val indexAt = out.size().toLong()
                    out.writeInt(blocks.size)
                    blocks.forEach { (key, at) ->
                        out.writeUTF(key)
                        out.writeLong(at[0])
                        out.writeInt(at[1].toInt())
                    }
                    out.writeLong(indexAt)
                }
            }
            true
        } catch (error: IOException) {
            Log.w(TAG, "Could not write the archive; the last one stays", error)
            false
        }
        if (!written) {
            temp.delete()
            return false
        }
        synchronized(lock) {
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) {
                    temp.delete()
                    return false
                }
            }
            index = null
        }
        return true
    }

    /** Spills of one guide refresh, each in its own folder under [root]; [clear] removes them all. */
    class Spills(private val root: File) {
        private var next = 0

        @Synchronized
        fun create(): LiveTvArchiveSpill? {
            val dir = File(root, (next++).toString())
            dir.deleteRecursively()
            return if (dir.mkdirs()) LiveTvArchiveSpill(dir) else null
        }

        fun clear() {
            root.deleteRecursively()
        }
    }
}

/**
 * The past programmes of catch-up channels one guide read meets, written to disk as they come:
 * a file per bucket of channels, so the archive can take them in a bucket at a time. Holds no
 * programmes in memory. A channel whose programmes came from a weaker match that a better one
 * replaced ([drop]) moves to a new generation, and the archive takes the last generation only.
 */
internal class LiveTvArchiveSpill(private val dir: File) {
    private val keys = HashMap<String, Int>()
    private val keyNames = ArrayList<String>()
    private val generations = HashMap<String, Int>()
    private val outputs = arrayOfNulls<DataOutputStream>(LiveTvArchive.BUCKETS)
    /** A spill that failed to write (disk full) is not taken in. */
    var failed = false
        private set
    private var finished = false

    fun write(key: String, startEpochMs: Long, stopEpochMs: Long, title: String) {
        if (failed || finished) return
        try {
            val id = keys.getOrPut(key) { keyNames.add(key); keyNames.size - 1 }
            val bucket = Math.floorMod(key.hashCode(), LiveTvArchive.BUCKETS)
            val out = outputs[bucket] ?: DataOutputStream(BufferedOutputStream(FileOutputStream(File(dir, bucket.toString())), 8 * 1024)).also { outputs[bucket] = it }
            out.writeInt(id)
            out.writeInt(generations[key] ?: 0)
            out.writeLong(startEpochMs)
            out.writeLong(stopEpochMs)
            out.writeUTF(if (title.length > 1_000) title.take(1_000) else title)
        } catch (error: IOException) {
            failed = true
            close()
        }
    }

    /** What [key] had so far came from a match a better one replaced. */
    fun drop(key: String) {
        generations[key] = (generations[key] ?: 0) + 1
    }

    fun generation(key: String): Int = generations[key] ?: 0

    /** The read is over: nothing more is written. */
    fun finish() {
        finished = true
        close()
    }

    private fun close() {
        outputs.forEachIndexed { index, out ->
            runCatching { out?.close() }.onFailure { failed = true }
            outputs[index] = null
        }
    }

    fun readBucket(bucket: Int, each: (key: String, generation: Int, start: Long, stop: Long, title: String) -> Unit) {
        if (failed) return
        val file = File(dir, bucket.toString())
        if (!file.isFile) return
        DataInputStream(BufferedInputStream(FileInputStream(file), 32 * 1024)).use { input ->
            while (true) {
                val id = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                val generation = input.readInt()
                val start = input.readLong()
                val stop = input.readLong()
                val title = input.readUTF()
                each(keyNames[id], generation, start, stop, title)
            }
        }
    }
}
