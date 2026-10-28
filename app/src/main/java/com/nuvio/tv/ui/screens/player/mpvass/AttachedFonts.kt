package com.nuvio.tv.ui.screens.player.mpvass

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * A Matroska file's attached fonts, read on a connection of their own, so the
 * picture doesn't wait behind them: the extractor skips them. They're kept in
 * [dir], for libass to take only those the shown subtitles use. Styled
 * subtitles use the fallback font until they're in.
 */
@OptIn(UnstableApi::class)
internal class AttachedFonts(
    private val source: DataSource.Factory,
    private val uri: Uri,
    /** The Attachments element, or its content when [length] is known. */
    private val position: Long,
    private val length: Long?,
    private val sink: AssSink,
    private val dir: File,
    /** Whether fonts this many bytes in all may be read now, rather than leave the link to playback. */
    private val mayLoad: (Long) -> Boolean,
) {
    @Volatile
    private var closed = false
    private val thread = Thread(::load, "attached-fonts").apply { isDaemon = true }

    fun start() = thread.start()

    fun close() {
        closed = true
        thread.interrupt()
    }

    private fun load() {
        try {
            val content = if (length != null) position until position + length else content() ?: return
            // No connection stays open meanwhile, which the server would fill.
            while (!mayLoad(content.last + 1 - content.first)) {
                if (closed) return
                Thread.sleep(WAIT_MS)
            }
            dir.mkdirs()
            val fonts = read(content) { input ->
                val fonts = mutableListOf<FontFile>()
                while (input.at <= content.last) {
                    val id = input.id()
                    val size = input.size()
                    if (id == ATTACHED_FILE) file(input, input.at + size, fonts.size)?.let(fonts::add) else input.skip(size)
                }
                fonts
            }
            if (fonts.isNotEmpty() && !closed) sink.fonts(fonts)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "couldn't read the attached fonts", e)
        }
    }

    /** The Attachments element's content, from its header. */
    private fun content(): LongRange? = read(position until position + HEADER_BYTES) { input ->
        if (input.id() != ATTACHMENTS) return@read null
        val size = input.size()
        input.at until input.at + size
    }

    private fun <T> read(range: LongRange, block: (Input) -> T): T {
        val data = source.createDataSource()
        try {
            data.open(DataSpec.Builder().setUri(uri).setPosition(range.first).setLength(range.last + 1 - range.first).build())
            return block(Input(data, range.first))
        } finally {
            runCatching { data.close() }
        }
    }

    /** Saves the attachment if it's a font. */
    private fun file(input: Input, end: Long, index: Int): FontFile? {
        var name: String? = null
        var mime: String? = null
        var bytes: ByteArray? = null
        while (input.at < end) {
            val id = input.id()
            val size = input.size()
            when (id) {
                FILE_NAME -> name = input.text(size)
                FILE_MIME_TYPE -> mime = input.text(size)
                FILE_DATA -> bytes = input.bytes(size)
                else -> input.skip(size)
            }
        }
        val font = bytes?.takeIf { name != null && isFont(name, mime) } ?: return null
        val file = File(dir, "$index-" + name!!.replace(UNSAFE, "_"))
        file.writeBytes(font)
        return FontFile(file, fontNames(font))
    }

    /** EBML read in order off the connection, until closed. */
    private inner class Input(private val data: DataSource, var at: Long) {
        private val buffer = ByteArray(64 * 1024)
        private var filled = 0
        private var next = 0

        fun id(): Long = vint(keepMarker = true)

        fun size(): Long = vint(keepMarker = false).also { if (it == UNKNOWN_SIZE) throw IOException("an attachment of unknown size") }

        fun text(size: Long) = String(bytes(size), Charsets.UTF_8).trimEnd('\u0000')

        fun bytes(size: Long): ByteArray {
            if (size > Int.MAX_VALUE) throw IOException("an attachment too large to hold")
            val out = ByteArray(size.toInt())
            var copied = 0
            while (copied < out.size) {
                if (next == filled) fill()
                val n = minOf(out.size - copied, filled - next)
                System.arraycopy(buffer, next, out, copied, n)
                copied += n
                next += n
                at += n
            }
            return out
        }

        fun skip(size: Long) {
            var left = size
            while (left > 0) {
                if (next == filled) fill()
                val n = minOf(left, (filled - next).toLong()).toInt()
                next += n
                at += n
                left -= n
            }
        }

        private fun byte(): Int {
            if (next == filled) fill()
            at++
            return buffer[next++].toInt() and 0xFF
        }

        private fun vint(keepMarker: Boolean): Long {
            val first = byte()
            val length = Integer.numberOfLeadingZeros(first) - 23
            if (length !in 1..8) throw IOException("not a Matroska number")
            var value = (if (keepMarker) first else first and (0xFF shr length)).toLong()
            repeat(length - 1) { value = (value shl 8) or byte().toLong() }
            if (!keepMarker && value == (1L shl (7 * length)) - 1) return UNKNOWN_SIZE
            return value
        }

        private fun fill() {
            if (closed) throw InterruptedIOException()
            val n = data.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) throw EOFException()
            filled = n
            next = 0
        }
    }

    companion object {
        private const val TAG = "attached-fonts"
        private const val WAIT_MS = 250L
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        /** An element's id and size. */
        private const val HEADER_BYTES = 12L
        private const val UNKNOWN_SIZE = -1L
        const val ATTACHMENTS = 0x1941A469L
        private const val ATTACHED_FILE = 0x61A7L
        private const val FILE_NAME = 0x466EL
        private const val FILE_MIME_TYPE = 0x4660L
        private const val FILE_DATA = 0x465CL

        fun isFont(name: String, mime: String?) =
            mime?.let { "font" in it || "truetype" in it || "opentype" in it } == true ||
                name.substringAfterLast('.').lowercase() in setOf("ttf", "otf", "ttc", "woff", "woff2")
    }
}
