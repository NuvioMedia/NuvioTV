package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LiveTvChannelState(
    val favoriteChannelIds: List<String> = emptyList(),
    val recentChannelIds: List<String> = emptyList(),
    val timeZoneId: String = "system",
)

/** Keeps small, profile-specific channel preferences; stream URLs and EPG data stay elsewhere. */
@Singleton
class LiveTvChannelStateStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    suspend fun read(): LiveTvChannelState = try {
        val raw = store().data.first()[stateKey].orEmpty()
        if (raw.length > MAX_STATE_CHARS) LiveTvChannelState()
        else normalize(json.decodeFromString<LiveTvChannelState>(raw))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LiveTvChannelState()
    }

    suspend fun toggleFavorite(channelId: String): LiveTvChannelState {
        validateId(channelId)
        return update { current ->
            if (channelId in current.favoriteChannelIds) {
                current.copy(favoriteChannelIds = current.favoriteChannelIds - channelId)
            } else {
                require(current.favoriteChannelIds.size < MAX_FAVORITES) { "Limite de canais favoritos atingido" }
                current.copy(favoriteChannelIds = current.favoriteChannelIds + channelId)
            }
        }
    }

    suspend fun recordRecent(channelId: String): LiveTvChannelState {
        validateId(channelId)
        return update { current ->
            current.copy(recentChannelIds = (listOf(channelId) + current.recentChannelIds.filterNot { it == channelId })
                .take(MAX_RECENTS))
        }
    }

    suspend fun removeSource(sourceId: String): LiveTvChannelState = update { current ->
        val prefix = "$sourceId:"
        current.copy(
            favoriteChannelIds = current.favoriteChannelIds.filterNot { it.startsWith(prefix) },
            recentChannelIds = current.recentChannelIds.filterNot { it.startsWith(prefix) },
        )
    }

    suspend fun setTimeZone(timeZoneId: String): LiveTvChannelState {
        require(timeZoneId in SUPPORTED_TIME_ZONE_IDS) { "Fuso horário não suportado" }
        return update { it.copy(timeZoneId = timeZoneId) }
    }

    private suspend fun update(transform: (LiveTvChannelState) -> LiveTvChannelState): LiveTvChannelState {
        var result = LiveTvChannelState()
        store().edit { preferences ->
            val raw = preferences[stateKey].orEmpty()
            val current = if (raw.length <= MAX_STATE_CHARS) {
                runCatching { json.decodeFromString<LiveTvChannelState>(raw) }.getOrDefault(LiveTvChannelState())
            } else LiveTvChannelState()
            result = normalize(transform(normalize(current)))
            val encoded = json.encodeToString(result)
            require(encoded.length <= MAX_STATE_CHARS) { "Estado de canais excede o limite permitido" }
            preferences[stateKey] = encoded
        }
        return result
    }

    private fun normalize(value: LiveTvChannelState) = LiveTvChannelState(
        favoriteChannelIds = value.favoriteChannelIds.asSequence().filter(::isValidId).distinct().take(MAX_FAVORITES).toList(),
        recentChannelIds = value.recentChannelIds.asSequence().filter(::isValidId).distinct().take(MAX_RECENTS).toList(),
        timeZoneId = value.timeZoneId.takeIf { it in SUPPORTED_TIME_ZONE_IDS } ?: DEFAULT_TIME_ZONE_ID,
    )

    private fun validateId(value: String) {
        require(isValidId(value)) { "Identificador de canal inválido" }
    }

    private fun isValidId(value: String) = value.isNotBlank() && value.length <= MAX_ID_CHARS

    companion object {
        const val FEATURE = "live_tv_channel_state"
        const val MAX_FAVORITES = 2_000
        const val MAX_RECENTS = 30
        const val MAX_ID_CHARS = 512
        const val MAX_STATE_CHARS = 256 * 1024
        const val DEFAULT_TIME_ZONE_ID = "system"
        val SUPPORTED_TIME_ZONE_IDS = setOf(
            DEFAULT_TIME_ZONE_ID,
            "America/Sao_Paulo",
            "America/Manaus",
            "America/Rio_Branco",
            "America/Noronha",
            "UTC",
        )
        val stateKey = stringPreferencesKey("channel_state_v1")
    }
}
