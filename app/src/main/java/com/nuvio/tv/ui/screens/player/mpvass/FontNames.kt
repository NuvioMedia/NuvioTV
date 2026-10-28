package com.nuvio.tv.ui.screens.player.mpvass

import java.nio.ByteBuffer

/**
 * The names libass matches a script's font against: each face's family, full,
 * PostScript and typographic family names, lowercased. Null where the font
 * can't be read.
 */
internal fun fontNames(data: ByteArray): Set<String>? = runCatching {
    val buffer = ByteBuffer.wrap(data)
    val faces = if (buffer.getInt(0) == TTC) (0 until buffer.getInt(8)).map { buffer.getInt(12 + it * 4) } else listOf(0)
    faces.flatMap { faceNames(buffer, it) }.toSet().takeIf { it.isNotEmpty() }
}.getOrNull()

/** A name as libass compares it: a vertical font's `@` dropped, case ignored. */
internal fun fontKey(name: String) = name.trim().removePrefix("@").lowercase()

private fun faceNames(buffer: ByteBuffer, face: Int): List<String> {
    val tables = buffer.getShort(face + 4).toInt() and 0xFFFF
    val record = (0 until tables).map { face + 12 + it * 16 }.firstOrNull { buffer.getInt(it) == NAME } ?: return emptyList()
    val table = buffer.getInt(record + 8)
    val count = buffer.getShort(table + 2).toInt() and 0xFFFF
    val strings = table + (buffer.getShort(table + 4).toInt() and 0xFFFF)
    return (0 until count).mapNotNull { i ->
        val at = table + 6 + i * 12
        val platform = buffer.getShort(at).toInt()
        val id = buffer.getShort(at + 6).toInt()
        if (id !in NAME_IDS) return@mapNotNull null
        val length = buffer.getShort(at + 8).toInt() and 0xFFFF
        val bytes = ByteArray(length).also { buffer.get(strings + (buffer.getShort(at + 10).toInt() and 0xFFFF), it) }
        val text = when (platform) {
            0, 3 -> String(bytes, Charsets.UTF_16BE)
            1 -> String(bytes, Charsets.ISO_8859_1)
            else -> return@mapNotNull null
        }
        fontKey(text).takeIf { it.isNotEmpty() }
    }
}

private fun ByteBuffer.get(at: Int, into: ByteArray) {
    duplicate().apply { position(at) }.get(into)
}

private const val TTC = 0x74746366
private const val NAME = 0x6E616D65
private val NAME_IDS = setOf(1, 4, 6, 16)
