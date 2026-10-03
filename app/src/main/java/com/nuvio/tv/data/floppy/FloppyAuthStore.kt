package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A user's Floppy server and the API token they created for NuvioTV. The token is never printed. */
@Serializable
data class FloppyCredentials(
    val baseUrl: String,
    val token: String
) {
    override fun toString(): String = "FloppyCredentials(baseUrl=$baseUrl)"
}

data class FloppyAuthState(
    val profileId: Int = 1,
    val isConnected: Boolean = false,
    val baseUrl: String? = null
)

interface FloppyAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
}

/**
 * Per-profile Floppy credentials. A failed durable write never publishes a connection,
 * so the UI cannot show "Connected" for a token that was not actually saved.
 */
class FloppyAuthStore(
    private val persistence: FloppyAuthPersistence,
    initialProfileId: Int = 1
) : ProfileScopedCredentialStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private var profileId = initialProfileId
    private var stored: FloppyCredentials? = load(initialProfileId)
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<FloppyAuthState> = mutableState.asStateFlow()

    fun credentials(): FloppyCredentials? = synchronized(lock) { stored }

    fun selectProfile(newProfileId: Int) = synchronized(lock) {
        if (profileId == newProfileId) return@synchronized
        profileId = newProfileId
        stored = load(newProfileId)
        mutableState.value = stateFor()
    }

    fun save(credentials: FloppyCredentials) = synchronized(lock) {
        persistence.write(profileId, json.encodeToString(credentials))
        stored = credentials
        mutableState.value = stateFor()
    }

    fun disconnect() = synchronized(lock) {
        persistence.write(profileId, null)
        stored = null
        mutableState.value = stateFor()
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (this.profileId == profileId) {
            stored = null
            mutableState.value = stateFor()
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        stored = null
        mutableState.value = stateFor()
    }

    private fun load(profileId: Int): FloppyCredentials? = persistence.read(profileId)
        ?.let { value -> runCatching { json.decodeFromString<FloppyCredentials>(value) }.getOrNull() }
        ?.takeIf { it.token.isNotBlank() && normalizeFloppyBaseUrl(it.baseUrl) != null }

    private fun stateFor() = FloppyAuthState(
        profileId = profileId,
        isConnected = stored != null,
        baseUrl = stored?.baseUrl
    )
}

/**
 * Accepts what a person types for their server and returns the origin plus any reverse-proxy
 * path, without a trailing slash or an `/api/v1` suffix. A missing scheme means https.
 * Returns null for anything that is not a plain http(s) address.
 */
fun normalizeFloppyBaseUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    val path = url.pathSegments.filter(String::isNotEmpty)
        .let { segments ->
            when {
                segments.takeLast(2) == listOf("api", "v1") -> segments.dropLast(2)
                segments.lastOrNull() == "api" -> segments.dropLast(1)
                else -> segments
            }
        }
    val origin = buildString {
        append(url.scheme).append("://")
        append(if (':' in url.host) "[${url.host}]" else url.host)
        if (url.port != okhttp3.HttpUrl.defaultPort(url.scheme)) append(':').append(url.port)
    }
    return if (path.isEmpty()) origin else origin + path.joinToString(separator = "/", prefix = "/")
}
