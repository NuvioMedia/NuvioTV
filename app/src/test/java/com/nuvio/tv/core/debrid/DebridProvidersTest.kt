package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridProvidersTest {
    @Test
    fun `configured services only include visible providers`() {
        val settings = DebridSettings(
            enabled = true,
            torboxApiKey = "tb",
            realDebridApiKey = "rd"
        )

        val services = DebridProviders.configuredServices(settings)

        assertEquals(listOf(DebridProviders.Torbox), services.map { it.provider })
        assertTrue(DebridProviders.isVisible(DebridProviders.TORBOX_ID))
        assertFalse(DebridProviders.isVisible(DebridProviders.REAL_DEBRID_ID))
    }
}
