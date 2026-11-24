package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.domain.model.Addon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonManagerFocusRestoreTest {

    private fun testAddon(url: String): Addon = Addon(
        id = url,
        name = url,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = url,
        catalogs = emptyList(),
        types = emptyList(),
        resources = emptyList(),
    )

    @Test
    fun `deleting first addon targets the next addon shifting into slot zero`() {
        val addons = listOf(testAddon("a"), testAddon("b"), testAddon("c"))
        assertEquals("b", resolvePostDeleteTargetAddonUrl(addons, deletedUrl = "a"))
    }

    @Test
    fun `deleting middle addon targets the following addon at the same index`() {
        val addons = listOf(testAddon("a"), testAddon("b"), testAddon("c"))
        assertEquals("c", resolvePostDeleteTargetAddonUrl(addons, deletedUrl = "b"))
    }

    @Test
    fun `deleting last addon targets the previous remaining addon`() {
        val addons = listOf(testAddon("a"), testAddon("b"), testAddon("c"))
        assertEquals("b", resolvePostDeleteTargetAddonUrl(addons, deletedUrl = "c"))
    }

    @Test
    fun `deleting the only addon returns null so focus falls back above list`() {
        val addons = listOf(testAddon("a"))
        assertNull(resolvePostDeleteTargetAddonUrl(addons, deletedUrl = "a"))
    }

    @Test
    fun `surviving target falls back by index if preferred target also disappeared`() {
        val postDeleteAddons = listOf(testAddon("a"), testAddon("b"))
        assertEquals(
            "b",
            resolveSurvivingPostDeleteAddonUrl(
                addons = postDeleteAddons,
                deletedUrl = "c",
                preferredTargetUrl = "d",
                deletedIndex = 2
            )
        )
    }

    @Test
    fun `surviving target returns null when no addons remain`() {
        assertNull(
            resolveSurvivingPostDeleteAddonUrl(
                addons = emptyList(),
                deletedUrl = "a",
                preferredTargetUrl = "b",
                deletedIndex = 0
            )
        )
    }
}
