package com.nuvio.tv.data.remote

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.ServerCapabilities
import com.nuvio.tv.domain.model.ServerConfiguration
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

enum class ServerDiscoveryFailure {
    INVALID_URL,
    OFFICIAL_SERVER,
    CONNECTION_FAILED,
    HTTP_ERROR,
    RESPONSE_TOO_LARGE,
    INVALID_DOCUMENT,
    UNSUPPORTED_VERSION,
    WRONG_SERVICE,
    NOT_SELF_HOSTED,
    MISSING_CONFIGURATION,
    NO_SUPPORTED_AUTH
}

class ServerDiscoveryException(
    val failure: ServerDiscoveryFailure,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : Exception(failure.name, cause)

@Serializable
private data class DiscoveryDocument(
    val version: Int,
    val service: String,
    @SerialName("self_hosted") val selfHosted: Boolean,
    @SerialName("backend_url") val backendUrl: String,
    @SerialName("publishable_key") val publishableKey: String,
    val capabilities: DiscoveryCapabilities
)

@Serializable
private data class DiscoveryCapabilities(
    @SerialName("email_password_auth") val emailPasswordAuth: Boolean = false,
    @SerialName("tv_login") val tvLogin: Boolean = false
)

internal object ServerDiscoveryPolicy {
    private val json = Json { ignoreUnknownKeys = true }

    fun discoveryUrl(input: String): String {
        val normalizedInput = input.trim()
        if (normalizedInput.isBlank()) throw ServerDiscoveryException(ServerDiscoveryFailure.INVALID_URL)
        val withScheme = if (normalizedInput.contains("://")) normalizedInput else "https://$normalizedInput"
        val parsed = withScheme.toHttpUrlOrNull()
            ?: throw ServerDiscoveryException(ServerDiscoveryFailure.INVALID_URL)
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            throw ServerDiscoveryException(ServerDiscoveryFailure.INVALID_URL)
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.INVALID_URL)
        }
        val suffix = "/.well-known/nuvio"
        val basePath = parsed.encodedPath.removeSuffix(suffix).trimEnd('/')
        return parsed.newBuilder()
            .encodedPath(if (basePath.isBlank()) suffix else "$basePath$suffix")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    fun parse(discoveryUrl: String, document: String): ServerConfiguration {
        val payload = runCatching { json.decodeFromString<DiscoveryDocument>(document) }
            .getOrElse { throw ServerDiscoveryException(ServerDiscoveryFailure.INVALID_DOCUMENT, cause = it) }
        if (payload.version != 1) throw ServerDiscoveryException(ServerDiscoveryFailure.UNSUPPORTED_VERSION)
        if (!payload.service.equals("nuvio", ignoreCase = true)) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.WRONG_SERVICE)
        }
        if (!payload.selfHosted) throw ServerDiscoveryException(ServerDiscoveryFailure.NOT_SELF_HOSTED)
        val backendUrl = normalizeBackendUrl(payload.backendUrl)
        val publishableKey = payload.publishableKey.trim()
        if (publishableKey.isBlank()) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.MISSING_CONFIGURATION)
        }
        if (!payload.capabilities.emailPasswordAuth && !payload.capabilities.tvLogin) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.NO_SUPPORTED_AUTH)
        }
        return ServerConfiguration(
            backendUrl = backendUrl,
            publishableKey = publishableKey,
            capabilities = ServerCapabilities(
                emailPasswordAuth = payload.capabilities.emailPasswordAuth,
                tvLogin = payload.capabilities.tvLogin
            ),
            isCustom = true,
            discoveryUrl = discoveryUrl,
            tvLoginWebBaseUrl = "$backendUrl/tv-login",
            deviceLoginWebBaseUrl = "$backendUrl/link",
            avatarPublicBaseUrl = "$backendUrl/storage/v1/object/public/avatars"
        )
    }

    fun matchesBackend(candidateUrl: String, backendUrl: String): Boolean {
        val candidate = candidateUrl.toHttpUrlOrNull() ?: return false
        val backend = backendUrl.trim().toHttpUrlOrNull() ?: return false
        return candidate.host.equals(backend.host, ignoreCase = true) && candidate.port == backend.port
    }

    private fun normalizeBackendUrl(value: String): String {
        val parsed = value.trim().toHttpUrlOrNull()
            ?: throw ServerDiscoveryException(ServerDiscoveryFailure.MISSING_CONFIGURATION)
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            throw ServerDiscoveryException(ServerDiscoveryFailure.MISSING_CONFIGURATION)
        }
        if (
            parsed.username.isNotEmpty() ||
            parsed.password.isNotEmpty() ||
            parsed.query != null ||
            parsed.fragment != null
        ) {
            throw ServerDiscoveryException(ServerDiscoveryFailure.MISSING_CONFIGURATION)
        }
        return parsed.toString().trimEnd('/')
    }
}

@Singleton
class ServerDiscoveryService @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun discover(input: String): Result<ServerConfiguration> = withContext(Dispatchers.IO) {
        runCatching {
            val discoveryUrl = ServerDiscoveryPolicy.discoveryUrl(input)
            if (ServerDiscoveryPolicy.matchesBackend(discoveryUrl, BuildConfig.SUPABASE_URL)) {
                throw ServerDiscoveryException(ServerDiscoveryFailure.OFFICIAL_SERVER)
            }
            val request = Request.Builder()
                .url(discoveryUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME.ifBlank { "dev" }}")
                .get()
                .build()
            val response = try {
                client.newCall(request).execute()
            } catch (error: IOException) {
                throw ServerDiscoveryException(ServerDiscoveryFailure.CONNECTION_FAILED, cause = error)
            }
            response.use {
                if (!it.isSuccessful) {
                    throw ServerDiscoveryException(
                        failure = ServerDiscoveryFailure.HTTP_ERROR,
                        statusCode = it.code
                    )
                }
                if (
                    discoveryUrl.startsWith("https://", ignoreCase = true) &&
                    it.request.url.scheme != "https"
                ) {
                    throw ServerDiscoveryException(ServerDiscoveryFailure.CONNECTION_FAILED)
                }
                val body = it.body
                    ?: throw ServerDiscoveryException(ServerDiscoveryFailure.INVALID_DOCUMENT)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                body.byteStream().use { stream ->
                    while (output.size() <= MAX_DOCUMENT_BYTES) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
                if (output.size() > MAX_DOCUMENT_BYTES) {
                    throw ServerDiscoveryException(ServerDiscoveryFailure.RESPONSE_TOO_LARGE)
                }
                ServerDiscoveryPolicy.parse(discoveryUrl, output.toString(Charsets.UTF_8.name()))
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 64 * 1024
    }
}
