package com.nuvio.tv.data.wetrakr

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

interface WeTrakrUploadMemory {
    suspend fun notFound(profileId: Int, now: Long): Set<String>
    suspend fun rememberNotFound(profileId: Int, keys: Collection<String>, now: Long)
}

internal fun mergeWeTrakrNotFound(stored: Map<String, Long>, keys: Collection<String>, now: Long): Map<String, Long> =
    stored.filterValues { now - it < WETRAKR_NOT_FOUND_RETRY_MS } + keys.associateWith { now }

internal const val WETRAKR_NOT_FOUND_RETRY_MS = 30L * 24 * 60 * 60 * 1000

@Singleton
class AndroidWeTrakrUploadMemory @Inject constructor(
    private val factory: ProfileDataStoreFactory
) : WeTrakrUploadMemory {
    override suspend fun notFound(profileId: Int, now: Long): Set<String> =
        decode(factory.get(profileId, FEATURE).data.first()[NOT_FOUND])
            .filterValues { now - it < WETRAKR_NOT_FOUND_RETRY_MS }
            .keys

    override suspend fun rememberNotFound(profileId: Int, keys: Collection<String>, now: Long) {
        if (keys.isEmpty() || factory.isProfileDeleted(profileId)) return
        factory.get(profileId, FEATURE).edit { preferences ->
            val merged = mergeWeTrakrNotFound(decode(preferences[NOT_FOUND]), keys, now)
            preferences[NOT_FOUND] = Json.encodeToString(SERIALIZER, merged)
        }
    }

    private fun decode(value: String?): Map<String, Long> =
        value?.let { runCatching { Json.decodeFromString(SERIALIZER, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val FEATURE = "wetrakr_upload"
        val NOT_FOUND = stringPreferencesKey("not_found")
        val SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
    }
}
