package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.MatroskaAfrProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Reads the chapters of a Matroska/WebM file over HTTP range requests, for ExoPlayer, whose
 * Matroska extractor skips them (mpv lists them itself).
 *
 * It reads the start of the file, follows the SeekHead to the Chapters element and reads only
 * that: two or three small requests. Best-effort: anything unexpected gives no chapters.
 */
internal object MatroskaChapterLoader {
    private const val TOTAL_TIMEOUT_MS = 8_000L
    private const val INITIAL_PROBE_BYTES = 256 * 1024
    private const val HEADER_PROBE_BYTES = 64
    private const val MAX_SEEK_HEAD_BYTES = 256 * 1024
    private const val MAX_CHAPTERS_BYTES = 1024 * 1024

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun load(url: String, headers: Map<String, String>): List<PlayerChapter> {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return emptyList()
        }
        return withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching { loadChapters(url, headers) }.getOrNull()
            }
        }.orEmpty()
    }

    private suspend fun loadChapters(url: String, headers: Map<String, String>): List<PlayerChapter> {
        val initial = fetchRange(url, headers, 0L, INITIAL_PROBE_BYTES) ?: return emptyList()
        val layout = MatroskaChapterParser.topLevelLayout(initial) ?: return emptyList()

        val chaptersPosition = layout.chaptersPosition ?: layout.seekHeadPosition?.let { seekHeadPosition ->
            val seekHead = elementAt(url, headers, initial, seekHeadPosition, MAX_SEEK_HEAD_BYTES)
                ?: return@let null
            MatroskaChapterParser.seekHeadPositions(seekHead)[MatroskaChapterParser.ID_CHAPTERS]
                ?.let { layout.segmentDataStart + it }
        } ?: return emptyList()

        val chapters = elementAt(url, headers, initial, chaptersPosition, MAX_CHAPTERS_BYTES)
            ?: return emptyList()
        return PlayerChapters.normalize(MatroskaChapterParser.parseChapters(chapters))
    }

    /** The whole element at [position], from [initial] when it is already there. */
    private suspend fun elementAt(
        url: String,
        headers: Map<String, String>,
        initial: ByteArray,
        position: Long,
        maxBytes: Int
    ): ByteArray? {
        val header = MatroskaAfrProbe.readElement(initial, position)
            ?: fetchRange(url, headers, position, HEADER_PROBE_BYTES)
                ?.let { MatroskaAfrProbe.readElementFromBufferStart(it, position) }
            ?: return null
        if (header.unknownSize) return null
        val totalSize = header.headerSize + header.dataSize
        if (totalSize <= 0L || totalSize > maxBytes) return null
        val end = position + totalSize
        if (end <= initial.size) return initial.copyOfRange(position.toInt(), end.toInt())
        return fetchRange(url, headers, position, totalSize.toInt())?.takeIf { it.size.toLong() == totalSize }
    }

    private suspend fun fetchRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        length: Int
    ): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${start + length - 1}")
            .header("Accept-Encoding", "identity")
            .apply {
                headers.forEach { (name, value) ->
                    if (!name.equals("Range", ignoreCase = true) && !name.equals("Accept-Encoding", ignoreCase = true)) {
                        header(name, value)
                    }
                }
            }
            .build()
        val call = httpClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = runCatching {
                        response.use {
                            // A server ignoring Range answers 200 with the whole file: only usable from 0.
                            if (it.code != 206 && !(it.code == 200 && start == 0L)) return@use null
                            it.body?.byteStream()?.use { input -> input.readNBytesCompat(length) }
                        }
                    }.getOrNull()
                    if (continuation.isActive) continuation.resume(bytes)
                }
            })
        }
    }

    private fun java.io.InputStream.readNBytesCompat(length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(buffer, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        if (offset == 0) return null
        return if (offset == length) buffer else buffer.copyOf(offset)
    }
}

/** Matroska layout and Chapters parsing over bytes already read; no I/O. */
internal object MatroskaChapterParser {
    const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_EDITION_FLAG_HIDDEN = 0x45BDL
    private const val ID_EDITION_FLAG_DEFAULT = 0x45DBL
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_CHAPTER_TIME_START = 0x91L
    private const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
    private const val ID_CHAPTER_FLAG_ENABLED = 0x4598L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L

    data class TopLevelLayout(
        /** Absolute offset where the Segment payload starts; SeekHead positions are relative to it. */
        val segmentDataStart: Long,
        val seekHeadPosition: Long?,
        val chaptersPosition: Long?
    )

    /** Where the SeekHead and Chapters start, from the head of the file. Null when not Matroska. */
    fun topLevelLayout(head: ByteArray): TopLevelLayout? {
        var position = 0L
        var segment: MatroskaAfrProbe.EbmlElement? = null
        for (attempt in 0 until 8) {
            val element = MatroskaAfrProbe.readElement(head, position) ?: return null
            if (element.id == MatroskaAfrProbe.ID_SEGMENT) {
                segment = element
                break
            }
            position = element.endExclusiveOrNull() ?: return null
        }
        val segmentDataStart = segment?.dataOffset ?: return null
        var seekHead: Long? = null
        var chapters: Long? = null
        position = segmentDataStart
        while (position < head.size) {
            val element = MatroskaAfrProbe.readElement(head, position) ?: break
            when (element.id) {
                MatroskaAfrProbe.ID_SEEK_HEAD -> if (seekHead == null) seekHead = position
                ID_CHAPTERS -> chapters = position
                MatroskaAfrProbe.ID_CLUSTER -> break
            }
            if (chapters != null) break
            position = element.endExclusiveOrNull() ?: break
        }
        return TopLevelLayout(segmentDataStart, seekHead, chapters)
    }

