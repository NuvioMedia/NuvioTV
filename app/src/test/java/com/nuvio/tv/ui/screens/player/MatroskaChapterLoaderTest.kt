package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.MatroskaAfrProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MatroskaChapterLoaderTest {

    private fun element(id: Long, vararg children: ByteArray): ByteArray =
        MatroskaAfrProbe.buildElement(id, children.fold(ByteArray(0)) { acc, child -> acc + child })

    private fun uint(id: Long, value: Long): ByteArray {
        var width = 1
        while (width < 8 && value ushr (width * 8) != 0L) width++
        return MatroskaAfrProbe.buildElement(id, ByteArray(width) { i -> (value ushr ((width - 1 - i) * 8)).toByte() })
    }

    private fun text(id: Long, value: String): ByteArray = MatroskaAfrProbe.buildElement(id, value.toByteArray())

    private fun atom(startMs: Long, title: String?): ByteArray {
        val parts = mutableListOf(uint(0x91L, startMs * 1_000_000L))
        if (title != null) parts += element(0x80L, text(0x85L, title), text(0x437CL, "eng"))
        return element(0xB6L, *parts.toTypedArray())
    }

    private fun chaptersElement(vararg atoms: ByteArray): ByteArray =
        element(MatroskaChapterParser.ID_CHAPTERS, element(0x45B9L, *atoms))

    private fun mkvWithInlineChapters(): ByteArray {
        val ebml = element(MatroskaAfrProbe.ID_EBML, uint(0x4282L, 0L))
        val info = element(MatroskaAfrProbe.ID_INFO, uint(0x2AD7B1L, 1_000_000L))
        val chapters = chaptersElement(atom(0L, "Intro"), atom(90_000L, "Episode"))
        val cluster = element(MatroskaAfrProbe.ID_CLUSTER, uint(0xE7L, 0L))
        return ebml + MatroskaAfrProbe.buildSegmentUnknownSize(info + chapters + cluster)
    }

    private fun mkvWithoutChapters(): ByteArray {
        val ebml = element(MatroskaAfrProbe.ID_EBML, uint(0x4282L, 0L))
        val info = element(MatroskaAfrProbe.ID_INFO, uint(0x2AD7B1L, 1_000_000L))
        val cluster = element(MatroskaAfrProbe.ID_CLUSTER, uint(0xE7L, 0L))
        return ebml + MatroskaAfrProbe.buildSegmentUnknownSize(info + cluster)
    }

    private data class RemoteSeekMkv(
        val headBytes: ByteArray,
        val chaptersOffset: Long,
        val chaptersBytes: ByteArray
    )

    private fun mkvWithRemoteChaptersViaSeekHead(): RemoteSeekMkv {
        val ebml = element(MatroskaAfrProbe.ID_EBML, uint(0x4282L, 0L))
        val chaptersBytes = chaptersElement(atom(0L, "Cold Open"), atom(120_000L, "Opening"))
        val remoteRelativeOffset = 1_000_000L
        val seek = element(
            MatroskaAfrProbe.ID_SEEK,
            MatroskaAfrProbe.buildElement(
                MatroskaAfrProbe.ID_SEEK_ID,
                MatroskaAfrProbe.elementIdBytes(MatroskaChapterParser.ID_CHAPTERS)
            ),
            uint(MatroskaAfrProbe.ID_SEEK_POSITION, remoteRelativeOffset)
        )
        val seekHead = element(MatroskaAfrProbe.ID_SEEK_HEAD, seek)
        val cluster = element(MatroskaAfrProbe.ID_CLUSTER, uint(0xE7L, 0L))
        val payloadPrefix = seekHead + cluster
        val fileHead = ebml + MatroskaAfrProbe.buildSegmentUnknownSize(payloadPrefix)
        val segmentDataStart = (fileHead.size - payloadPrefix.size).toLong()
        return RemoteSeekMkv(
            headBytes = fileHead,
            chaptersOffset = segmentDataStart + remoteRelativeOffset,
            chaptersBytes = chaptersBytes
        )
    }

    @Test
    fun `retries transient failure and succeeds on subsequent attempt`() = runBlocking {
        val mkv = mkvWithInlineChapters()
        var attempts = 0

        val result = MatroskaChapterLoader.loadWithFetcher(
            url = "https://debrid.example.com/stream.mkv",
            headers = emptyMap(),
            retryDelaysMs = listOf(0L, 0L)
        ) { url, _, _, _ ->
            attempts++
            if (attempts == 1) {
                MatroskaChapterLoader.RangeFetchResult.TransientFailure("http_429")
            } else {
                MatroskaChapterLoader.RangeFetchResult.Success(mkv, url)
            }
        }

        assertEquals(2, attempts)
        assertEquals(listOf("Intro", "Episode"), result.map { it.title })
    }

    @Test
    fun `does not retry when file has no chapters`() = runBlocking {
        val mkv = mkvWithoutChapters()
        var attempts = 0

        val result = MatroskaChapterLoader.loadWithFetcher(
            url = "https://debrid.example.com/stream.mkv",
            headers = emptyMap(),
            retryDelaysMs = listOf(0L, 0L)
        ) { url, _, _, _ ->
            attempts++
            MatroskaChapterLoader.RangeFetchResult.Success(mkv, url)
        }

        assertTrue(result.isEmpty())
        assertEquals(1, attempts)
    }

    @Test
    fun `does not retry permanent http failure`() = runBlocking {
        var attempts = 0

        val result = MatroskaChapterLoader.loadWithFetcher(
            url = "https://debrid.example.com/stream.mkv",
            headers = emptyMap(),
            retryDelaysMs = listOf(0L, 0L)
        ) { _, _, _, _ ->
            attempts++
            MatroskaChapterLoader.RangeFetchResult.PermanentFailure("http_404")
        }

        assertTrue(result.isEmpty())
        assertEquals(1, attempts)
    }

    @Test
    fun `does not retry a non-io exception such as a parse failure`() = runBlocking {
        var attempts = 0

        val result = MatroskaChapterLoader.loadWithFetcher(
            url = "https://debrid.example.com/stream.mkv",
            headers = emptyMap(),
            retryDelaysMs = listOf(0L, 0L)
        ) { _, _, _, _ ->
            attempts++
            throw IllegalStateException("malformed element")
        }

        assertTrue(result.isEmpty())
        assertEquals(1, attempts)
    }

    @Test
    fun `retries a thrown io exception`() = runBlocking {
        val mkv = mkvWithInlineChapters()
        var attempts = 0

        val result = MatroskaChapterLoader.loadWithFetcher(
            url = "https://debrid.example.com/stream.mkv",
            headers = emptyMap(),
            retryDelaysMs = listOf(0L, 0L)
        ) { url, _, _, _ ->
            attempts++
            if (attempts == 1) throw IOException("connection reset")
            MatroskaChapterLoader.RangeFetchResult.Success(mkv, url)
        }

        assertEquals(2, attempts)
        assertEquals(listOf("Intro", "Episode"), result.map { it.title })
    }

    @Test
    fun `stops after retry budget is exhausted`() = runBlocking {
        var attempts = 0

        val result = MatroskaChapterLoader.loadWithFetcher(
            url = "https://debrid.example.com/stream.mkv",
            headers = emptyMap(),
            retryDelaysMs = listOf(0L, 0L)
        ) { _, _, _, _ ->
            attempts++
            MatroskaChapterLoader.RangeFetchResult.TransientFailure("timeout")
        }

        assertTrue(result.isEmpty())
        assertEquals(3, attempts)
    }

    @Test
    fun `reuses resolved redirect url for follow-up range requests without auth headers`() = runBlocking {
        val remote = mkvWithRemoteChaptersViaSeekHead()
        val originalUrl = "https://addon.example.com/redirect/ep2.mkv"
        val resolvedUrl = "https://cdn.example.net/files/ep2.mkv"
        val requestedUrls = mutableListOf<String>()

        val result = MatroskaChapterLoader.loadWithFetcher(
            url = originalUrl,
            headers = emptyMap(),
            retryDelaysMs = emptyList()
        ) { url, _, start, _ ->
            requestedUrls += url
            when (start) {
                0L -> MatroskaChapterLoader.RangeFetchResult.Success(remote.headBytes, resolvedUrl)
                remote.chaptersOffset -> MatroskaChapterLoader.RangeFetchResult.Success(remote.chaptersBytes, resolvedUrl)
                else -> MatroskaChapterLoader.RangeFetchResult.PermanentFailure("unexpected_offset_$start")
            }
        }

        assertEquals(listOf("Cold Open", "Opening"), result.map { it.title })
        assertEquals(listOf(originalUrl, resolvedUrl, resolvedUrl), requestedUrls)
    }

    @Test
    fun `keeps original url on cross-host redirect when authorization header is present`() = runBlocking {
        val remote = mkvWithRemoteChaptersViaSeekHead()
        val originalUrl = "https://addon.example.com/redirect/ep2.mkv"
        val resolvedUrl = "https://cdn.example.net/files/ep2.mkv"
        val requestedUrls = mutableListOf<String>()

        val result = MatroskaChapterLoader.loadWithFetcher(
            url = originalUrl,
            headers = mapOf("Authorization" to "Bearer secret"),
            retryDelaysMs = emptyList()
        ) { url, _, start, _ ->
            requestedUrls += url
            when (start) {
                0L -> MatroskaChapterLoader.RangeFetchResult.Success(remote.headBytes, resolvedUrl)
                remote.chaptersOffset -> MatroskaChapterLoader.RangeFetchResult.Success(remote.chaptersBytes, resolvedUrl)
                else -> MatroskaChapterLoader.RangeFetchResult.PermanentFailure("unexpected_offset_$start")
            }
        }

        assertEquals(listOf("Cold Open", "Opening"), result.map { it.title })
        assertEquals(listOf(originalUrl, originalUrl, originalUrl), requestedUrls)
    }
}
