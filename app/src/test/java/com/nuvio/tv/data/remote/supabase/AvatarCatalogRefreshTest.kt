package com.nuvio.tv.data.remote.supabase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarCatalogRefreshTest {
    @Test
    fun recentCatalogDoesNotRequireRefresh() {
        val nowMs = 20 * 60_000L

        assertFalse(isAvatarCatalogRefreshDue(lastRefreshAtMs = nowMs - 60_000L, nowMs = nowMs))
    }

    @Test
    fun staleUninitializedOrFutureCatalogRequiresRefresh() {
        val nowMs = 20 * 60_000L

        assertTrue(isAvatarCatalogRefreshDue(lastRefreshAtMs = 0L, nowMs = nowMs))
        assertTrue(isAvatarCatalogRefreshDue(lastRefreshAtMs = nowMs - 15 * 60_000L, nowMs = nowMs))
        assertTrue(isAvatarCatalogRefreshDue(lastRefreshAtMs = nowMs + 1L, nowMs = nowMs))
    }
}
