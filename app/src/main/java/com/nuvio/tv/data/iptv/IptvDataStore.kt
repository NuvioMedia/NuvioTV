package com.nuvio.tv.data.iptv

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class IptvDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "iptv"
        private const val MAX_RECENTS = 30
    }

    private val playlistsKey = stringPreferencesKey("playlists")
    private val favoritesKey = stringSetPreferencesKey("favorites")
    private val recentsKey = stringPreferencesKey("recents")
    private val lastChannelKey = stringPreferencesKey("last_channel")
    private val lastGroupKey = stringPreferencesKey("last_group")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val playlists: Flow<List<IptvPlaylist>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs -> decodePlaylists(prefs[playlistsKey]) }
    }

    val favorites: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs -> prefs[favoritesKey].orEmpty() }
    }

    val recents: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs -> decodeStringList(prefs[recentsKey]) }
    }

    val lastChannelKeyFlow: Flow<String?> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs -> prefs[lastChannelKey] }
    }

    val lastGroupFlow: Flow<String?> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { prefs -> prefs[lastGroupKey] }
    }

    suspend fun upsertPlaylist(playlist: IptvPlaylist) {
        store().edit { prefs ->
            val current = decodePlaylists(prefs[playlistsKey])
            val updated = if (current.any { it.id == playlist.id }) {
                current.map { if (it.id == playlist.id) playlist else it }
            } else {
                current + playlist
            }
            prefs[playlistsKey] = encodePlaylists(updated)
        }
    }

    suspend fun removePlaylist(playlistId: String) {
        store().edit { prefs ->
            val updated = decodePlaylists(prefs[playlistsKey]).filterNot { it.id == playlistId }
            prefs[playlistsKey] = encodePlaylists(updated)
            val prefix = "$playlistId|"
            prefs[favoritesKey] = prefs[favoritesKey].orEmpty().filterNot { it.startsWith(prefix) }.toSet()
            prefs[recentsKey] = encodeStringList(decodeStringList(prefs[recentsKey]).filterNot { it.startsWith(prefix) })
        }
    }

    suspend fun toggleFavorite(channelKey: String) {
        store().edit { prefs ->
            val current = prefs[favoritesKey].orEmpty()
            prefs[favoritesKey] = if (channelKey in current) current - channelKey else current + channelKey
        }
    }

    suspend fun recordWatched(channelKey: String) {
        store().edit { prefs ->
            val updated = buildList {
                add(channelKey)
                addAll(decodeStringList(prefs[recentsKey]).filterNot { it == channelKey })
            }.take(MAX_RECENTS)
            prefs[recentsKey] = encodeStringList(updated)
            prefs[lastChannelKey] = channelKey
        }
    }

    suspend fun setLastChannel(channelKey: String) {
        store().edit { prefs -> prefs[lastChannelKey] = channelKey }
    }

    suspend fun setLastGroup(groupId: String) {
        store().edit { prefs -> prefs[lastGroupKey] = groupId }
    }

    private fun encodePlaylists(list: List<IptvPlaylist>): String {
        val array = JSONArray()
        list.forEach { playlist ->
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("type", playlist.type.name)
                    .put("url", playlist.url)
                    .put("username", playlist.username)
                    .put("password", playlist.password)
                    .put("epgUrl", playlist.epgUrl)
                    .put("userAgent", playlist.userAgent)
                    .put("enabled", playlist.enabled)
            )
        }
        return array.toString()
    }

    private fun decodePlaylists(raw: String?): List<IptvPlaylist> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                IptvPlaylist(
                    id = id,
                    name = item.optString("name"),
                    type = runCatching { IptvSourceType.valueOf(item.optString("type")) }.getOrDefault(IptvSourceType.M3U),
                    url = item.optString("url"),
                    username = item.optString("username"),
                    password = item.optString("password"),
                    epgUrl = item.optString("epgUrl"),
                    userAgent = item.optString("userAgent"),
                    enabled = item.optBoolean("enabled", true)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeStringList(list: List<String>): String = JSONArray(list).toString()

    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotEmpty() } }
        }.getOrDefault(emptyList())
    }
}
