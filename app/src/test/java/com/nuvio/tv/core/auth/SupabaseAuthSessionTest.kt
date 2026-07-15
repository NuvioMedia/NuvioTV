package com.nuvio.tv.core.auth

import com.nuvio.tv.data.remote.supabase.TvLoginExchangeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseAuthSessionTest {

    @Test
    fun `token response preserves expiry when creating session`() {
        val response = TvLoginExchangeResult(
            accessToken = "access",
            refreshToken = "refresh",
            tokenType = "bearer",
            expiresIn = 3600L
        )

        val session = response.toUserSession(user = null)

        assertEquals("access", session.accessToken)
        assertEquals("refresh", session.refreshToken)
        assertEquals("bearer", session.tokenType)
        assertEquals(3600L, session.expiresIn)
        assertTrue(session.expiresAt.toEpochMilliseconds() > System.currentTimeMillis())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `token response rejects missing expiry`() {
        TvLoginExchangeResult(
            accessToken = "access",
            refreshToken = "refresh"
        ).toUserSession(user = null)
    }
}
