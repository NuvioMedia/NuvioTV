package com.nuvio.tv.core.auth

import com.nuvio.tv.data.remote.supabase.TvLoginExchangeResult
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession

internal suspend fun Auth.importTokenResponse(result: TvLoginExchangeResult) {
    val user = result.user ?: retrieveUser(result.accessToken)
    importSession(result.toUserSession(user))
}

internal fun TvLoginExchangeResult.toUserSession(user: UserInfo?): UserSession {
    val lifetimeSeconds = requireNotNull(expiresIn?.takeIf { it > 0L }) {
        "Supabase token response did not include a valid expires_in"
    }
    return UserSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresIn = lifetimeSeconds,
        tokenType = tokenType?.takeIf { it.isNotBlank() } ?: "bearer",
        user = user
    )
}
