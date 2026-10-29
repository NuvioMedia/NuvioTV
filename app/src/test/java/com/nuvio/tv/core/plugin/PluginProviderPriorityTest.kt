package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.pluginBingeGroup
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
        val ordered = listOf("first", "second").orderForPluginPlayback(
            id = { it },
            priorityBingeGroup = "torrentio|1080p",
            stats = emptyMap()
        )
        assertEquals(listOf("first", "second"), ordered)
    }

    @Test
    fun `next episode runs only the preferred provider until it misses`() {
        val stats = pluginProviderPlayStats(listOf("favorite", "favorite", "other"))
        val plan = listOf("added-first", "favorite", "other").planPluginRun(
            id = { it },
            priorityBingeGroup = null,
            stats = stats,
            preferredOnly = true
        )
        assertEquals("favorite", plan.preferred)
        assertEquals(listOf("added-first", "other"), plan.others)
    }

    @Test
    fun `next episode keeps the current plugin ahead of the global favorite`() {
        val stats = pluginProviderPlayStats(listOf("favorite", "favorite"))
        val plan = listOf("favorite", "this-show").planPluginRun(
            id = { it },
            priorityBingeGroup = pluginBingeGroup("this-show"),
            stats = stats,
            preferredOnly = true
        )
        assertEquals("this-show", plan.preferred)
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
        assertEquals("vidsrc", scraperIdFromPluginBingeGroup(pluginBingeGroup("vidsrc")))
        assertNull(scraperIdFromPluginBingeGroup("addon-group"))
        assertNull(scraperIdFromPluginBingeGroup("local-plugin-"))
    }
}
