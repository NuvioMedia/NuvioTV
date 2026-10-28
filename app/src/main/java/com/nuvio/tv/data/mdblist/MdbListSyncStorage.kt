package com.nuvio.tv.data.mdblist

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

interface MdbListSyncStorage {
    suspend fun load(profileId: Int): String?
    suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit)
    suspend fun remove(profileId: Int, checkScope: () -> Unit)
    suspend fun loadPlayback(profileId: Int): String? = null
    suspend fun savePlayback(profileId: Int, payload: String, checkScope: () -> Unit) {}
    suspend fun clearPlayback(profileId: Int, checkScope: () -> Unit) {}
}

@Singleton
class AndroidMdbListSyncStorage @Inject constructor(
    private val factory: ProfileDataStoreFactory
) : MdbListSyncStorage {
    override suspend fun load(profileId: Int): String? =
        factory.get(profileId, FEATURE).data.first()[SNAPSHOT]

    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        factory.get(profileId, FEATURE).edit { preferences ->
            checkScope()
            if (factory.isProfileDeleted(profileId)) throw CancellationException("MDBList profile removed")
            preferences[SNAPSHOT] = payload
            preferences.remove(PLAYBACK)
        }
        clearPlaybackBestEffort(profileId, checkScope)
    }

    override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        factory.get(profileId, FEATURE).edit { preferences ->
            checkScope()
            preferences.remove(SNAPSHOT)
            preferences.remove(PLAYBACK)
        }
        clearPlaybackBestEffort(profileId, checkScope)
    }

    override suspend fun loadPlayback(profileId: Int): String? {
        val current = factory.get(profileId, PLAYBACK_FEATURE).data.first()[PLAYBACK]
        if (current != null) return current.ifEmpty { null }
        return factory.get(profileId, FEATURE).data.first()[PLAYBACK]
    }

    override suspend fun savePlayback(profileId: Int, payload: String, checkScope: () -> Unit) {
        factory.get(profileId, PLAYBACK_FEATURE).edit { preferences ->
            checkScope()
            if (factory.isProfileDeleted(profileId)) throw CancellationException("MDBList profile removed")
            preferences[PLAYBACK] = payload
        }
    }

    override suspend fun clearPlayback(profileId: Int, checkScope: () -> Unit) {
        factory.get(profileId, PLAYBACK_FEATURE).edit { preferences ->
            checkScope()
            preferences[PLAYBACK] = ""
        }
    }

    private suspend fun clearPlaybackBestEffort(profileId: Int, checkScope: () -> Unit) {
        try {
            clearPlayback(profileId, checkScope)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The new snapshot revision already makes the old playback override unusable.
        }
    }

    private companion object {
        const val FEATURE = "mdblist_sync"
        const val PLAYBACK_FEATURE = "mdblist_playback"
        val SNAPSHOT = stringPreferencesKey("snapshot")
        val PLAYBACK = stringPreferencesKey("playback")
    }
}
