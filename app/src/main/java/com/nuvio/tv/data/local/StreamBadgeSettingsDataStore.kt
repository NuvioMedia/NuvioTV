package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeGroup
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.core.streams.StreamBadgeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamBadgeSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    companion object {
        const val FEATURE = "stream_badge_settings"
        private const val LEGACY_DEBRID_FEATURE = "debrid_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private fun legacyDebridStore(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, LEGACY_DEBRID_FEATURE)

    private val streamBadgeRulesKey = stringPreferencesKey("stream_badge_rules")
    private val showFileSizeBadgesKey = booleanPreferencesKey("show_file_size_badges")
    private val legacyDebridStreamBadgeRulesKey = stringPreferencesKey("debrid_stream_badge_rules")

    val settings: Flow<StreamBadgeSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.onStart { migrateProfile(pid) }
    }.map { prefs ->
        StreamBadgeSettings(
            rules = parseStreamBadgeRules(prefs[streamBadgeRulesKey]) ?: StreamBadgeRules(),
            showFileSizeBadges = prefs[showFileSizeBadgesKey] ?: true
        )
    }

    suspend fun setStreamBadgeRules(rules: StreamBadgeRules) {
        store().edit {
            val normalized = rules.normalized()
            if (normalized.hasImport) {
                it[streamBadgeRulesKey] = json.encodeToString(normalized)
            } else {
                it.remove(streamBadgeRulesKey)
            }
        }
    }

    suspend fun setShowFileSizeBadges(enabled: Boolean) {
        store().edit { it[showFileSizeBadgesKey] = enabled }
    }

    suspend fun setSettings(settings: StreamBadgeSettings) {
        store().edit {
            val normalized = settings.rules.normalized()
            if (normalized.hasImport) {
                it[streamBadgeRulesKey] = json.encodeToString(normalized)
            } else {
                it.remove(streamBadgeRulesKey)
            }
            it[showFileSizeBadgesKey] = settings.showFileSizeBadges
        }
    }

    private suspend fun migrateProfile(profileId: Int) {
        val currentPrefs = store(profileId).data.first()
        if (currentPrefs[streamBadgeRulesKey] != null) return
        val legacyPrefs = legacyDebridStore(profileId).data.first()
        val legacyRules = parseStreamBadgeRules(legacyPrefs[legacyDebridStreamBadgeRulesKey]) ?: return
        store(profileId).edit {
            if (it[streamBadgeRulesKey] == null) {
                it[streamBadgeRulesKey] = json.encodeToString(legacyRules.normalized())
            }
        }
        legacyDebridStore(profileId).edit { it.remove(legacyDebridStreamBadgeRulesKey) }
    }

    private fun parseStreamBadgeRules(value: String?): StreamBadgeRules? {
        if (value.isNullOrBlank()) return null
        val decodedRules = try {
            json.decodeFromString<StreamBadgeRules>(value).normalized()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (decodedRules?.hasImport == true) return decodedRules

        val legacyRules = try {
            json.decodeFromString<LegacyStreamBadgeRules>(value)
                .toBadgeRules()
                .normalized()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return legacyRules?.takeIf { it.hasImport } ?: decodedRules
    }
}

@Serializable
private data class LegacyStreamBadgeRules(
    val sourceUrl: String = "",
    val filters: List<StreamBadgeFilter> = emptyList(),
    val groups: List<StreamBadgeGroup> = emptyList()
) {
    fun toBadgeRules(): StreamBadgeRules =
        StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = sourceUrl.ifBlank { "Local badge rules" },
                    filters = filters,
                    groups = groups
                )
            )
        )
}
