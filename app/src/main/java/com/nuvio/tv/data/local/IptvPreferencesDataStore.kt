package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IptvPreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "iptv_settings"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private val playlistUrlKey = stringPreferencesKey("playlist_url")
    private val epgUrlKey = stringPreferencesKey("epg_url")

    val playlistUrl: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[playlistUrlKey] ?: "" }
    }

    val epgUrl: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[epgUrlKey] ?: "" }
    }

    suspend fun setPlaylistUrl(url: String) {
        store().edit { it[playlistUrlKey] = url.trim() }
    }

    suspend fun setEpgUrl(url: String) {
        store().edit { it[epgUrlKey] = url.trim() }
    }
}
