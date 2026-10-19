package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.LiveTvSource
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Small profile-scoped source configuration only; channel and EPG payloads are never stored here. */
@Singleton
class LiveTvSourceStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    suspend fun list(): List<LiveTvSource> = runCatching {
        val raw = store().data.first()[sourcesKey].orEmpty()
        if (raw.length > MAX_CONFIG_CHARS) return@runCatching emptyList()
        json.decodeFromString<List<LiveTvSource>>(raw).take(MAX_SOURCES)
    }.getOrDefault(emptyList())

    suspend fun add(name: String, playlistUrl: String, epgUrl: String? = null): LiveTvSource {
        val source = LiveTvSource(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(MAX_NAME_CHARS).ifBlank { "Lista de canais" },
            playlistUrl = requireHttpUrl(playlistUrl),
            epgUrl = epgUrl?.trim()?.takeIf(String::isNotEmpty)?.let(::requireHttpUrl),
        )
        update { current ->
            require(current.size < MAX_SOURCES) { "Limite de fontes Live TV atingido" }
            current + source
        }
        return source
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        update { current -> current.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    suspend fun remove(id: String) {
        update { current -> current.filterNot { it.id == id } }
    }

    private suspend fun update(transform: (List<LiveTvSource>) -> List<LiveTvSource>) {
        store().edit { preferences ->
            val raw = preferences[sourcesKey].orEmpty()
            val current = if (raw.length <= MAX_CONFIG_CHARS) {
                runCatching { json.decodeFromString<List<LiveTvSource>>(raw) }.getOrDefault(emptyList())
            } else emptyList()
            val encoded = json.encodeToString(transform(current).take(MAX_SOURCES))
            require(encoded.length <= MAX_CONFIG_CHARS) { "Configuração Live TV excede o limite" }
            preferences[sourcesKey] = encoded
        }
    }

    private fun requireHttpUrl(raw: String): String {
        val value = raw.trim()
        val uri = runCatching { java.net.URI(value) }.getOrNull()
        require(value.length <= MAX_URL_CHARS && uri != null &&
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "Informe uma URL HTTP ou HTTPS válida sem credenciais embutidas"
        }
        return value
    }

    private companion object {
        const val FEATURE = "live_tv_sources"
        const val MAX_SOURCES = 20
        const val MAX_NAME_CHARS = 80
        const val MAX_URL_CHARS = 4_096
        const val MAX_CONFIG_CHARS = 64 * 1024
        val sourcesKey = stringPreferencesKey("sources_v1")
    }
}
