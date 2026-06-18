package com.nuvio.tv.core.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncBackendStorage @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSelectionPayload(): String? =
        preferences.getString(SELECTION_PAYLOAD_KEY, null)

    fun saveSelectionPayload(payload: String) {
        preferences.edit()
            .putString(SELECTION_PAYLOAD_KEY, payload)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "nuvio_sync_backend"
        const val SELECTION_PAYLOAD_KEY = "selection_payload_v1"
    }
}