    /** Element id to its position relative to the Segment payload, from a whole SeekHead element. */
    fun seekHeadPositions(seekHead: ByteArray): Map<Long, Long> {
        val root = MatroskaAfrProbe.readElement(seekHead, 0L) ?: return emptyMap()
        if (root.id != MatroskaAfrProbe.ID_SEEK_HEAD) return emptyMap()
        val result = mutableMapOf<Long, Long>()
        children(seekHead, root).filter { it.id == MatroskaAfrProbe.ID_SEEK }.forEach { seek ->
            var id: Long? = null
            var position: Long? = null
            children(seekHead, seek).forEach { child ->
                when (child.id) {
                    MatroskaAfrProbe.ID_SEEK_ID -> id = readUnsigned(seekHead, child)
                    MatroskaAfrProbe.ID_SEEK_POSITION -> position = readUnsigned(seekHead, child)
                }
            }
            val targetId = id
            val targetPosition = position
            if (targetId != null && targetPosition != null) result.putIfAbsent(targetId, targetPosition)
        }
        return result
    }

    /**
     * The chapters of the default edition (else the first one shown), from a whole Chapters element.
     * Hidden and disabled chapters are left out; nested chapters are not listed.
     */
    fun parseChapters(chapters: ByteArray): List<PlayerChapter> {
        val root = MatroskaAfrProbe.readElement(chapters, 0L) ?: return emptyList()
        if (root.id != ID_CHAPTERS) return emptyList()
        val editions = children(chapters, root).filter { it.id == ID_EDITION_ENTRY }
        val shown = editions.filterNot { flag(chapters, it, ID_EDITION_FLAG_HIDDEN, default = false) }
        val edition = shown.firstOrNull { flag(chapters, it, ID_EDITION_FLAG_DEFAULT, default = false) }
            ?: shown.firstOrNull()
            ?: return emptyList()
        return children(chapters, edition)
            .filter { it.id == ID_CHAPTER_ATOM }
            .filterNot { flag(chapters, it, ID_CHAPTER_FLAG_HIDDEN, default = false) }
            .filter { flag(chapters, it, ID_CHAPTER_FLAG_ENABLED, default = true) }
            .mapNotNull { atom ->
                val atomChildren = children(chapters, atom)
                val startNs = atomChildren.firstOrNull { it.id == ID_CHAPTER_TIME_START }
                    ?.let { readUnsigned(chapters, it) }
                    ?: return@mapNotNull null
                val title = atomChildren.firstOrNull { it.id == ID_CHAPTER_DISPLAY }
                    ?.let { display -> children(chapters, display).firstOrNull { it.id == ID_CHAP_STRING } }
                    ?.let { readUtf8(chapters, it) }
                    ?.takeIf { it.isNotBlank() }
                PlayerChapter(startMs = startNs / 1_000_000L, title = title)
            }
    }

    private fun flag(bytes: ByteArray, parent: MatroskaAfrProbe.EbmlElement, id: Long, default: Boolean): Boolean =
        children(bytes, parent).firstOrNull { it.id == id }
            ?.let { readUnsigned(bytes, it) }
            ?.let { it != 0L }
            ?: default

    private fun children(bytes: ByteArray, parent: MatroskaAfrProbe.EbmlElement): List<MatroskaAfrProbe.EbmlElement> {
        val end = parent.endExclusiveOrNull()?.coerceAtMost(bytes.size.toLong()) ?: return emptyList()
        val result = mutableListOf<MatroskaAfrProbe.EbmlElement>()
        var position = parent.dataOffset
        while (position < end) {
            val child = MatroskaAfrProbe.readElement(bytes, position) ?: break
            val childEnd = child.endExclusiveOrNull() ?: break
            if (childEnd > end || childEnd <= position) break
            result += child
            position = childEnd
        }
        return result
    }

    private fun readUnsigned(bytes: ByteArray, element: MatroskaAfrProbe.EbmlElement): Long? {
        if (element.dataSize !in 1L..8L) return null
        var value = 0L
        for (i in 0 until element.dataSize.toInt()) {
            value = (value shl 8) or (bytes[(element.dataOffset + i).toInt()].toLong() and 0xFFL)
        }
        return value
    }

    private fun readUtf8(bytes: ByteArray, element: MatroskaAfrProbe.EbmlElement): String? {
        val start = element.dataOffset.toInt()
        val end = (element.dataOffset + element.dataSize).toInt()
        if (element.dataSize < 0L || end > bytes.size) return null
        return String(bytes, start, end - start, Charsets.UTF_8).trimEnd('\u0000').trim()
    }
}
