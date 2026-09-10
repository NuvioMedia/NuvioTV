package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.TmdbSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TmdbSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "tmdb_settings"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    /**
     * Falls back to the app's current UI language (e.g. "it", "es") when the user has never
     * explicitly set a TMDB language, rather than always defaulting to English - passed through
     * as-is to TmdbMetadataService.normalizeTmdbLanguage(), which already knows how to turn a
     * bare tag (or region-qualified one like "es-419") into what the TMDB API expects.
     */
    private fun defaultLanguageFromAppLocale(): String {
        val appTag = LocaleCache.localeTag
        return appTag.takeIf { it.isNotBlank() && it != LocaleCache.UNSET }
            ?: Locale.getDefault().language.takeIf { it.isNotBlank() }
            ?: "en"
    }

    private val enabledKey = booleanPreferencesKey("tmdb_enabled")
    private val modernHomeEnabledKey = booleanPreferencesKey("tmdb_modern_home_enabled")
    private val enrichContinueWatchingKey = booleanPreferencesKey("tmdb_enrich_continue_watching")
    private val languageKey = stringPreferencesKey("tmdb_language")
    private val useArtworkKey = booleanPreferencesKey("tmdb_use_artwork")
    private val useBasicInfoKey = booleanPreferencesKey("tmdb_use_basic_info")
    private val useDetailsKey = booleanPreferencesKey("tmdb_use_details")
    private val useReleaseDatesKey = booleanPreferencesKey("tmdb_use_release_dates")
    private val useCreditsKey = booleanPreferencesKey("tmdb_use_credits")
    private val useProductionsKey = booleanPreferencesKey("tmdb_use_productions")
    private val useNetworksKey = booleanPreferencesKey("tmdb_use_networks")
    private val useEpisodesKey = booleanPreferencesKey("tmdb_use_episodes")
    private val useTrailersKey = booleanPreferencesKey("tmdb_use_trailers")
    private val useMoreLikeThisKey = booleanPreferencesKey("tmdb_use_more_like_this")
    private val useCollectionsKey = booleanPreferencesKey("tmdb_use_collections")

    val settings: StateFlow<TmdbSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        // Combined with LocaleCache.localeTagFlow (not just .map on the DataStore alone) so that
        // when the user has never explicitly picked a TMDB language, it follows the app's UI
        // language live - including immediately after a language change, not just on next cold
        // start. An explicit tmdb_language value (set via TMDB Settings) always wins.
        factory.get(pid, FEATURE).data.combine(LocaleCache.localeTagFlow) { prefs, _ ->
            TmdbSettings(
                enabled = prefs[enabledKey] ?: false,
                modernHomeEnabled = prefs[modernHomeEnabledKey] ?: false,
                enrichContinueWatching = prefs[enrichContinueWatchingKey] ?: true,
                language = prefs[languageKey] ?: defaultLanguageFromAppLocale(),
                useArtwork = prefs[useArtworkKey] ?: true,
                useBasicInfo = prefs[useBasicInfoKey] ?: true,
                useDetails = prefs[useDetailsKey] ?: true,
                useReleaseDates = prefs[useReleaseDatesKey] ?: false,
                useCredits = prefs[useCreditsKey] ?: true,
                useProductions = prefs[useProductionsKey] ?: true,
                useNetworks = prefs[useNetworksKey] ?: true,
                useEpisodes = prefs[useEpisodesKey] ?: true,
                useTrailers = prefs[useTrailersKey] ?: true,
                useMoreLikeThis = prefs[useMoreLikeThisKey] ?: true,
                useCollections = prefs[useCollectionsKey] ?: true
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, TmdbSettings())

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setModernHomeEnabled(enabled: Boolean) {
        store().edit { it[modernHomeEnabledKey] = enabled }
    }

    suspend fun setEnrichContinueWatching(enabled: Boolean) {
        store().edit { it[enrichContinueWatchingKey] = enabled }
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

    suspend fun setUseReleaseDates(enabled: Boolean) {
        store().edit { it[useReleaseDatesKey] = enabled }
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

    suspend fun setUseTrailers(enabled: Boolean) {
        store().edit { it[useTrailersKey] = enabled }
    }

    suspend fun setUseMoreLikeThis(enabled: Boolean) {
        store().edit { it[useMoreLikeThisKey] = enabled }
    }

    suspend fun setUseCollections(enabled: Boolean) {
        store().edit { it[useCollectionsKey] = enabled }
    }
}
