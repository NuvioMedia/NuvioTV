package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.MDBListSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MDBListSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "mdblist_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("mdblist_enabled")
    private val apiKeyKey = stringPreferencesKey("mdblist_api_key")
    private val showTraktKey = booleanPreferencesKey("mdblist_show_trakt")
    private val showImdbKey = booleanPreferencesKey("mdblist_show_imdb")
    private val showTmdbKey = booleanPreferencesKey("mdblist_show_tmdb")
    private val showLetterboxdKey = booleanPreferencesKey("mdblist_show_letterboxd")
    private val showTomatoesKey = booleanPreferencesKey("mdblist_show_tomatoes")
    private val showAudienceKey = booleanPreferencesKey("mdblist_show_audience")
    private val showMetacriticKey = booleanPreferencesKey("mdblist_show_metacritic")

    val settings: Flow<MDBListSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            MDBListSettings(
                enabled = prefs[enabledKey] ?: false,
                apiKey = prefs[apiKeyKey] ?: "",
                showTrakt = prefs[showTraktKey] ?: true,
                showImdb = prefs[showImdbKey] ?: true,
                showTmdb = prefs[showTmdbKey] ?: true,
                showLetterboxd = prefs[showLetterboxdKey] ?: true,
                showTomatoes = prefs[showTomatoesKey] ?: true,
                showAudience = prefs[showAudienceKey] ?: true,
                showMetacritic = prefs[showMetacriticKey] ?: true
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setApiKey(apiKey: String) {
        store().edit { it[apiKeyKey] = apiKey.trim() }
    }

    suspend fun setShowTrakt(enabled: Boolean) {
        store().edit { it[showTraktKey] = enabled }
    }

    suspend fun setShowImdb(enabled: Boolean) {
        store().edit { it[showImdbKey] = enabled }
    }

    suspend fun setShowTmdb(enabled: Boolean) {
        store().edit { it[showTmdbKey] = enabled }
    }

    suspend fun setShowLetterboxd(enabled: Boolean) {
        store().edit { it[showLetterboxdKey] = enabled }
    }

    suspend fun setShowTomatoes(enabled: Boolean) {
        store().edit { it[showTomatoesKey] = enabled }
    }

    suspend fun setShowAudience(enabled: Boolean) {
        store().edit { it[showAudienceKey] = enabled }
    }

    suspend fun setShowMetacritic(enabled: Boolean) {
        store().edit { it[showMetacriticKey] = enabled }
    }
}
