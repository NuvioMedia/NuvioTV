package com.nuvio.tv.data.wetrakr

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

interface WeTrakrSyncStorage {
    suspend fun load(profileId: Int): String?
    suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit)
    suspend fun remove(profileId: Int, checkScope: () -> Unit)
}

@Singleton
class AndroidWeTrakrSyncStorage @Inject constructor(
    private val factory: ProfileDataStoreFactory
) : WeTrakrSyncStorage {
    override suspend fun load(profileId: Int): String? =
        factory.get(profileId, FEATURE).data.first()[SNAPSHOT]

    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        factory.get(profileId, FEATURE).edit { preferences ->
            checkScope()
            if (factory.isProfileDeleted(profileId)) throw CancellationException("WeTrakr profile removed")
            preferences[SNAPSHOT] = payload
        }
    }

    override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        factory.get(profileId, FEATURE).edit { preferences ->
            checkScope()
            preferences.remove(SNAPSHOT)
        }
    }

    private companion object {
        const val FEATURE = "wetrakr_sync"
        val SNAPSHOT = stringPreferencesKey("snapshot")
    }
}
