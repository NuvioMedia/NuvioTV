package com.nuvio.tv.core.torrent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [DataStore] used by unit tests to avoid file I/O and Windows
 * file-lock flakiness. Mutations are serialized with a [Mutex] to mirror the
 * behavior of the real file-backed DataStore.
 */
class InMemoryPreferencesDataStore(
    initial: Preferences = emptyPreferences()
) : DataStore<Preferences> {

    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    fun set(key: Preferences.Key<String>, value: String) {
        state.value = state.value.toMutablePreferences().apply {
            this[key] = value
        }.toPreferences()
    }

    fun remove(key: Preferences.Key<String>) {
        state.value = state.value.toMutablePreferences().apply {
            remove(key)
        }.toPreferences()
    }

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        return mutex.withLock {
            state.value = transform(state.value)
            state.value
        }
    }
}