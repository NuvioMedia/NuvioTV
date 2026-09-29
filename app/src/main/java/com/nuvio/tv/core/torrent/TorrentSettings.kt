package com.nuvio.tv.core.torrent

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.torrentDataStore by preferencesDataStore(
    name = "torrent_settings",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

enum class TorrentProfile {
    SOFT,
    BALANCED,
    FAST
}

enum class TorrentCacheSize(val bytes: Long) {
    NONE(0L),
    GB_2(2L * 1024L * 1024L * 1024L),
    GB_5(5L * 1024L * 1024L * 1024L),
    GB_10(10L * 1024L * 1024L * 1024L)
}

data class TorrentSettingsData(
    val p2pEnabled: Boolean = false,
    val enableUpload: Boolean = true,
    val hideTorrentStats: Boolean = true,
    val torrentProfile: TorrentProfile = TorrentProfile.BALANCED,
    val cacheSize: TorrentCacheSize = TorrentCacheSize.GB_2
)

@Singleton
class TorrentSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val P2P_ENABLED = booleanPreferencesKey("p2p_enabled")
        val ENABLE_UPLOAD = booleanPreferencesKey("enable_upload")
        val HIDE_TORRENT_STATS = booleanPreferencesKey("hide_torrent_stats")
        val TORRENT_PROFILE = stringPreferencesKey("torrent_profile")
        val CACHE_SIZE = stringPreferencesKey("cache_size")
    }

    val settings: Flow<TorrentSettingsData> = context.torrentDataStore.data.map { prefs ->
        TorrentSettingsData(
            p2pEnabled = prefs[Keys.P2P_ENABLED] ?: false,
            enableUpload = prefs[Keys.ENABLE_UPLOAD] ?: true,
            hideTorrentStats = prefs[Keys.HIDE_TORRENT_STATS] ?: true,
            torrentProfile = prefs[Keys.TORRENT_PROFILE]
                ?.let { stored -> TorrentProfile.entries.firstOrNull { it.name == stored } }
                ?: TorrentProfile.BALANCED,
            cacheSize = prefs[Keys.CACHE_SIZE]
                ?.let { stored -> TorrentCacheSize.entries.firstOrNull { it.name == stored } }
                ?: TorrentCacheSize.GB_2
        )
    }

    fun setP2pEnabled(enabled: Boolean) {
        scope.launch {
            context.torrentDataStore.edit { it[Keys.P2P_ENABLED] = enabled }
        }
    }

    fun setEnableUpload(enabled: Boolean) {
        scope.launch {
            context.torrentDataStore.edit { it[Keys.ENABLE_UPLOAD] = enabled }
        }
    }

    fun setHideTorrentStats(enabled: Boolean) {
        scope.launch {
            context.torrentDataStore.edit { it[Keys.HIDE_TORRENT_STATS] = enabled }
        }
    }

    fun setTorrentProfile(profile: TorrentProfile) {
        scope.launch {
            context.torrentDataStore.edit { it[Keys.TORRENT_PROFILE] = profile.name }
        }
    }

    fun setCacheSize(size: TorrentCacheSize) {
        scope.launch {
            context.torrentDataStore.edit { it[Keys.CACHE_SIZE] = size.name }
        }
    }
}
