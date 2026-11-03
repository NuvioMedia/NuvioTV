package com.nuvio.tv.reshaped.livetv

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The programmes kept from the last full guide read, saved small beside the guides. Opening Live
 * TV again (after it was let go while unused, or after the app was closed) reads this in a moment
 * instead of going through the whole XMLTV file, which is often 100+ MB. It only serves while
 * the guides it came from are unchanged and none of its channels has run out of programmes.
 */
internal object LiveTvGuideCache {
    const val FILE_NAME = "guide_kept.bin.gz"
    // 5: playlist-scoped channel identities and complete guide imports. 6: no overlapping programmes.
    // 7: id matches rank over name matches. 8: a name match fills in for an id without programmes.
    // 9: a name match with the channel's own country tag wins.
    private const val VERSION = 10
    private const val MAX_TITLE = 1_000

    class Entry(val schedule: LiveTvSchedule, val logos: Map<String, String>, val nextReadAtMs: Long)

    /** What the saved programmes were read for: the guides, the channels and how much is kept. */
    fun key(epgUrls: List<String>, guideKeys: Set<String>, window: LiveTvGuideWindow, catchupKeys: Set<String> = emptySet()): Long {
        var channels = guideKeys.size.toLong()
        guideKeys.forEach { channels += it.hashCode() }
        catchupKeys.forEach { channels += 31L * it.hashCode() }
        var key = epgUrls.hashCode().toLong()
        key = key * 31 + channels
        key = key * 31 + window.pastMs
        key = key * 31 + window.aheadMs
        key = key * 31 + window.maxPast * 1_000 + window.maxAhead + window.maxShortAhead * 100_000L
        key = key * 31 + window.catchupPastMs + window.maxCatchupPast
        key = key * 31 + window.detailsMs + window.maxDescription * 1_000L + window.detailsPerChannel
        key = key * 31 + window.detailsBudgetChars + window.maxCatchupProgrammes
        return key
    }

    /** The saved programmes, or null when they are missing, stale or for other guides or channels. */
    fun read(file: File, key: Long, guideFiles: List<File>, nowMs: Long, maxGuideAgeMs: Long): Entry? = runCatching {
        if (!file.isFile) return null
        DataInputStream(GZIPInputStream(file.inputStream().buffered(), 16 * 1024)).use { input ->
            if (input.readInt() != VERSION || input.readLong() != key) return null
            val nextReadAtMs = input.readLong()
            if (nowMs >= nextReadAtMs) return null
            val files = input.readInt()
            if (files != guideFiles.size) return null
            guideFiles.forEach { guide ->
                val modified = input.readLong()
                // A guide downloaded since, or due to be downloaded again, means a full read.
                if (guide.lastModified() != modified || nowMs - modified !in 0 until maxGuideAgeMs) return null
            }
            val logos = HashMap<String, String>()
            repeat(input.readInt()) { logos[input.readUTF()] = input.readUTF() }
            // Titles and descriptions are saved once each and named by index: repeats share one
            // copy, as after a full read, and a week of catch-up reads no more text than a day.
            val texts = Array(input.readInt()) { input.readUTF() }
            fun DataInputStream.readShared(): String = texts[readInt()]
            val channels = input.readInt()
            val schedule = HashMap<String, List<LiveTvProgramme>>(channels * 2)
            repeat(channels) {
                val guideKey = input.readUTF()
                val count = input.readInt()
                schedule[guideKey] = List(count) {
                    LiveTvProgramme(
                        title = input.readShared(),
                        startEpochMs = input.readLong(),
                        stopEpochMs = input.readLong(),
                        description = if (input.readBoolean()) input.readShared() else null,
                        image = input.readOptional(),
                    )
                }
            }
            Entry(schedule, logos, nextReadAtMs)
        }
    }.getOrNull()

    /** Saves a full read's result; a failed save only means the next opening reads the XML again. */
    fun write(file: File, key: Long, guideFiles: List<File>, entry: Entry) {
        val temp = File(file.path + ".tmp")
        runCatching {
            DataOutputStream(GZIPOutputStream(temp.outputStream().buffered(), 16 * 1024)).use { out ->
                out.writeInt(VERSION)
                out.writeLong(key)
                out.writeLong(entry.nextReadAtMs)
                out.writeInt(guideFiles.size)
                guideFiles.forEach { out.writeLong(it.lastModified()) }
                out.writeInt(entry.logos.size)
                entry.logos.forEach { (guideKey, logo) ->
                    out.writeUTF(guideKey)
                    out.writeUTF(logo)
                }
                val texts = LinkedHashMap<String, Int>()
                entry.schedule.values.forEach { programmes ->
                    programmes.forEach { programme ->
                        texts.getOrPut(programme.title.take(MAX_TITLE)) { texts.size }
                        programme.description?.let { texts.getOrPut(it.take(MAX_TITLE)) { texts.size } }
                    }
                }
                out.writeInt(texts.size)
                texts.keys.forEach(out::writeUTF)
                out.writeInt(entry.schedule.size)
                entry.schedule.forEach { (guideKey, programmes) ->
                    out.writeUTF(guideKey)
                    out.writeInt(programmes.size)
                    programmes.forEach { programme ->
                        out.writeInt(texts.getValue(programme.title.take(MAX_TITLE)))
                        out.writeLong(programme.startEpochMs)
                        out.writeLong(programme.stopEpochMs)
                        out.writeBoolean(programme.description != null)
                        programme.description?.let { out.writeInt(texts.getValue(it.take(MAX_TITLE))) }
                        // A cut link would be a broken one.
                        out.writeOptional(programme.image?.takeIf { it.length <= MAX_TITLE })
                    }
                }
            }
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure {
            temp.delete()
            file.delete()
        }
    }

    private fun DataInputStream.readOptional(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeOptional(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }
}
