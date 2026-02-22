package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.TmdbSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "tmdb_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("tmdb_enabled")
    private val languageKey = stringPreferencesKey("tmdb_language")
    private val useArtworkKey = booleanPreferencesKey("tmdb_use_artwork")
    private val useBasicInfoKey = booleanPreferencesKey("tmdb_use_basic_info")
    private val useDetailsKey = booleanPreferencesKey("tmdb_use_details")
    private val useCreditsKey = booleanPreferencesKey("tmdb_use_credits")
    private val useProductionsKey = booleanPreferencesKey("tmdb_use_productions")
    private val useNetworksKey = booleanPreferencesKey("tmdb_use_networks")
    private val useEpisodesKey = booleanPreferencesKey("tmdb_use_episodes")
    private val useMoreLikeThisKey = booleanPreferencesKey("tmdb_use_more_like_this")

    val settings: Flow<TmdbSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            TmdbSettings(
                enabled = prefs[enabledKey] ?: false,
                language = prefs[languageKey] ?: "en",
                useArtwork = prefs[useArtworkKey] ?: true,
                useBasicInfo = prefs[useBasicInfoKey] ?: true,
                useDetails = prefs[useDetailsKey] ?: true,
                useCredits = prefs[useCreditsKey] ?: true,
                useProductions = prefs[useProductionsKey] ?: true,
                useNetworks = prefs[useNetworksKey] ?: true,
                useEpisodes = prefs[useEpisodesKey] ?: true,
                useMoreLikeThis = prefs[useMoreLikeThisKey] ?: true
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setLanguage(language: String) {
        store().edit { it[languageKey] = language.ifBlank { "en" } }
    }

    suspend fun setUseArtwork(enabled: Boolean) {
        store().edit { it[useArtworkKey] = enabled }
    }

    suspend fun setUseBasicInfo(enabled: Boolean) {
        store().edit { it[useBasicInfoKey] = enabled }
    }

    suspend fun setUseDetails(enabled: Boolean) {
        store().edit { it[useDetailsKey] = enabled }
    }

    suspend fun setUseCredits(enabled: Boolean) {
        store().edit { it[useCreditsKey] = enabled }
    }

    suspend fun setUseProductions(enabled: Boolean) {
        store().edit { it[useProductionsKey] = enabled }
    }

    suspend fun setUseNetworks(enabled: Boolean) {
        store().edit { it[useNetworksKey] = enabled }
    }

    suspend fun setUseEpisodes(enabled: Boolean) {
        store().edit { it[useEpisodesKey] = enabled }
    }

    suspend fun setUseMoreLikeThis(enabled: Boolean) {
        store().edit { it[useMoreLikeThisKey] = enabled }
    }
}
