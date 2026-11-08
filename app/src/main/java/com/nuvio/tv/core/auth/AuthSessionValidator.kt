package com.nuvio.tv.core.auth

import com.nuvio.tv.core.util.findCause
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class AuthSessionValidationResult {
    VALID,
    INVALID_SESSION,
    EXPIRED_ACCESS_TOKEN,
    TRANSIENT_FAILURE
}

internal data class AuthSessionValidationOutcome(
    val result: AuthSessionValidationResult,
    val error: Throwable? = null
)

internal class AuthSessionValidator(
    private val auth: Auth
) {
    private val mutex = Mutex()
    @Volatile
    private var validatedAccessToken: String? = null

    suspend fun validate(force: Boolean): AuthSessionValidationOutcome =
        mutex.withLock {
            val accessToken = auth.currentAccessTokenOrNull()?.takeIf { it.isNotBlank() }
                ?: return@withLock AuthSessionValidationOutcome(
                    AuthSessionValidationResult.INVALID_SESSION
                )
            if (!force && validatedAccessToken == accessToken) {
                return@withLock AuthSessionValidationOutcome(
                    AuthSessionValidationResult.VALID
                )
            }

            try {
                val remoteUser = auth.retrieveUserForCurrentSession(false)
                if (auth.currentAccessTokenOrNull() != accessToken) {
                    val hasCurrentSession = auth.currentSessionOrNull() != null
                    if (hasCurrentSession) {
                        markCurrentSessionValidated()
                    }
                    return@withLock AuthSessionValidationOutcome(
                        if (hasCurrentSession) {
                            AuthSessionValidationResult.VALID
                        } else {
                            AuthSessionValidationResult.INVALID_SESSION
                        }
                    )
                }
                val localUser = auth.currentUserOrNull()
                if (
                    localUser == null ||
                    remoteUser.id != localUser.id ||
                    remoteUser.email.isNullOrBlank()
                ) {
                    AuthSessionValidationOutcome(
                        AuthSessionValidationResult.INVALID_SESSION
                    )
                } else {
                    validatedAccessToken = accessToken
                    AuthSessionValidationOutcome(
                        AuthSessionValidationResult.VALID
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (auth.currentAccessTokenOrNull() != accessToken) {
                    val hasCurrentSession = auth.currentSessionOrNull() != null
                    if (hasCurrentSession) {
                        markCurrentSessionValidated()
                    }
                    return@withLock AuthSessionValidationOutcome(
                        if (hasCurrentSession) {
                            AuthSessionValidationResult.VALID
                        } else {
                            AuthSessionValidationResult.INVALID_SESSION
                        },
                        error
                    )
                }
                AuthSessionValidationOutcome(
                    result = when {
                        error.isJwtExpiredAuthError() ->
                            AuthSessionValidationResult.EXPIRED_ACCESS_TOKEN
                        error.isInvalidRemoteSessionError() ->
                            AuthSessionValidationResult.INVALID_SESSION
                        else ->
                            AuthSessionValidationResult.TRANSIENT_FAILURE
                    },
                    error = error
                )
            }
        }

    fun markCurrentSessionValidated() {
        validatedAccessToken = auth.currentAccessTokenOrNull()
    }

    fun reset() {
        validatedAccessToken = null
    }
}

private fun Throwable.isInvalidRemoteSessionError(): Boolean {
    findCause<RestException>()?.let { error ->
        if (
            isInvalidRemoteSessionResponse(
                statusCode = error.statusCode,
                message = "${error.error} ${error.description} ${error.message.orEmpty()}"
            )
        ) {
            return true
        }
    }

    findCause<ClientRequestException>()?.let { error ->
        if (
            isInvalidRemoteSessionResponse(
                statusCode = error.response.status.value,
                message = error.message.orEmpty()
            )
        ) {
            return true
        }
    }

    return isInvalidRemoteSessionResponse(
        statusCode = null,
        message = causeMessages()
    )
}

private fun Throwable.causeMessages(): String {
    val messages = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null) {
        current.message?.let(messages::add)
        current = current.cause
    }
    return messages.joinToString(" ")
}
