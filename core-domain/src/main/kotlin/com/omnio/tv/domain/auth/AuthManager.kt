package com.omnio.tv.domain.auth

import com.omnio.tv.domain.model.AuthState
import com.omnio.tv.domain.model.auth.TvLoginPollResult
import com.omnio.tv.domain.model.auth.TvLoginStartResult
import kotlinx.coroutines.flow.StateFlow

interface AuthManager {

    val authState: StateFlow<AuthState>

    val isAuthenticated: Boolean

    val currentUserId: String?

    suspend fun getEffectiveUserId(fallbackToOwnIdOnFailure: Boolean = true): String?

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit>

    suspend fun signInWithEmail(email: String, password: String): Result<Unit>

    suspend fun ensureQrSessionAuthenticated(): Result<Unit>

    suspend fun signOut(explicit: Boolean = true)

    fun clearEffectiveUserIdCache()

    suspend fun refreshSessionIfJwtExpired(error: Throwable): Boolean

    suspend fun startTvLoginSession(
        deviceNonce: String,
        deviceName: String?,
        redirectBaseUrl: String
    ): Result<TvLoginStartResult>

    suspend fun pollTvLoginSession(code: String, deviceNonce: String): Result<TvLoginPollResult>

    suspend fun exchangeTvLoginSession(code: String, deviceNonce: String): Result<Unit>
}
