package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class TraktAuthServiceTest {
    @Test
    fun `token refresh delay schedules one minute before expiry`() {
        val state = authenticatedState().copy(createdAt = 1_000L, expiresIn = 3_600)

        assertEquals(
            3_540_000L,
            traktTokenRefreshDelayMillis(state = state, nowMillis = 1_000_000L)
        )
    }

    @Test
    fun `token refresh delay is immediate for expired or incomplete authenticated state`() {
        assertEquals(
            0L,
            traktTokenRefreshDelayMillis(
                state = authenticatedState().copy(createdAt = 1_000L, expiresIn = 3_600),
                nowMillis = 4_600_000L
            )
        )
        assertEquals(
            0L,
            traktTokenRefreshDelayMillis(
                state = authenticatedState().copy(createdAt = null),
                nowMillis = 1_000_000L
            )
        )
    }

    @Test
    fun `token refresh delay ignores unauthenticated state`() {
        assertNull(
            traktTokenRefreshDelayMillis(
                state = TraktAuthState(),
                nowMillis = 1_000_000L
            )
        )
    }

    @Test
    fun `refresh token 400 clears credentials and prevents another refresh`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>()
        var authState = authenticatedState()

        coEvery { traktAuthDataStore.getCurrentState() } answers { authState }
        coEvery { traktAuthDataStore.clearAuth() } answers { authState = TraktAuthState() }
        coEvery { authSessionNoticeDataStore.markTraktReconnectRequired() } returns Unit
        coEvery { traktApi.refreshToken(any()) } returns Response.error(400, "invalid_grant".toResponseBody())

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = authSessionNoticeDataStore
        )

        assertFalse(service.refreshTokenIfNeeded(force = true))
        assertFalse(service.refreshTokenIfNeeded(force = true))

        coVerify(exactly = 1) { traktApi.refreshToken(any()) }
        coVerify(exactly = 1) { authSessionNoticeDataStore.markTraktReconnectRequired() }
        coVerify(exactly = 1) { traktAuthDataStore.clearAuth() }
    }

    private fun authenticatedState(): TraktAuthState {
        return TraktAuthState(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            tokenType = "bearer",
            createdAt = 1L,
            expiresIn = 3600
        )
    }
}
