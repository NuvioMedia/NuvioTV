package com.nuvio.tv.data.simkl

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

interface SimklSyncStorage {
    suspend fun load(profileId: Int): String?
    suspend fun save(profileId: Int, payload: String)
    suspend fun remove(profileId: Int)
    suspend fun loadPlayback(profileId: Int): String? = null
    suspend fun savePlayback(profileId: Int, payload: String) {}
    suspend fun clearPlayback(profileId: Int) {}
}

@Singleton
class AndroidSimklSyncStorage @Inject constructor(
    private val factory: ProfileDataStoreFactory
) : SimklSyncStorage {
    override suspend fun load(profileId: Int): String? =
        factory.get(profileId, FEATURE_NAME).data.first()[SNAPSHOT_KEY]

    override suspend fun save(profileId: Int, payload: String) {
        factory.get(profileId, FEATURE_NAME).edit { preferences ->
            preferences[SNAPSHOT_KEY] = payload
            preferences.remove(PLAYBACK_KEY)
        }
        clearPlaybackBestEffort(profileId)
    }

    override suspend fun remove(profileId: Int) {
        factory.get(profileId, FEATURE_NAME).edit { preferences ->
            preferences.remove(SNAPSHOT_KEY)
            preferences.remove(PLAYBACK_KEY)
        }
        clearPlaybackBestEffort(profileId)
    }

    override suspend fun loadPlayback(profileId: Int): String? {
        val current = factory.get(profileId, PLAYBACK_FEATURE_NAME).data.first()[PLAYBACK_KEY]
        if (current != null) return current.ifEmpty { null }
        return factory.get(profileId, FEATURE_NAME).data.first()[PLAYBACK_KEY]
    }

    override suspend fun savePlayback(profileId: Int, payload: String) {
        factory.get(profileId, PLAYBACK_FEATURE_NAME).edit { preferences ->
            preferences[PLAYBACK_KEY] = payload
        }
    }

    override suspend fun clearPlayback(profileId: Int) {
        factory.get(profileId, PLAYBACK_FEATURE_NAME).edit { preferences ->
            preferences[PLAYBACK_KEY] = ""
        }
    }

    private suspend fun clearPlaybackBestEffort(profileId: Int) {
        try {
            clearPlayback(profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The new snapshot revision already makes the old playback override unusable.
        }
    }

    private companion object {
        const val FEATURE_NAME = "simkl_sync"
        const val PLAYBACK_FEATURE_NAME = "simkl_playback"
        val SNAPSHOT_KEY = stringPreferencesKey("snapshot")
        val PLAYBACK_KEY = stringPreferencesKey("playback")
    }
}
