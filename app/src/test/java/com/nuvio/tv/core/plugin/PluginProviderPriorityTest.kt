package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.indexOfPluginBingeMatch
import com.nuvio.tv.domain.model.pluginBingeGroup
import com.nuvio.tv.domain.model.pluginBingeGroupMatches
import com.nuvio.tv.domain.model.pluginIdentityTokens
import com.nuvio.tv.domain.model.scraperIdFromPluginBingeGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginProviderPriorityTest {
    @Test
    fun `most played provider runs first and the rest keep add order`() {
        val stats = pluginProviderPlayStats(listOf("added-first", "favorite", "favorite"))
        val ordered = listOf("added-first", "favorite", "added-last").orderForPluginPlayback(
            id = { it },
            priorityBingeGroup = null,
            stats = stats
        )
        assertEquals(listOf("favorite", "added-first", "added-last"), ordered)
    }

    @Test
    fun `equal play counts prefer the provider used most recently`() {
        val stats = pluginProviderPlayStats(listOf("older", "newer"))
        val ordered = listOf("older", "newer").orderForPluginPlayback(
            id = { it },
            priorityBingeGroup = null,
            stats = stats
        )
        assertEquals(listOf("newer", "older"), ordered)
    }

    @Test
    fun `current plugin binge group runs ahead of the most played provider`() {
        val stats = pluginProviderPlayStats(listOf("favorite", "favorite", "favorite"))
        val ordered = listOf("favorite", "this-show").orderForPluginPlayback(
            id = { it },
            priorityBingeGroup = pluginBingeGroup("this-show"),
            stats = stats
        )
        assertEquals(listOf("this-show", "favorite"), ordered)
    }

    @Test
    fun `addon binge group leaves plugin order unchanged when nothing has been played`() {
        val stats = emptyMap<String, PluginProviderPlayStat>()
        val ordered = listOf("added-first", "added-second").orderForPluginPlayback(
            id = { it },
            priorityBingeGroup = "torrentio|720p",
            stats = stats
        )
        assertEquals(listOf("added-first", "added-second"), ordered)
    }

    @Test
    fun `next episode runs only the preferred provider until it misses`() {
        val stats = pluginProviderPlayStats(listOf("favorite", "favorite"))
        val plan = listOf("added-first", "favorite").planPluginRun(
            id = { it },
            priorityBingeGroup = null,
            stats = stats,
            preferredOnly = true
        )
        assertEquals("favorite", plan.preferred)
        assertEquals(listOf("added-first"), plan.others)
    }

    @Test
    fun `next episode keeps the current plugin ahead of the global favorite`() {
        val stats = pluginProviderPlayStats(listOf("favorite", "favorite", "favorite"))
        val plan = listOf("favorite", "current-show").planPluginRun(
            id = { it },
            priorityBingeGroup = pluginBingeGroup("current-show"),
            stats = stats,
            preferredOnly = true
        )
        assertEquals("current-show", plan.preferred)
        assertEquals(listOf("favorite"), plan.others)
    }

    @Test
    fun `a normal search still runs every provider together`() {
        val stats = pluginProviderPlayStats(listOf("favorite"))
        val plan = listOf("added-first", "favorite").planPluginRun(
            id = { it },
            priorityBingeGroup = null,
            stats = stats,
            preferredOnly = false
        )
        assertEquals(null, plan.preferred)
        assertEquals(listOf("favorite", "added-first"), plan.others)
    }

    @Test
    fun `plugin binge group stores the provider id`() {
        assertEquals("example", scraperIdFromPluginBingeGroup(pluginBingeGroup("example")))
        assertEquals("example", scraperIdFromPluginBingeGroup("local-plugin-example|1080"))
        assertNull(scraperIdFromPluginBingeGroup("addon-group"))
        assertNull(scraperIdFromPluginBingeGroup("local-plugin-"))
        assertNull(scraperIdFromPluginBingeGroup("torrentio|1080p"))
    }

    @Test
    fun `identity tokens keep the plugin wording and fold only unicode`() {
        assertEquals(listOf("1080", "hdr"), pluginIdentityTokens(quality = "1080p HDR"))
        assertEquals(listOf("4k"), pluginIdentityTokens(quality = "4K"))
        assertEquals(listOf("fhd"), pluginIdentityTokens(quality = "FHD"))
        assertEquals(listOf("français", "vostfr"), pluginIdentityTokens(title = "Français (VOSTFR)"))
        assertEquals(listOf("español", "latino"), pluginIdentityTokens(language = "Español Latino"))
        assertEquals(listOf("1", "server"), pluginIdentityTokens(name = "Server 1"))
        assertEquals(emptyList<String>(), pluginIdentityTokens())
    }

    @Test
    fun `next episode keeps the plugin variant and drops the episode-only tokens`() {
        val saved = pluginBingeGroup(
            scraperId = "example-scraper",
            name = "Filemoon",
            title = "Episode 1 Dual Audio",
            quality = "720p"
        )
        val original = pluginBingeGroup(
            scraperId = "example-scraper",
            name = "Filemoon",
            title = "Episode 2 Original",
            quality = "720p"
        )
        val dual = pluginBingeGroup(
            scraperId = "example-scraper",
            name = "Filemoon",
            title = "Épisode 2 Dual Audio",
            quality = "720P"
        )
        val french = pluginBingeGroup(
            scraperId = "example-scraper",
            title = "S01E02 Français VOSTFR",
            quality = "1080p"
        )
        val frenchNext = pluginBingeGroup(
            scraperId = "example-scraper",
            title = "S01E03 Français VOSTFR",
            quality = "1080p"
        )
        val frenchOther = pluginBingeGroup(
            scraperId = "example-scraper",
            title = "S01E03 Français VF",
            quality = "1080p"
        )

        assertEquals(1, indexOfPluginBingeMatch(saved, listOf(original, dual)))
        assertEquals(0, indexOfPluginBingeMatch(french, listOf(frenchNext, frenchOther)))
        assertEquals(false, pluginBingeGroupMatches(frenchOther, french))
        assertEquals(true, pluginBingeGroupMatches(frenchNext, french))
    }

    @Test
    fun `quality specific plugin groups do not match a different variant`() {
        assertEquals(
            true,
            pluginBingeGroupMatches("local-plugin-example|720", "local-plugin-example|720")
        )
        assertEquals(
            false,
            pluginBingeGroupMatches("local-plugin-example|1080", "local-plugin-example|720")
        )
        assertEquals(
            true,
            pluginBingeGroupMatches("local-plugin-example|1080", "local-plugin-example")
        )
        assertEquals(
            false,
            pluginBingeGroupMatches("torrentio|1080p", "torrentio|720p")
        )
        assertEquals(
            false,
            pluginBingeGroupMatches(
                "local-plugin-example|1080 filemoon",
                "local-plugin-example|720 filemoon"
            )
        )
    }

    @Test
    fun `a numbered alternative keeps the closest variant instead of matching nothing`() {
        val saved = pluginBingeGroup(
            scraperId = "example-scraper",
            name = "Server 1",
            title = "Türkçe Altyazı"
        )
        val sameServerOtherAudio = pluginBingeGroup(
            scraperId = "example-scraper",
            name = "Server 1",
            title = "Türkçe Dublaj"
        )
        val otherServerSameAudio = pluginBingeGroup(
            scraperId = "example-scraper",
            name = "Server 2",
            title = "Türkçe Altyazı"
        )
        val exact = pluginBingeGroup(
            scraperId = "example-scraper",
            name = "Server 1",
            title = "Türkçe Altyazı"
        )

        assertEquals(1, indexOfPluginBingeMatch(saved, listOf(sameServerOtherAudio, otherServerSameAudio)))
        assertEquals(0, indexOfPluginBingeMatch(saved, listOf(exact, otherServerSameAudio)))
    }
}
