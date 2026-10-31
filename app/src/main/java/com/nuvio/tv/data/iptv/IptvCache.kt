package com.nuvio.tv.data.iptv

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal data class IptvCacheMeta(
    val playlistFetchedAt: Long = 0L,
    val playlistAttemptAt: Long = 0L,
    val epgFetchedAt: Long = 0L,
    val epgAttemptAt: Long = 0L,
    val headerEpgUrls: List<String> = emptyList()
)

internal object IptvCache {
    private const val CHANNELS_MAGIC = 0x4E564348
    private const val EPG_MAGIC = 0x4E564550
    private const val FORMAT_VERSION = 1
    private const val CHANNELS_FILE = "channels.bin"
    private const val EPG_FILE = "epg.bin"
    private const val META_FILE = "meta.json"

    fun readMeta(dir: File): IptvCacheMeta {
        val file = File(dir, META_FILE)
        if (!file.exists()) return IptvCacheMeta()
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optInt("version") != FORMAT_VERSION) return IptvCacheMeta()
            val urls = json.optJSONArray("headerEpgUrls")
            IptvCacheMeta(
                playlistFetchedAt = json.optLong("playlistFetchedAt"),
                playlistAttemptAt = json.optLong("playlistAttemptAt"),
                epgFetchedAt = json.optLong("epgFetchedAt"),
                epgAttemptAt = json.optLong("epgAttemptAt"),
                headerEpgUrls = if (urls == null) emptyList() else (0 until urls.length()).mapNotNull {
                    urls.optString(it).takeIf { value -> value.isNotBlank() }
                }
            )
        }.getOrDefault(IptvCacheMeta())
    }

    fun writeMeta(dir: File, meta: IptvCacheMeta) {
        val json = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("playlistFetchedAt", meta.playlistFetchedAt)
            .put("playlistAttemptAt", meta.playlistAttemptAt)
            .put("epgFetchedAt", meta.epgFetchedAt)
            .put("epgAttemptAt", meta.epgAttemptAt)
            .put("headerEpgUrls", JSONArray(meta.headerEpgUrls))
        writeAtomically(File(dir, META_FILE)) { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
    }

    fun writeChannels(dir: File, channels: List<IptvChannel>) {
        writeAtomically(File(dir, CHANNELS_FILE)) { stream ->
            val out = DataOutputStream(stream)
            out.writeInt(CHANNELS_MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeInt(channels.size)
            channels.forEach { channel ->
                out.writeText(channel.key)
                out.writeText(channel.playlistId)
                out.writeText(channel.name)
                out.writeInt(channel.number)
                out.writeText(channel.logo)
                out.writeText(channel.group)
                out.writeText(channel.tvgId)
                out.writeText(channel.tvgName)
                out.writeText(channel.streamUrl)
                out.writeInt(channel.headers.size)
                channel.headers.forEach { (name, value) ->
                    out.writeText(name)
                    out.writeText(value)
                }
                out.writeInt(channel.catchupDays)
            }
            out.flush()
        }
    }

    fun readChannels(dir: File): List<IptvChannel>? {
        val file = File(dir, CHANNELS_FILE)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { input ->
                if (input.readInt() != CHANNELS_MAGIC || input.readInt() != FORMAT_VERSION) return null
                val count = input.readInt()
                val interned = HashMap<String, String>()
                fun intern(value: String): String = interned.getOrPut(value) { value }
                val result = ArrayList<IptvChannel>(count)
                repeat(count) {
                    val key = input.readTextNonNull()
                    val playlistId = intern(input.readTextNonNull())
                    val name = input.readTextNonNull()
                    val number = input.readInt()
                    val logo = input.readText()
                    val group = intern(input.readTextNonNull())
                    val tvgId = input.readText()
                    val tvgName = input.readText()
                    val streamUrl = input.readTextNonNull()
                    val headerCount = input.readInt()
                    val headers = if (headerCount == 0) emptyMap() else LinkedHashMap<String, String>(headerCount).apply {
                        repeat(headerCount) { put(intern(input.readTextNonNull()), intern(input.readTextNonNull())) }
                    }
                    val catchupDays = input.readInt()
                    result.add(
                        IptvChannel(
                            key = key,
                            playlistId = playlistId,
                            name = name,
                            number = number,
                            logo = logo,
                            group = group,
                            tvgId = tvgId,
                            tvgName = tvgName,
                            streamUrl = streamUrl,
                            headers = headers,
                            catchupDays = catchupDays
                        )
                    )
                }
                result
            }
        }.getOrNull()
    }

    fun writeEpg(dir: File, epg: Map<String, List<IptvProgramme>>) {
        writeAtomically(File(dir, EPG_FILE)) { stream ->
            val out = DataOutputStream(stream)
            out.writeInt(EPG_MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeInt(epg.size)
            epg.forEach { (key, programmes) ->
                out.writeText(key)
                out.writeInt(programmes.size)
                programmes.forEach { programme ->
                    out.writeLong(programme.start)
                    out.writeLong(programme.end)
                    out.writeText(programme.title)
                    out.writeText(programme.subtitle)
                    out.writeText(programme.description)
                    out.writeText(programme.category)
                    out.writeText(programme.icon)
                }
            }
            out.flush()
        }
    }

    fun readEpg(dir: File, keepAfter: Long): Map<String, List<IptvProgramme>>? {
        val file = File(dir, EPG_FILE)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file), 256 * 1024)).use { input ->
                if (input.readInt() != EPG_MAGIC || input.readInt() != FORMAT_VERSION) return null
                val channelCount = input.readInt()
                val interned = HashMap<String, String>()
                fun intern(value: String?): String? = value?.let { interned.getOrPut(it) { it } }
                val result = HashMap<String, List<IptvProgramme>>(channelCount)
                repeat(channelCount) {
                    val key = input.readTextNonNull()
                    val count = input.readInt()
                    val list = ArrayList<IptvProgramme>(count)
                    repeat(count) {
                        val start = input.readLong()
                        val end = input.readLong()
                        val title = input.readTextNonNull()
                        val subtitle = input.readText()
                        val description = input.readText()
                        val category = input.readText()
                        val icon = input.readText()
                        if (end > keepAfter) {
                            list.add(
                                IptvProgramme(
                                    start = start,
                                    end = end,
                                    title = intern(title)!!,
                                    subtitle = subtitle,
                                    description = description,
                                    category = intern(category),
                                    icon = icon
                                )
                            )
                        }
                    }
                    if (list.isNotEmpty()) result[key] = list
                }
                result
            }
        }.getOrNull()
    }

    private fun DataOutputStream.writeText(value: String?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String? {
        val length = readInt()
        if (length < 0) return null
        if (length == 0) return ""
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.readTextNonNull(): String = readText().orEmpty()

    private inline fun writeAtomically(target: File, block: (BufferedOutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            BufferedOutputStream(FileOutputStream(temp), 256 * 1024).use { block(it) }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
