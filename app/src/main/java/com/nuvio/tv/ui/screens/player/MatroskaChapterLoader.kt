package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.player.MatroskaAfrProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private const val TAG = "MatroskaChapterLoader"
    private const val TOTAL_TIMEOUT_MS = 12_000L
    private const val INITIAL_PROBE_BYTES = 256 * 1024
    private const val HEADER_PROBE_BYTES = 64
    private const val MAX_SEEK_HEAD_BYTES = 256 * 1024
    private const val MAX_CHAPTERS_BYTES = 1024 * 1024
    private val DEFAULT_RETRY_DELAYS_MS = listOf(1_500L, 3_000L)

    internal sealed interface RangeFetchResult {
        data class Success(val bytes: ByteArray, val resolvedUrl: String) : RangeFetchResult
        data class PermanentFailure(val reason: String) : RangeFetchResult
        data class TransientFailure(val reason: String) : RangeFetchResult
    }

    internal sealed interface ChapterLoadOutcome {
        data class Loaded(val chapters: List<PlayerChapter>) : ChapterLoadOutcome
        data object NoChapters : ChapterLoadOutcome
        data class PermanentFailure(val reason: String) : ChapterLoadOutcome
        data class TransientFailure(val reason: String) : ChapterLoadOutcome
    }

    private sealed interface ElementFetchResult {
        data class Found(val bytes: ByteArray) : ElementFetchResult
        data object NotFound : ElementFetchResult
        data class PermanentFailure(val reason: String) : ElementFetchResult
        data class TransientFailure(val reason: String) : ElementFetchResult
    }

    private val httpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun load(url: String, headers: Map<String, String>): List<PlayerChapter> {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            loadWithFetcher(url, headers) { requestUrl, requestHeaders, start, length ->
                fetchRange(requestUrl, requestHeaders, start, length)
            }
        }
    }

    internal suspend fun loadWithFetcher(
        url: String,
        headers: Map<String, String>,
        retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
        attemptTimeoutMs: Long = TOTAL_TIMEOUT_MS,
        fetchRange: suspend (url: String, headers: Map<String, String>, start: Long, length: Int) -> RangeFetchResult
    ): List<PlayerChapter> {
        val totalAttempts = retryDelaysMs.size + 1
        for (attempt in 0 until totalAttempts) {
            val outcome = try {
                withTimeoutOrNull(attemptTimeoutMs) {
                    loadChapters(url, headers, fetchRange)
                } ?: ChapterLoadOutcome.TransientFailure("timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ChapterLoadOutcome.TransientFailure("io:${e.javaClass.simpleName}")
            } catch (e: Exception) {
                // Range fetches report their failures as results, so what is thrown here comes from
                // parsing the file or building the request: retrying would fail the same way.
                ChapterLoadOutcome.PermanentFailure("exception:${e.javaClass.simpleName}")
            }

            when (outcome) {
                is ChapterLoadOutcome.Loaded -> return outcome.chapters
                ChapterLoadOutcome.NoChapters -> return emptyList()
                is ChapterLoadOutcome.PermanentFailure -> {
                    Log.w(TAG, "Chapter load aborted permanently (${outcome.reason})")
                    return emptyList()
                }
                is ChapterLoadOutcome.TransientFailure -> {
                    Log.w(TAG, "Chapter load attempt ${attempt + 1}/$totalAttempts failed (${outcome.reason})")
                    if (attempt < retryDelaysMs.size) {
                        delay(retryDelaysMs[attempt])
                    } else {
                        return emptyList()
                    }
                }
            }
        }
        return emptyList()
    }

    private suspend fun loadChapters(
        url: String,
        headers: Map<String, String>,
        fetchRange: suspend (url: String, headers: Map<String, String>, start: Long, length: Int) -> RangeFetchResult
    ): ChapterLoadOutcome {
        val initialResult = when (val fetched = fetchRange(url, headers, 0L, INITIAL_PROBE_BYTES)) {
            is RangeFetchResult.Success -> fetched
            is RangeFetchResult.PermanentFailure -> return ChapterLoadOutcome.PermanentFailure(fetched.reason)
            is RangeFetchResult.TransientFailure -> return ChapterLoadOutcome.TransientFailure(fetched.reason)
        }
        val initial = initialResult.bytes
        val layout = MatroskaChapterParser.topLevelLayout(initial) ?: return ChapterLoadOutcome.NoChapters
        val followUpUrl = followUpRangeUrl(url, initialResult.resolvedUrl, headers)

        val chaptersPosition = layout.chaptersPosition ?: run {
            val seekHeadPosition = layout.seekHeadPosition ?: return ChapterLoadOutcome.NoChapters
            val seekHead = when (val element = elementAt(followUpUrl, headers, initial, seekHeadPosition, MAX_SEEK_HEAD_BYTES, fetchRange)) {
                is ElementFetchResult.Found -> element.bytes
                ElementFetchResult.NotFound -> return ChapterLoadOutcome.NoChapters
                is ElementFetchResult.PermanentFailure -> return ChapterLoadOutcome.PermanentFailure(element.reason)
                is ElementFetchResult.TransientFailure -> return ChapterLoadOutcome.TransientFailure(element.reason)
            }
            val relativeChaptersPosition = MatroskaChapterParser.seekHeadPositions(seekHead)[MatroskaChapterParser.ID_CHAPTERS]
                ?: return ChapterLoadOutcome.NoChapters
            layout.segmentDataStart + relativeChaptersPosition
        }

        val chaptersBytes = when (val element = elementAt(followUpUrl, headers, initial, chaptersPosition, MAX_CHAPTERS_BYTES, fetchRange)) {
            is ElementFetchResult.Found -> element.bytes
            ElementFetchResult.NotFound -> return ChapterLoadOutcome.NoChapters
            is ElementFetchResult.PermanentFailure -> return ChapterLoadOutcome.PermanentFailure(element.reason)
            is ElementFetchResult.TransientFailure -> return ChapterLoadOutcome.TransientFailure(element.reason)
        }
        val parsed = PlayerChapters.normalize(MatroskaChapterParser.parseChapters(chaptersBytes))
        return if (parsed.isEmpty()) ChapterLoadOutcome.NoChapters else ChapterLoadOutcome.Loaded(parsed)
    }

    /** The whole element at [position], from [initial] when it is already there. */
    private suspend fun elementAt(
        url: String,
        headers: Map<String, String>,
        initial: ByteArray,
        position: Long,
        maxBytes: Int,
        fetchRange: suspend (url: String, headers: Map<String, String>, start: Long, length: Int) -> RangeFetchResult
    ): ElementFetchResult {
        val header = MatroskaAfrProbe.readElement(initial, position) ?: run {
            val probeResult = when (val fetched = fetchRange(url, headers, position, HEADER_PROBE_BYTES)) {
                is RangeFetchResult.Success -> fetched.bytes
                is RangeFetchResult.PermanentFailure -> return ElementFetchResult.PermanentFailure(fetched.reason)
                is RangeFetchResult.TransientFailure -> return ElementFetchResult.TransientFailure(fetched.reason)
            }
            MatroskaAfrProbe.readElementFromBufferStart(probeResult, position)
                ?: return ElementFetchResult.NotFound
        }
        if (header.unknownSize) return ElementFetchResult.NotFound
        val totalSize = header.headerSize + header.dataSize
        if (totalSize <= 0L || totalSize > maxBytes) return ElementFetchResult.NotFound
        val end = position + totalSize
        if (end <= initial.size) {
            return ElementFetchResult.Found(initial.copyOfRange(position.toInt(), end.toInt()))
        }
        return when (val fetched = fetchRange(url, headers, position, totalSize.toInt())) {
            is RangeFetchResult.Success -> {
                if (fetched.bytes.size.toLong() == totalSize) {
                    ElementFetchResult.Found(fetched.bytes)
                } else {
                    ElementFetchResult.TransientFailure("truncated_element")
                }
            }
            is RangeFetchResult.PermanentFailure -> ElementFetchResult.PermanentFailure(fetched.reason)
            is RangeFetchResult.TransientFailure -> ElementFetchResult.TransientFailure(fetched.reason)
        }
    }

    internal fun followUpRangeUrl(
        originalUrl: String,
        resolvedUrl: String,
        headers: Map<String, String>
    ): String {
        if (resolvedUrl.isBlank() || resolvedUrl == originalUrl) return originalUrl
        val originalHost = originalUrl.toHttpUrlOrNull()?.host ?: return originalUrl
        val resolvedHost = resolvedUrl.toHttpUrlOrNull()?.host ?: return originalUrl
        if (originalHost.equals(resolvedHost, ignoreCase = true)) return resolvedUrl
        val hasSensitiveAuthHeaders = headers.keys.any { key ->
            key.equals("Authorization", ignoreCase = true) || key.equals("Cookie", ignoreCase = true)
        }
        return if (hasSensitiveAuthHeaders) originalUrl else resolvedUrl
    }

    private suspend fun fetchRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        length: Int
    ): RangeFetchResult {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${start + length - 1}")
            .header("Accept-Encoding", "identity")
            .apply {
                if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                    header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
                }
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
                    if (continuation.isActive) {
                        continuation.resume(RangeFetchResult.TransientFailure("io:${e.javaClass.simpleName}"))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { res ->
                            when {
                                res.code == 206 || (res.code == 200 && start == 0L) -> {
                                    val bytes = res.body.byteStream().use { input -> input.readNBytesCompat(length) }
                                    if (bytes == null) {
                                        RangeFetchResult.TransientFailure("empty_body")
                                    } else {
                                        RangeFetchResult.Success(bytes, res.request.url.toString())
                                    }
                                }
                                res.code == 429 || res.code == 408 || res.code in 500..599 -> {
                                    RangeFetchResult.TransientFailure("http_${res.code}")
                                }
                                else -> {
                                    RangeFetchResult.PermanentFailure("http_${res.code}")
                                }
                            }
                        }
                    } catch (e: IOException) {
                        RangeFetchResult.TransientFailure("io:${e.javaClass.simpleName}")
                    } catch (e: Exception) {
                        // Anything else must still resume the caller, not leave it to the attempt timeout.
                        RangeFetchResult.PermanentFailure("exception:${e.javaClass.simpleName}")
                    }
                    if (continuation.isActive) continuation.resume(result)
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
