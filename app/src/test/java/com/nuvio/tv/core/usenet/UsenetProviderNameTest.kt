package com.nuvio.tv.core.usenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsenetProviderNameTest {
    @Test fun `a configured provider is named by its server`() {
        val stats = memoryStats()
        val provider = UsenetProvider(name = "Eweka", host = "news.eweka.nl", port = 563, username = "u", password = "p")
        stats.knowProviders(listOf(provider))
        assertEquals("Eweka", stats.providerName(provider.serverUrl()))
        assertNull(stats.providerName("nntps://news.example.test:563/20?priority=1"))
    }
}
