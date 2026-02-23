package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRevokeRequestDto
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Representa el resultado del sondeo del token durante la autenticación del dispositivo.
 */
sealed interface TraktTokenPollResult {
    data object Pending : TraktTokenPollResult // Esperando que el usuario autorice
    data object Expired : TraktTokenPollResult // El código ha expirado
    data object Denied : TraktTokenPollResult  // El usuario denegó el acceso
    data class SlowDown(val pollIntervalSeconds: Int) : TraktTokenPollResult // Requisito de esperar más entre intentos
    data class Approved(val username: String?) : TraktTokenPollResult // Autorización exitosa
    data class Failed(val reason: String) : TraktTokenPollResult // Fallo genérico
}

@Singleton
class TraktAuthService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthDataStore: TraktAuthDataStore
) {
    private val refreshLeewaySeconds = 60L
    private val writeRequestMutex = Mutex()
    private var lastWriteRequestAtMs = 0L
    private val minWriteIntervalMs = 1_000L

    fun hasRequiredCredentials(): Boolean {
        return BuildConfig.TRAKT_CLIENT_ID.isNotBlank() && BuildConfig.TRAKT_CLIENT_SECRET.isNotBlank()
    }

    suspend fun getCurrentAuthState(): TraktAuthState = traktAuthDataStore.state.first()

    /**
     * Inicia el flujo de autenticación solicitando un código para mostrar en pantalla.
     */
    suspend fun startDeviceAuth(): Result<TraktDeviceCodeResponseDto> {
        if (!hasRequiredCredentials()) {
            return Result.failure(IllegalStateException("Faltan las credenciales de TRAKT en el archivo de configuración"))
        }

        val response = traktApi.requestDeviceCode(
            TraktDeviceCodeRequestDto(clientId = BuildConfig.TRAKT_CLIENT_ID)
        )
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return Result.failure(
                IllegalStateException("Error al iniciar autenticación en Trakt (Código: ${response.code()})")
            )
        }

        traktAuthDataStore.saveDeviceFlow(body)
        return Result.success(body)
    }

    /**
     * Consulta si el usuario ya ingresó el código en el sitio de Trakt.
     */
    suspend fun pollDeviceToken(): TraktTokenPollResult {
        if (!hasRequiredCredentials()) {
            return TraktTokenPollResult.Failed("Faltan las credenciales de TRAKT")
        }

        val state = getCurrentAuthState()
        val deviceCode = state.deviceCode
        if (deviceCode.isNullOrBlank()) {
            return TraktTokenPollResult.Failed("No hay un código de dispositivo activo")
        }

        val response = traktApi.requestDeviceToken(
            TraktDeviceTokenRequestDto(
                code = deviceCode,
                clientId = BuildConfig.TRAKT_CLIENT_ID,
                clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
            )
        )

        val tokenBody = response.body()
        if (response.isSuccessful && tokenBody != null) {
            traktAuthDataStore.saveToken(tokenBody)
            traktAuthDataStore.clearDeviceFlow()
            val user = fetchUserSettings()
            return TraktTokenPollResult.Approved(user)
        }

        return when (response.code()) {
            400, 409 -> TraktTokenPollResult.Pending
            404 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.Failed("Código de dispositivo no válido")
            }
            410 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.Expired
            }
            418 -> {
                traktAuthDataStore.clearDeviceFlow()
                TraktTokenPollResult.Denied
            }
            429 -> {
                val nextInterval = ((state.pollInterval ?: 5) + 5).coerceAtMost(60)
                traktAuthDataStore.updatePollInterval(nextInterval)
                TraktTokenPollResult.SlowDown(nextInterval)
            }
            else -> TraktTokenPollResult.Failed("Error en el sondeo del token (Código: ${response.code()})")
        }
    }

    /**
     * Renueva el token de acceso si ha expirado o está cerca de hacerlo.
     */
    suspend fun refreshTokenIfNeeded(force: Boolean = false): Boolean {
        if (!hasRequiredCredentials()) return false

        val state = getCurrentAuthState()
        val refreshToken = state.refreshToken ?: return false
        if (!force && !isTokenExpiredOrExpiring(state)) {
            return true
        }

        val response = try {
            traktApi.refreshToken(
                TraktRefreshTokenRequestDto(
                    refreshToken = refreshToken,
                    clientId = BuildConfig.TRAKT_CLIENT_ID,
                    clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
                )
            )
        } catch (e: IOException) {
            Log.w("TraktAuthService", "Error de red al intentar renovar el token", e)
            return false
        }

        val tokenBody = response.body()
        if (!response.isSuccessful || tokenBody == null) {
            if (response.code() == 401 || response.code() == 403) {
                // Si la renovación falla por falta de autorización, cerramos la sesión
                traktAuthDataStore.clearAuth()
            }
            return false
        }

        traktAuthDataStore.saveToken(tokenBody)
        return true
    }

    suspend fun revokeAndLogout() {
        val state = getCurrentAuthState()
        if (hasRequiredCredentials()) {
            state.accessToken?.let { accessToken ->
                runCatching {
                    traktApi.revokeToken(
                        TraktRevokeRequestDto(
                            token = accessToken,
                            clientId = BuildConfig.TRAKT_CLIENT_ID,
                            clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
                        )
                    )
                }
            }
        }
        traktAuthDataStore.clearAuth()
    }

    suspend fun fetchUserSettings(): String? {
        val response = executeAuthorizedRequest { authHeader ->
            traktApi.getUserSettings(authorization = authHeader)
        } ?: return null

        if (!response.isSuccessful) return null

        val username = response.body()?.user?.username
        val slug = response.body()?.user?.ids?.slug
        traktAuthDataStore.saveUser(username = username, userSlug = slug)
        return username
    }

    /**
     * Ejecuta una petición que requiere token Bearer, manejando automáticamente la renovación si falla.
     */
    suspend fun <T> executeAuthorizedRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        var token = getValidAccessToken() ?: return null
        var retriedAuth = false
        var retriedRateLimit = false

        while (true) {
            val response = try {
                call("Bearer $token")
            } catch (e: IOException) {
                Log.w("TraktAuthService", "Error de red durante una petición autorizada", e)
                return null
            }

            // Manejo de token expirado
            if (response.code() == 401 && !retriedAuth && refreshTokenIfNeeded(force = true)) {
                token = getCurrentAuthState().accessToken ?: return response
                retriedAuth = true
                continue
            }

            // Manejo de límite de peticiones (Rate Limit)
            if (response.code() == 429 && !retriedRateLimit) {
                val retryAfterSeconds = response.headers()["Retry-After"]
                    ?.toLongOrNull()
                    ?.coerceIn(1L, 60L)
                    ?: 2L
                delay(retryAfterSeconds * 1000L)
                retriedRateLimit = true
                continue
            }

            return response
        }
    }

    /**
     * Ejecuta una petición de escritura (POST/DELETE) con control de intervalos para evitar colisiones.
     */
    suspend fun <T> executeAuthorizedWriteRequest(
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T>? {
        writeRequestMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (lastWriteRequestAtMs + minWriteIntervalMs - now).coerceAtLeast(0L)
            if (waitMs > 0L) delay(waitMs)
            lastWriteRequestAtMs = System.currentTimeMillis()
        }
        return executeAuthorizedRequest(call)
    }

    private suspend fun getValidAccessToken(): String? {
        val state = getCurrentAuthState()
        if (state.accessToken.isNullOrBlank()) return null
        if (refreshTokenIfNeeded(force = false)) {
            return getCurrentAuthState().accessToken
        }
        return null
    }

    private fun isTokenExpiredOrExpiring(state: TraktAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        val expiresAt = createdAt + expiresIn
        val nowSeconds = System.currentTimeMillis() / 1000L
        return nowSeconds >= (expiresAt - refreshLeewaySeconds)
    }
}