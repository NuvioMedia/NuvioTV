package com.nuvio.tv.reshaped.livetv

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The channel list a source last gave (M3U links and Xtream panels), saved small on the TV. Opening
 * Live TV again reads it in a moment instead of downloading and parsing the provider's list, and
 * a provider that is slow or down still shows its channels. It serves for the guide refresh
 * interval; after that the provider is asked again, and this copy only stands in when that fails.
 * Portals (Stalker) are not saved: their lists carry a session that is renewed on each load.
 */
internal object LiveTvListCache {
    private const val VERSION = 2
    private const val DIR = "live_tv_lists"

    /** What a provider gave, before the list tags the channels with their source. */
    class Entry(
        val channels: List<LiveTvChannel>,
        val epgUrls: List<String>,
        val groupOrder: List<String>,
        val savedAtMs: Long,
    )

    fun file(cacheDir: File, profileId: Int, sourceId: String): File =
        File(File(cacheDir, DIR), "list_${profileId}_${Integer.toHexString(sourceId.hashCode())}.bin.gz")

    /** Removes the saved lists of [profileId] whose source is no longer one of [sourceIds]. */
    fun keepOnly(cacheDir: File, profileId: Int, sourceIds: Collection<String>) {
        val kept = sourceIds.mapTo(HashSet()) { file(cacheDir, profileId, it).name }
        File(cacheDir, DIR).listFiles()?.forEach { saved ->
            // A list being written (a .tmp name) is left alone.
            if (saved.name.startsWith("list_${profileId}_") && saved.name.endsWith(".bin.gz") && saved.name !in kept) saved.delete()
        }
    }

    /**
     * Everything that makes the provider give another list: an edited link, login or user agent
     * no longer matches. Kept as a digest, so the password is not written out again.
     */
    private fun key(source: LiveTvSource): String {
        val text = listOf(
            source.type.name, source.url, source.xtream.serverUrl, source.xtream.username, source.xtream.password, source.userAgent,
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** The saved list of [source], or null when there is none or it was for another link or login. */
    fun read(file: File, source: LiveTvSource): Entry? = runCatching {
        if (!file.isFile) return null
        DataInputStream(GZIPInputStream(file.inputStream().buffered(64 * 1024), 64 * 1024).buffered(64 * 1024)).use { input ->
            // A list saved before guide shifts were kept (version 1) is read too, without them.
            val version = input.readInt()
            if (version !in 1..VERSION || input.readUTF() != key(source)) return null
            val savedAtMs = input.readLong()
            val epgUrls = List(input.readInt()) { input.readUTF() }
            val groupOrder = List(input.readInt()) { input.readUTF() }
            // Channels share header sets, catch-up kinds and category names, as when the list was read.
            val headerSets = List(input.readInt()) {
                val size = input.readInt()
                LinkedHashMap<String, String>(size * 2).apply { repeat(size) { put(input.readUTF(), input.readUTF()) } }
            }
            val catchups = List(input.readInt()) {
                LiveTvCatchup(LiveTvCatchup.Kind.valueOf(input.readUTF()), input.readInt(), input.readOptional())
            }
            val groups = HashMap<String, String>()
            val count = input.readInt()
            val channels = ArrayList<LiveTvChannel>(count)
            repeat(count) {
                channels += LiveTvChannel(
                    id = input.readUTF(),
                    name = input.readUTF(),
                    streamUrl = input.readUTF(),
                    tvgId = input.readOptional(),
                    logoUrl = input.readOptional(),
                    group = input.readUTF().let { groups.getOrPut(it) { it } },
                    headers = headerSets[input.readInt()],
                    tvgName = input.readOptional(),
                    catchup = input.readInt().let { if (it < 0) null else catchups[it] },
                    guideShiftMinutes = if (version >= 2) input.readInt() else 0,
                )
            }
            Entry(channels, epgUrls, groupOrder, savedAtMs)
        }
    }.getOrNull()

    /** Saves what [source] gave; a failed save only means the next opening asks the provider. */
    fun write(file: File, source: LiveTvSource, entry: Entry) {
        file.parentFile?.mkdirs()
        val temp = runCatching { File.createTempFile(file.name, ".tmp", file.parentFile) }.getOrNull() ?: return
        runCatching {
            val headerIndex = LinkedHashMap<Map<String, String>, Int>()
            val catchupIndex = LinkedHashMap<LiveTvCatchup, Int>()
            entry.channels.forEach { channel ->
                headerIndex.getOrPut(channel.headers) { headerIndex.size }
                channel.catchup?.let { catchupIndex.getOrPut(it) { catchupIndex.size } }
            }
            val gzip = object : GZIPOutputStream(temp.outputStream().buffered(64 * 1024), 64 * 1024) {
                init {
                    // Little CPU on a weak TV; the list still shrinks several times over.
                    def.setLevel(Deflater.BEST_SPEED)
                }
            }
            DataOutputStream(gzip.buffered(64 * 1024)).use { out ->
                out.writeInt(VERSION)
                out.writeUTF(key(source))
                out.writeLong(entry.savedAtMs)
                out.writeInt(entry.epgUrls.size)
                entry.epgUrls.forEach { out.writeUTF(it) }
                out.writeInt(entry.groupOrder.size)
                entry.groupOrder.forEach { out.writeUTF(it) }
                out.writeInt(headerIndex.size)
                headerIndex.keys.forEach { headers ->
                    out.writeInt(headers.size)
                    headers.forEach { (name, value) ->
                        out.writeUTF(name)
                        out.writeUTF(value)
                    }
                }
                out.writeInt(catchupIndex.size)
                catchupIndex.keys.forEach { catchup ->
                    out.writeUTF(catchup.kind.name)
                    out.writeInt(catchup.days)
                    out.writeOptional(catchup.template)
                }
                out.writeInt(entry.channels.size)
                entry.channels.forEach { channel ->
                    out.writeUTF(channel.id)
                    out.writeUTF(channel.name)
                    out.writeUTF(channel.streamUrl)
                    out.writeOptional(channel.tvgId)
                    out.writeOptional(channel.logoUrl)
                    out.writeUTF(channel.group)
                    out.writeInt(headerIndex.getValue(channel.headers))
                    out.writeOptional(channel.tvgName)
                    out.writeInt(channel.catchup?.let(catchupIndex::getValue) ?: -1)
                    out.writeInt(channel.guideShiftMinutes)
                }
            }
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        }.onFailure { temp.delete() }
    }

    fun delete(file: File) {
        file.delete()
    }

    private fun DataInputStream.readOptional(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeOptional(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }
}
