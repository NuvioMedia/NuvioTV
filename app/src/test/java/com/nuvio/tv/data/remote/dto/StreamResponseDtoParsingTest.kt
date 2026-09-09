package com.nuvio.tv.data.remote.dto

import com.nuvio.tv.data.mapper.toDomain
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the SubtitleDto nullability fix: a single malformed subtitle
 * entry (missing/null url or lang) from a non-compliant addon must no longer fail Moshi
 * parsing for the entire stream response - see StreamResponseDto.kt / StreamMapper.kt.
 */
class StreamResponseDtoParsingTest {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(StreamResponseDto::class.java)

    @Test
    fun `complete subtitle entry parses and maps normally`() {
        val dto = adapter.fromJson(
            """{"streams":[{"url":"https://example.com/s.mp4","subtitles":[{"id":"1","url":"https://example.com/en.srt","lang":"en"}]}]}"""
        )!!

        val stream = dto.streams!!.single().toDomain(addonName = "Addon", addonLogo = null)

        assertEquals(1, stream.subtitles.size)
        assertEquals("https://example.com/en.srt", stream.subtitles[0].url)
        assertEquals("en", stream.subtitles[0].lang)
    }

    @Test
    fun `subtitle missing lang field parses with Unknown fallback instead of failing`() {
        val dto = adapter.fromJson(
            """{"streams":[{"url":"https://example.com/s.mp4","subtitles":[{"url":"https://example.com/x.srt"}]}]}"""
        )!!

        val stream = dto.streams!!.single().toDomain(addonName = "Addon", addonLogo = null)

        assertEquals(1, stream.subtitles.size)
        assertEquals("Unknown", stream.subtitles[0].lang)
    }

    @Test
    fun `subtitle with explicit null url is dropped, not fatal to the response`() {
        val dto = adapter.fromJson(
            """{"streams":[{"url":"https://example.com/s.mp4","subtitles":[{"url":null,"lang":"en"}]}]}"""
        )!!

        val stream = dto.streams!!.single().toDomain(addonName = "Addon", addonLogo = null)

        assertTrue(stream.subtitles.isEmpty())
    }

    @Test
    fun `subtitle missing url field entirely is dropped, not fatal to the response`() {
        val dto = adapter.fromJson(
            """{"streams":[{"url":"https://example.com/s.mp4","subtitles":[{"lang":"en"}]}]}"""
        )!!

        val stream = dto.streams!!.single().toDomain(addonName = "Addon", addonLogo = null)

        assertTrue(stream.subtitles.isEmpty())
    }

    @Test
    fun `one malformed subtitle does not discard sibling valid subtitles or streams`() {
        val dto = adapter.fromJson(
            """
            {"streams":[
              {"url":"https://example.com/s1.mp4","subtitles":[
                {"url":"https://example.com/ok.srt","lang":"en"},
                {"lang":"fr"}
              ]},
              {"url":"https://example.com/s2.mp4"}
            ]}
            """.trimIndent()
        )!!

        val streams = dto.streams!!.map { it.toDomain(addonName = "Addon", addonLogo = null) }

        assertEquals(2, streams.size)
        assertEquals(1, streams[0].subtitles.size)
        assertEquals("https://example.com/ok.srt", streams[0].subtitles[0].url)
    }

    @Test
    fun `unknown extra fields in the JSON are ignored, not fatal`() {
        val dto = adapter.fromJson(
            """{"streams":[{"url":"https://example.com/s.mp4","unexpectedField":123,"subtitles":[{"url":"https://example.com/x.srt","lang":"en","extra":"surprise"}]}]}"""
        )!!

        val stream = dto.streams!!.single().toDomain(addonName = "Addon", addonLogo = null)

        assertEquals(1, stream.subtitles.size)
    }

    @Test
    fun `empty streams array parses to an empty list, not null or an exception`() {
        val dto = adapter.fromJson("""{"streams":[]}""")!!

        assertEquals(emptyList<StreamDto>(), dto.streams)
    }

    @Test
    fun `missing streams field entirely parses to null rather than throwing`() {
        val dto = adapter.fromJson("""{}""")!!

        assertNull(dto.streams)
    }
}
