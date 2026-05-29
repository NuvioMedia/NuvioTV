package com.nuvio.tv.core.debrid

import org.junit.Assert.assertEquals
import org.junit.Test

class DebridStreamTemplateEngineTest {
    private val engine = DebridStreamTemplateEngine()

    @Test
    fun `renders equality and existence branches`() {
        val output = engine.render(
            "{stream.resolution::=2160p[\"4K \"||\"\"]}{stream.resolution::exists[\"\"||\"Unknown \"]}{service.cached::istrue[\"Ready\"||\"Not Ready\"]}",
            mapOf(
                "stream.resolution" to "2160p",
                "service.cached" to true
            )
        )

        assertEquals("4K Ready", output)
    }

    @Test
    fun `renders nested replacements and joins`() {
        val output = engine.render(
            "{stream.audioTags::exists[\"{stream.audioTags::join(' | ')::replace('Atmos','ATMOS')}\"||\"\"]}",
            mapOf("stream.audioTags" to listOf("TrueHD", "Atmos"))
        )

        assertEquals("TrueHD | ATMOS", output)
    }

    @Test
    fun `renders byte formatting`() {
        val output = engine.render(
            "{stream.size::>0[\"{stream.size::bytes}\"||\"\"]}",
            mapOf("stream.size" to 58_408_797_841L)
        )

        assertEquals("54.4 GB", output)
    }

    @Test
    fun `supports and or condition groups`() {
        val output = engine.render(
            "{stream.audioTags::exists::or::stream.audioChannels::exists::and::stream.languages::exists[\"yes\"||\"no\"]}",
            mapOf(
                "stream.audioTags" to emptyList<String>(),
                "stream.audioChannels" to listOf("5.1"),
                "stream.languages" to listOf("English")
            )
        )

        assertEquals("yes", output)
    }

    @Test
    fun `supports contains shorthand operator`() {
        val output = engine.render(
            "{stream.quality::~Remux[\"remux\"||\"other\"]}",
            mapOf("stream.quality" to "BluRay REMUX")
        )

        assertEquals("remux", output)
    }
}
