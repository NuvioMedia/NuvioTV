package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamProviderGroupingTest {
    private fun stream(provider: String?, name: String, addon: String = "Repository") = Stream(
        name = name, title = null, description = null, url = name, ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = addon, addonLogo = null, pluginSourceName = provider
    )

    @Test fun `providers are alphabetical within each repository and streams retain their order`() {
        val z1 = stream("Zulu", "z1")
        val a1 = stream("alpha", "a1")
        val z2 = stream("Zulu", "z2")
        val other = stream("Beta", "other", "Other repository")
        assertEquals(listOf(a1, z1, z2, other), groupStreamsByProvider(listOf(z1, a1, z2, other)))
    }

    @Test fun `ordinary addon streams keep their order and duplicate results are retained`() {
        val one = stream(null, "one", "Addon")
        val two = stream(null, "two", "Addon")
        val blank = stream(" ", "blank", "Addon")
        assertEquals(listOf(one, two, one, blank), groupStreamsByProvider(listOf(one, two, one, blank)))
        assertEquals(emptyList<Stream>(), groupStreamsByProvider(emptyList()))
    }

    @Test fun `a provider header appears once per group and separately in another repository`() {
        val first = stream("Alpha", "first")
        val second = stream("Alpha", "second")
        val third = stream("Beta", "third")
        assertEquals("Alpha", streamProviderHeader(first))
        assertNull(streamProviderHeader(second, first))
        assertEquals("Beta", streamProviderHeader(third, second))
        assertEquals("Alpha", streamProviderHeader(first.copy(addonName = "Other repository"), second))
    }

    @Test fun `empty and addon-equivalent provider names have no header`() {
        assertNull(streamProviderHeader(stream(null, "null")))
        assertNull(streamProviderHeader(stream(" ", "blank")))
        assertNull(streamProviderHeader(stream("Repository", "same")))
    }

    @Test fun `grouping precedes pagination so a provider stays together across page expansion`() {
        val input = (1..120).map { stream(if (it % 2 == 0) "Alpha" else "Zulu", "$it") }
        val grouped = groupStreamsByProvider(input)
        val page = grouped.take(50)
        assertEquals(List(50) { "Alpha" }, page.map { it.pluginSourceName })
        assertNull(streamProviderHeader(grouped[50], page.last()))
        assertEquals("Zulu", streamProviderHeader(grouped[60], grouped[59]))
        assertEquals(grouped, groupStreamsByProvider(grouped))
    }
}
