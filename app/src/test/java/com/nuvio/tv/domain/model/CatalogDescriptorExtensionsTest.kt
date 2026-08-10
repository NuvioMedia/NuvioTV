package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDescriptorExtensionsTest {

    @Test
    fun `supportsExtra reads full extra list`() {
        val catalog = descriptor(
            extra = listOf(CatalogExtra(name = "skip"), CatalogExtra(name = "genre"))
        )
        assertTrue(catalog.supportsExtra("skip"))
        assertTrue(catalog.supportsExtra("genre"))
        assertFalse(catalog.supportsExtra("search"))
    }

    @Test
    fun `supportsExtra reads short-form extraSupported`() {
        val catalog = descriptor(extraSupported = listOf("search", "skip"))
        assertTrue(catalog.supportsExtra("skip"))
        assertTrue(catalog.supportsExtra("SKIP"))
        assertFalse(catalog.supportsExtra("genre"))
    }

    @Test
    fun `supportsExtra reads short-form extraRequired`() {
        val catalog = descriptor(extraRequired = listOf("search"))
        assertTrue(catalog.supportsExtra("search"))
        assertFalse(catalog.supportsExtra("skip"))
    }

    @Test
    fun `skipStep prefers pageSize`() {
        val catalog = descriptor(pageSize = 20)
        assertEquals(20, catalog.skipStep())
    }

    @Test
    fun `skipStep infers step from skip options`() {
        val catalog = descriptor(
            extra = listOf(
                CatalogExtra(name = "skip", options = listOf("0", "50", "100", "150"))
            )
        )
        assertEquals(50, catalog.skipStep())
    }

    @Test
    fun `skipStep falls back to default when no pageSize or options`() {
        val catalog = descriptor()
        assertEquals(100, catalog.skipStep())
    }

    private fun descriptor(
        extra: List<CatalogExtra> = emptyList(),
        pageSize: Int? = null,
        extraSupported: List<String> = emptyList(),
        extraRequired: List<String> = emptyList()
    ): CatalogDescriptor {
        return CatalogDescriptor(
            type = ContentType.MOVIE,
            id = "top",
            name = "Top",
            extra = extra,
            pageSize = pageSize,
            extraSupported = extraSupported,
            extraRequired = extraRequired
        )
    }
}
