package com.nuvio.tv.data.anilist

import android.content.Context
import android.util.Base64
import com.nuvio.tv.core.server.AniListLoginResult
import com.nuvio.tv.core.server.AniListLoginServer
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class AniListQrSession(
    val scope: AniListAuthScope,
    val loginUrl: String,
    val displayUrl: String,
    val pairingCode: String,
    val expiresAtEpochMs: Long
)

enum class AniListQrError {
    MISSING_CLIENT_ID,
    NO_NETWORK,
    SERVER_UNAVAILABLE
}

class AniListQrException(val error: AniListQrError) : Exception(error.name)

class AniListAuthRepository(
    private val context: Context,
    private val store: AniListAuthStore,
    private val api: AniListApiClient,
    private val sync: AniListSyncRepository,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val random = SecureRandom()
    private var server: AniListLoginServer? = null
    private var expiryJob: Job? = null
    private val mutableSession = MutableStateFlow<AniListQrSession?>(null)
    val session: StateFlow<AniListQrSession?> = mutableSession.asStateFlow()
    val state: StateFlow<AniListAuthState> = store.state

    fun hasRequiredCredentials(): Boolean = AniListConfig.isConfigured

    fun authorizationUrl(): String =
        "${AniListConfig.AUTHORIZE_URL}?client_id=${URLEncoder.encode(AniListConfig.CLIENT_ID, "UTF-8")}&response_type=token"

    fun ensureLoaded() {
        store.refreshExpiry()
        val current = mutableSession.value
        if (current != null && (!store.isCurrent(current.scope) || current.expiresAtEpochMs <= now())) stopQrLogin()
    }

    fun startQrLogin(): AniListQrSession = synchronized(lock) {
        ensureLoaded()
        if (!hasRequiredCredentials()) throw AniListQrException(AniListQrError.MISSING_CLIENT_ID)
        val scope = store.scope()
        mutableSession.value?.takeIf { it.scope == scope && it.expiresAtEpochMs > now() && server != null }?.let { return it }
        stopServer()
        val ip = DeviceIpAddress.get(context) ?: throw AniListQrException(AniListQrError.NO_NETWORK)
        val pairingCode = (0 until PAIRING_CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }
        val started = AniListLoginServer.startOnAvailablePort(
            pairingCode = pairingCode,
            authorizeUrl = authorizationUrl(),
            onTokenSubmitted = { token -> onTokenSubmitted(scope, token) }
        ) ?: throw AniListQrException(AniListQrError.SERVER_UNAVAILABLE)
        server = started
        store.beginAuthorization(scope)
        val displayUrl = "http://$ip:${started.listeningPort}"
        val session = AniListQrSession(
            scope = scope,
            loginUrl = "$displayUrl/?code=$pairingCode",
            displayUrl = displayUrl,
            pairingCode = pairingCode,
            expiresAtEpochMs = now() + AniListAuthStore.PENDING_WINDOW_MS
        )
        mutableSession.value = session
        expiryJob?.cancel()
        expiryJob = coroutineScope.launch {
            delay(AniListAuthStore.PENDING_WINDOW_MS)
            synchronized(lock) {
                if (mutableSession.value == session) {
                    stopServer()
                    mutableSession.value = null
                    store.cancelAuthorization(session.scope)
                }
            }
        }
        session
    }

    fun stopQrLogin() = synchronized(lock) {
        stopServer()
        val current = mutableSession.value
        mutableSession.value = null
        current?.let { store.cancelAuthorization(it.scope) }
    }

    suspend fun connectWithToken(scope: AniListAuthScope, rawToken: String): AniListViewer {
        store.checkScope(scope)
        val token = extractToken(rawToken) ?: throw AniListAuthException(AniListAuthError.INVALID_CALLBACK)
        store.setBusy(true, scope)
        try {
            val viewer = api.viewerForToken(token)
            store.checkScope(scope)
            if (!store.authorize(token, tokenExpiry(token), scope)) throw CancellationException("AniList account changed")
            val authorizedScope = store.scope()
            store.saveViewer(viewer, authorizedScope)
            synchronized(lock) {
                expiryJob?.cancel()
                expiryJob = null
                mutableSession.value = null
                server?.let { finished ->
                    coroutineScope.launch {
                        delay(SERVER_SHUTDOWN_DELAY_MS)
                        synchronized(lock) {
                            if (server === finished) server = null
                        }
                        runCatching { finished.stop() }
                    }
                }
            }
            coroutineScope.launch {
                try {
                    sync.refresh(TrackingRefreshIntent.INVALIDATED)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                }
            }
            return viewer
        } finally {
            store.setBusy(false, store.scope())
        }
    }

    fun disconnect() {
        stopQrLogin()
        store.clearAuth(store.scope())
    }

    private fun onTokenSubmitted(scope: AniListAuthScope, token: String): AniListLoginResult {
        if (!store.isCurrent(scope)) {
            return AniListLoginResult(false, "This sign-in link has expired. Scan the QR code on your TV again.")
        }
        return runBlocking {
            val result = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) {
                try {
                    val viewer = connectWithToken(scope, token)
                    AniListLoginResult(true, viewer.name)
                } catch (error: TimeoutCancellationException) {
                    throw error
                } catch (error: CancellationException) {
                    AniListLoginResult(false, "This sign-in link has expired. Scan the QR code on your TV again.")
                } catch (error: AniListAuthException) {
                    AniListLoginResult(false, "AniList did not accept this token. Sign in again and copy the full token.")
                } catch (error: AniListApiException) {
                    if (error.status == 429) {
                        AniListLoginResult(false, "AniList's request limit has been reached. Try again in a minute.")
                    } else {
                        AniListLoginResult(false, "Could not reach AniList. Try again.")
                    }
                } catch (error: Exception) {
                    AniListLoginResult(false, "Could not reach AniList. Try again.")
                }
            }
            result ?: AniListLoginResult(false, "AniList took too long to respond. Try again.")
        }
    }

    private fun stopServer() {
        expiryJob?.cancel()
        expiryJob = null
        server?.let { runCatching { it.stop() } }
        server = null
    }

    private fun extractToken(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val fromUrl = if (trimmed.contains("access_token=")) {
            trimmed.substringAfter("access_token=").substringBefore('&').substringBefore('#').let { value ->
                runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }
        } else {
            trimmed
        }
        return fromUrl.filterNot(Char::isWhitespace).takeIf(String::isNotBlank)
    }

    private fun tokenExpiry(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val payload = runCatching {
            String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val exp = runCatching { JSONObject(payload).optLong("exp", 0L) }.getOrDefault(0L)
        return exp.takeIf { it > 0L }?.let { it * 1_000L }
    }

    companion object {
        private const val PAIRING_CODE_LENGTH = 6
        private const val SUBMIT_TIMEOUT_MS = 30_000L
        private const val SERVER_SHUTDOWN_DELAY_MS = 3_000L
    }
}
