package com.nuvio.tv.core.player

import android.content.Context
import android.graphics.Typeface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SubtitleFont(val id: String, val name: String)

object SubtitleFonts {
    const val DEFAULT = "default"
    const val SERIF = "serif"
    const val MONO = "mono"
    const val MAX_BYTES = 10 * 1024 * 1024
    val builtIn = listOf(DEFAULT, SERIF, MONO)
    private val customId = Regex("custom-[a-f0-9-]{36}")

    fun isCustom(id: String): Boolean = customId.matches(id)
}

class SubtitleFontStore private constructor(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val typefaces = ConcurrentHashMap<String, Typeface>()
    private val mutableFonts = MutableStateFlow(readIndex())
    val fonts: StateFlow<List<SubtitleFont>> = mutableFonts.asStateFlow()

    fun cachedTypeface(id: String): Typeface? = when (id) {
        SubtitleFonts.SERIF -> Typeface.SERIF
        SubtitleFonts.MONO -> Typeface.MONOSPACE
        else -> typefaces[id]
    }

    fun typeface(id: String): Typeface? =
        cachedTypeface(id) ?: if (SubtitleFonts.isCustom(id)) load(id) else null

    fun fontFile(id: String): File? = if (SubtitleFonts.isCustom(id)) file(id).takeIf { it.isFile } else null

    @Synchronized
    fun import(fileName: String, bytes: ByteArray): SubtitleFont {
        require(isSupportedSubtitleFont(fileName, bytes))
        val current = mutableFonts.value
        current.firstOrNull { file(it.id).takeIf(File::isFile)?.readBytes()?.contentEquals(bytes) == true }
            ?.let { return it }
        val name = fileName.substringBeforeLast('.').trim().take(80).ifBlank { fileName }
        val font = SubtitleFont("custom-${UUID.randomUUID()}", name)
        write(file(font.id), bytes)
        try {
            requireNotNull(load(font.id))
            writeIndex(current + font)
        } catch (error: Exception) {
            typefaces.remove(font.id)
            file(font.id).delete()
            throw error
        }
        mutableFonts.value = current + font
        return font
    }

    @Synchronized
    fun remove(id: String) {
        val remaining = mutableFonts.value.filterNot { it.id == id }
        writeIndex(remaining)
        typefaces.remove(id)
        file(id).delete()
        mutableFonts.value = remaining
    }

    private fun load(id: String): Typeface? = file(id).takeIf { it.isFile }
        ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        ?.also { typefaces[id] = it }

    private fun readIndex(): List<SubtitleFont> = runCatching {
        json.decodeFromString<List<SubtitleFont>>(indexFile().readText())
            .filter { SubtitleFonts.isCustom(it.id) && file(it.id).isFile }
    }.getOrDefault(emptyList())

    private fun writeIndex(fonts: List<SubtitleFont>) {
        write(indexFile(), json.encodeToString(fonts).encodeToByteArray())
    }

    private fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.path + ".tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target))
    }

    private fun indexFile() = File(dir, "fonts.json")

    private fun file(id: String): File {
        require(SubtitleFonts.isCustom(id))
        return File(dir, id)
    }

    companion object {
        @Volatile
        private var instance: SubtitleFontStore? = null

        fun get(context: Context): SubtitleFontStore = instance ?: synchronized(this) {
            instance ?: SubtitleFontStore(File(context.applicationContext.filesDir, "subtitle-fonts"))
                .also { instance = it }
        }
    }
}

fun isSupportedSubtitleFont(fileName: String, bytes: ByteArray): Boolean {
    if (fileName.substringAfterLast('.', "").lowercase() !in setOf("ttf", "otf")) return false
    if (bytes.size !in 12..SubtitleFonts.MAX_BYTES) return false
    val signature = bytes.take(4).map { it.toInt() and 255 }
    if (signature != listOf(0, 1, 0, 0) && bytes.decodeToString(0, 4) != "OTTO") return false
    fun uint16(offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    fun uint32(offset: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[offset + i].toLong() and 255) }
    val tables = uint16(4)
    if (tables == 0 || 12L + tables * 16L > bytes.size) return false
    return (0 until tables).all { index ->
        val offset = 12 + index * 16
        val start = uint32(offset + 8)
        val length = uint32(offset + 12)
        start <= bytes.size && length <= bytes.size - start
    }
}
