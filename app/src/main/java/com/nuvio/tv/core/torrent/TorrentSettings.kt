package com.nuvio.tv.core.torrent

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

private val Context.torrentDataStore by preferencesDataStore(name = "torrent_settings")

data class TorrentSettingsData(
    val p2pEnabled: Boolean = false,
    val enableUpload: Boolean = true,
    val hideTorrentStats: Boolean = true
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
    }

    val settings: Flow<TorrentSettingsData> = context.torrentDataStore.data.map { prefs ->
        TorrentSettingsData(
            p2pEnabled = prefs[Keys.P2P_ENABLED] ?: false,
            enableUpload = prefs[Keys.ENABLE_UPLOAD] ?: true,
            hideTorrentStats = prefs[Keys.HIDE_TORRENT_STATS] ?: true
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
}
