package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltInUsenetOrderTest {
    private val urls = listOf("https://a.example", "https://b.example", "https://c.example")

    @Test
    fun `never placed it follows every addon`() {
        assertEquals(3, BuiltInUsenetOrder.index(urls, null))
    }

    @Test
    fun `placed first it leads the addons`() {
        assertEquals(0, BuiltInUsenetOrder.index(urls, emptyList()))
    }

    @Test
    fun `it follows the lowest addon placed above it`() {
        assertEquals(2, BuiltInUsenetOrder.index(urls, listOf("https://a.example", "https://b.example")))
        // Reordered on another device: b went below c, so it now follows b at the end.
        assertEquals(3, BuiltInUsenetOrder.index(listOf("https://a.example", "https://c.example", "https://b.example"),
            listOf("https://a.example", "https://b.example")))
    }

    @Test
    fun `a removed addon leaves it after the one above that`() {
        assertEquals(1, BuiltInUsenetOrder.index(listOf("https://a.example", "https://c.example"),
            listOf("https://a.example", "https://b.example")))
        assertEquals(0, BuiltInUsenetOrder.index(listOf("https://c.example"), listOf("https://a.example")))
    }

    @Test
    fun `addon urls match without case or a trailing slash`() {
        assertEquals(1, BuiltInUsenetOrder.index(urls, listOf("HTTPS://A.example/")))
    }

    @Test
    fun `moving built-in Usenet changes only its place`() {
        val up = BuiltInUsenetOrder.move(urls, usenetIndex = 3, url = null, direction = -1)!!
        assertEquals(urls, up.addonUrls)
        assertEquals(listOf("https://a.example", "https://b.example"), up.addonsAbove)

        val down = BuiltInUsenetOrder.move(urls, usenetIndex = 0, url = null, direction = 1)!!
        assertEquals(urls, down.addonUrls)
        assertEquals(listOf("https://a.example"), down.addonsAbove)
    }

    @Test
    fun `moving an addon past built-in Usenet changes only Usenet's place`() {
        val move = BuiltInUsenetOrder.move(urls, usenetIndex = 1, url = "https://b.example", direction = -1)!!
        assertEquals(urls, move.addonUrls)
        assertEquals(listOf("https://a.example", "https://b.example"), move.addonsAbove)
    }

    @Test
    fun `moving an addon past another reorders the addons`() {
        val move = BuiltInUsenetOrder.move(urls, usenetIndex = 3, url = "https://c.example", direction = -1)!!
        assertEquals(listOf("https://a.example", "https://c.example", "https://b.example"), move.addonUrls)
    }

    @Test
    fun `nothing moves past either end`() {
        assertNull(BuiltInUsenetOrder.move(urls, usenetIndex = 0, url = null, direction = -1))
        assertNull(BuiltInUsenetOrder.move(urls, usenetIndex = 0, url = "https://c.example", direction = 1))
    }

    @Test
    fun `stream groups follow built-in Usenet's place`() {
        val addons = urls.mapIndexed { i, url -> addon("Addon $i", url) }
        val groups = listOf("Addon 0", "Addon 1", "Addon 2", BuiltInUsenetService.GROUP_NAME, "A plugin").map {
            AddonStreams(addonName = it, addonLogo = null, streams = emptyList())
        }
        val order = BuiltInUsenetOrder.sourceOrder(addons, listOf("https://a.example"))
        assertEquals(listOf("Addon 0", BuiltInUsenetService.GROUP_NAME, "Addon 1", "Addon 2", "A plugin"),
            StreamAutoPlaySelector.orderAddonStreams(groups, order).map { it.addonName })
    }

    private fun addon(name: String, url: String) = Addon(
        id = name, name = name, version = "1", description = null, logo = null, baseUrl = url,
        catalogs = emptyList(), types = emptyList(), resources = emptyList()
    )
}
