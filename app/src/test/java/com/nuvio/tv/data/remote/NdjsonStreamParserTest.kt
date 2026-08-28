package com.nuvio.tv.data.remote

import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdjsonStreamParserTest {

    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(StreamResponseDto::class.java)

    @Test
    fun `isNdjsonContentType matches x-ndjson with and without charset`() {
        assertTrue(isNdjsonContentType("application/x-ndjson"))
        assertTrue(isNdjsonContentType("application/x-ndjson; charset=utf-8"))
        assertTrue(isNdjsonContentType("APPLICATION/X-NDJSON"))
    }

    @Test
    fun `isNdjsonContentType rejects other content types`() {
        assertFalse(isNdjsonContentType("application/json"))
        assertFalse(isNdjsonContentType("text/event-stream"))
        assertFalse(isNdjsonContentType("application/x-ndjsonx"))
        assertFalse(isNdjsonContentType(null))
    }

    @Test
    fun `parseNdjsonStreamDtos decodes each line independently`() {
        val line1 = """{"streams":[{"name":"A","url":"https://a.example/1"}]}"""
        val line2 = """{"streams":[{"name":"B","url":"https://b.example/2"}]}"""

        val batch1 = parseNdjsonStreamDtos(adapter, line1)
        val batch2 = parseNdjsonStreamDtos(adapter, line2)

        assertEquals(listOf("A"), batch1.map { it.name })
        assertEquals(listOf("B"), batch2.map { it.name })
    }

    @Test
    fun `parseNdjsonStreamDtos tolerates blank and malformed lines`() {
        assertEquals(emptyList<StreamDto>(), parseNdjsonStreamDtos(adapter, ""))
        assertEquals(emptyList<StreamDto>(), parseNdjsonStreamDtos(adapter, "   "))
        assertEquals(emptyList<StreamDto>(), parseNdjsonStreamDtos(adapter, "not json"))
        assertEquals(emptyList<StreamDto>(), parseNdjsonStreamDtos(adapter, """{"streams":[]}"""))
        assertEquals(emptyList<StreamDto>(), parseNdjsonStreamDtos(adapter, """{"other":1}"""))
    }

    @Test
    fun `parseNdjsonStreamDtos keeps stream fields`() {
        val batch = parseNdjsonStreamDtos(
            adapter,
            """{"streams":[{"name":"A","url":"https://a.example/1","infoHash":"abc"}]}"""
        )

        assertEquals(1, batch.size)
        assertEquals("A", batch[0].name)
        assertEquals("https://a.example/1", batch[0].url)
        assertEquals("abc", batch[0].infoHash)
    }
}
