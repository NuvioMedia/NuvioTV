package com.nuvio.tv.data.remote.supabase

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseProfileSetupCopyResultTest {
    @Test
    fun `ignores response fields that are not used by tv`() {
        val result = Json.decodeFromString<SupabaseProfileSetupCopyResult>(
            """
            {
              "source_profile_id": 1,
              "target_profile_id": 2,
              "tv_status": "copied",
              "mobile_status": "not_selected",
              "desktop_status": "not_selected",
              "provider_credentials_status": "unchanged",
              "provider_credentials_found": 0,
              "provider_credentials_written": 0,
              "provider_credentials_preserved": 0
            }
            """.trimIndent()
        )

        assertEquals(1, result.sourceProfileId)
        assertEquals(2, result.targetProfileId)
        assertEquals("copied", result.tvStatus)
        assertEquals("unchanged", result.providerCredentialsStatus)
    }
}
