package com.nuvio.tv

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TestPreferencesStore(
    initial: Preferences = emptyPreferences()
) : DataStore<Preferences> {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    val value: Preferences
        get() = state.value

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences = mutex.withLock {
        transform(state.value).also { state.value = it }
    }
}
