package com.nuvio.tv.data.simkl

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simkl sign-in using the AUTH V2 device flow (RFC 8628): the TV shows a QR code for
 * `verification_uri_complete` plus the `user_code`, and polls `POST /oauth2/token` until the
 * user approves. Access tokens last 7 days and are refreshed by [SimklApiClient].
 */
@Singleton
class SimklAuthRepository(
    private val apiClient: SimklApiClient,
    private val configuration: SimklApiConfiguration,
    private val storage: SimklAuthStorage,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(
        apiClient: SimklApiClient,
        configuration: SimklApiConfiguration,
        storage: SimklAuthStorage
    ) : this(apiClient, configuration, storage, System::currentTimeMillis)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val state: StateFlow<SimklAuthState> = storage.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    suspend fun startPinAuth(): SimklPinSession = mutex.withLock {
        val authScope = storage.currentScope()
        if (!hasRequiredCredentials()) throw SimklAuthException(SimklAuthError.MISSING_CLIENT_ID)
        val response = executeAuthRequest(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = SIMKL_DEVICE_PATH,
                body = simklFormBody(
                    mapOf(
                        "client_id" to configuration.clientId,
                        "scope" to SIMKL_REQUESTED_SCOPE
                    )
                ),
                requiresAuthentication = false,
                contentType = SimklContentType.FORM
            )
        )
        val payload = decode<SimklDeviceAuthorizationResponse>(response.body)
        val deviceCode = payload.deviceCode?.trim()?.takeIf(String::isNotBlank)
        val userCode = payload.userCode?.trim()?.takeIf(String::isNotBlank)
        val verificationUri = payload.verificationUri?.trim()?.takeIf(String::isNotBlank)
        val expiresIn = payload.expiresIn?.takeIf { it > 0L }
        val interval = (payload.interval ?: DEFAULT_POLL_INTERVAL_SECONDS).coerceAtLeast(1)
        if (deviceCode == null || userCode == null || verificationUri == null || expiresIn == null) {
            throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        }
        val session = SimklPinSession(
            userCode = userCode,
            verificationUri = verificationUri,
            expiresAtEpochMs = nowEpochMs() + expiresIn * 1_000L,
            intervalSeconds = interval,
            verificationUriComplete = payload.verificationUriComplete?.trim()?.takeIf(String::isNotBlank)
        )
        if (!storage.savePinSession(session, deviceCode, authScope)) {
            throw SimklAuthException(SimklAuthError.PIN_INVALIDATED)
        }
        session
    }

    suspend fun pollPin(): SimklPinPollResult = mutex.withLock {
        val authScope = storage.currentScope()
        val session = storage.state.value.pinSession ?: return@withLock SimklPinPollResult.Invalidated
        if (nowEpochMs() >= session.expiresAtEpochMs) {
            storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
            return@withLock SimklPinPollResult.Expired
        }
        val deviceCode = storage.pinDeviceCode()
        if (deviceCode.isNullOrBlank()) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return@withLock SimklPinPollResult.Invalidated
        }
        val response = try {
            apiClient.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = SIMKL_TOKEN_PATH,
                    body = simklFormBody(
                        mapOf(
                            "grant_type" to SIMKL_DEVICE_CODE_GRANT_TYPE,
                            "client_id" to configuration.clientId,
                            "device_code" to deviceCode
                        )
                    ),
                    requiresAuthentication = false,
                    // A failed poll is simply repeated on the next interval.
                    retryPolicy = SimklRetryPolicy.NEVER,
                    contentType = SimklContentType.FORM
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimklApiException) {
            return@withLock handlePollError(error, authScope, session)
        }
        val grant = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
            .getOrNull()
            ?.toGrant(nowEpochMs())
            ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        if (!grant.canWrite) {
            Log.w(TAG, "Simkl granted scope=${grant.scope}; scrobbling and list changes will be rejected")
        }
        if (!storage.completeDeviceAuthorization(grant, authScope)) {
            return@withLock SimklPinPollResult.Invalidated
        }
        try {
            refreshUserSettings(scope = authScope)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
        SimklPinPollResult.Authorized
    }

    private fun handlePollError(
        error: SimklApiException,
        authScope: SimklAuthScope,
        session: SimklPinSession
    ): SimklPinPollResult = when (error.errorCode) {
        "authorization_pending" -> SimklPinPollResult.Pending
        "slow_down" -> {
            // RFC 8628: add 5 seconds to the interval and actually wait it out.
            storage.updatePinInterval(session.intervalSeconds + SLOW_DOWN_STEP_SECONDS, authScope)
            SimklPinPollResult.SlowDown
        }
        "expired_token" -> {
            storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
            SimklPinPollResult.Expired
        }
        "invalid_client", "unauthorized_client" -> {
            storage.clearPinSession(SimklAuthError.INVALID_CLIENT, authScope)
            throw SimklAuthException(SimklAuthError.INVALID_CLIENT, error)
        }
        "invalid_grant", "access_denied" -> {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            SimklPinPollResult.Invalidated
        }
        else -> when {
            error.status == 401 -> {
                storage.clearPinSession(SimklAuthError.INVALID_CLIENT, authScope)
                throw SimklAuthException(SimklAuthError.INVALID_CLIENT, error)
            }
            // Transport failures, bursts and server errors: keep the code and poll again.
            error.status == null || error.status == 429 || error.status >= 500 -> SimklPinPollResult.Pending
            else -> throw SimklAuthException(SimklAuthError.NETWORK, error)
        }
    }

    suspend fun refreshUserSettings(
        activityWatermark: String? = null,
        scope: SimklAuthScope = storage.currentScope()
    ): String? {
        if (!storage.isCurrent(scope) || !storage.state.value.isAuthenticated) return null
        val response = apiClient.execute(
            request = SimklApiRequest(SimklHttpMethod.POST, "/users/settings"),
            expectedAuthScope = scope
        )
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }.getOrNull()
            ?: return null
        val username = settings.user?.name?.trim()?.takeIf(String::isNotBlank)
        val saved = storage.saveIdentity(
            username = username,
            accountId = settings.account?.id,
            settingsActivityWatermark = activityWatermark,
            scope = scope
        )
        return username.takeIf { saved }
    }

    suspend fun synchronizeUserSettings(activityWatermark: String?) {
        val authScope = storage.currentScope()
        if (!storage.state.value.isAuthenticated) return
        try {
            when (simklSettingsRefreshAction(storage.state.value, activityWatermark)) {
                SimklSettingsRefreshAction.NONE -> Unit
                SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                    storage.recordSettingsActivityWatermark(requireNotNull(activityWatermark), authScope)
                }
                SimklSettingsRefreshAction.FETCH -> refreshUserSettings(activityWatermark, authScope)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
    }

    fun cancelPinAuth() = storage.clearPinSession()

    /**
     * Signs out locally, then revokes the grant on Simkl (RFC 7009). Revoking either token ends
     * the whole grant, and the endpoint always answers 200, so failures are ignored.
     */
    suspend fun disconnect() {
        val authorization = storage.authorization()
        storage.clearAuth()
        val token = authorization?.refreshToken ?: authorization?.accessToken ?: return
        if (!hasRequiredCredentials()) return
        try {
            apiClient.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = SIMKL_REVOKE_PATH,
                    body = simklFormBody(
                        mapOf(
                            "client_id" to configuration.clientId,
                            "token" to token
                        )
                    ),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                    contentType = SimklContentType.FORM
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
    }

    private suspend fun executeAuthRequest(request: SimklApiRequest): SimklApiResponse = try {
        apiClient.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SimklAuthException) {
        throw error
    } catch (error: SimklApiException) {
        val authError = when {
            error.errorCode == "invalid_client" || error.errorCode == "unauthorized_client" ||
                error.status == 401 -> SimklAuthError.INVALID_CLIENT
            else -> SimklAuthError.NETWORK
        }
        throw SimklAuthException(authError, error)
    } catch (error: Throwable) {
        throw SimklAuthException(SimklAuthError.NETWORK, error)
    }

    private inline fun <reified T> decode(body: String): T =
        runCatching { json.decodeFromString<T>(body) }
            .getOrElse { throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE, it) }

    private companion object {
        const val TAG = "SimklAuth"
        const val DEFAULT_POLL_INTERVAL_SECONDS = 5
        const val SLOW_DOWN_STEP_SECONDS = 5
    }
}
