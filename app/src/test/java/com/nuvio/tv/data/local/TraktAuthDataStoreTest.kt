package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class TraktAuthDataStoreTest {
    @Test
    fun `legacy forced daily lifetime migrates to documented lifetime`() {
        assertEquals(604_800, normalizeTraktTokenLifetimeSeconds(86_400))
    }

    @Test
    fun `returned token lifetime is preserved`() {
        assertEquals(604_800, normalizeTraktTokenLifetimeSeconds(604_800))
        assertEquals(3_600, normalizeTraktTokenLifetimeSeconds(3_600))
    }

    @Test
    fun `invalid token lifetime is preserved for immediate refresh`() {
        assertEquals(0, normalizeTraktTokenLifetimeSeconds(0))
        assertEquals(-1, normalizeTraktTokenLifetimeSeconds(-1))
    }
}
