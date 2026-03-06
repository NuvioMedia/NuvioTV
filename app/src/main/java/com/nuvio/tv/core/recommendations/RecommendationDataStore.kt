package com.nuvio.tv.core.recommendations

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recommendationDataStore by preferencesDataStore(
    name = "tv_recommendation_prefs"
)

/**
 * Persists channel IDs created via [TvContractCompat] so they survive app restarts,
 * and stores the global "recommendations enabled" toggle.
 */
@Singleton
class RecommendationDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_RECOMMENDATIONS_ENABLED =
            booleanPreferencesKey("recommendations_enabled")
        
        // This stores a set of catalog keys (e.g. from Addon config) that the user wants to push to Android TV
        private val KEY_ENABLED_CATALOGS =
            androidx.datastore.preferences.core.stringSetPreferencesKey("enabled_tv_catalogs")
            
        private val KEY_SYNC_INTERVAL_HOURS = intPreferencesKey("sync_interval_hours")
        private val KEY_MAX_ITEMS_PER_CHANNEL = intPreferencesKey("max_items_per_channel")
        private val KEY_USE_WIDE_POSTER = booleanPreferencesKey("use_wide_poster")
        private val KEY_PLAY_NEXT_ENABLED = booleanPreferencesKey("play_next_enabled")
    }

    // ── Configuration ──
    
    val syncIntervalHoursFlow = context.recommendationDataStore.data.map {
        it[KEY_SYNC_INTERVAL_HOURS] ?: 3
    }

    val maxItemsPerChannelFlow = context.recommendationDataStore.data.map {
        it[KEY_MAX_ITEMS_PER_CHANNEL] ?: 25
    }

    val useWidePosterFlow = context.recommendationDataStore.data.map {
        it[KEY_USE_WIDE_POSTER] ?: false
    }

    val playNextEnabledFlow = context.recommendationDataStore.data.map {
        it[KEY_PLAY_NEXT_ENABLED] ?: true
    }

    suspend fun getSyncIntervalHours(): Int = syncIntervalHoursFlow.first()
    suspend fun getMaxItemsPerChannel(): Int = maxItemsPerChannelFlow.first()
    suspend fun getUseWidePoster(): Boolean = useWidePosterFlow.first()
    suspend fun getPlayNextEnabled(): Boolean = playNextEnabledFlow.first()

    suspend fun setSyncIntervalHours(hours: Int) {
        context.recommendationDataStore.edit { it[KEY_SYNC_INTERVAL_HOURS] = hours }
    }

    suspend fun setMaxItemsPerChannel(max: Int) {
        context.recommendationDataStore.edit { it[KEY_MAX_ITEMS_PER_CHANNEL] = max }
    }

    suspend fun setUseWidePoster(useWide: Boolean) {
        context.recommendationDataStore.edit { it[KEY_USE_WIDE_POSTER] = useWide }
    }

    suspend fun setPlayNextEnabled(enabled: Boolean) {
        context.recommendationDataStore.edit { it[KEY_PLAY_NEXT_ENABLED] = enabled }
    }

    // ── Enabled Catalogs ──

    val enabledCatalogsFlow = context.recommendationDataStore.data.map {
        it[KEY_ENABLED_CATALOGS] ?: emptySet()
    }

    suspend fun getEnabledCatalogs(): Set<String> = enabledCatalogsFlow.first()

    suspend fun setEnabledCatalogs(catalogs: Set<String>) {
        context.recommendationDataStore.edit {
            it[KEY_ENABLED_CATALOGS] = catalogs
        }
    }

    // ── Channel ID CRUD ──

    suspend fun getChannelId(channelType: String): Long? {
        val key = keyForType(channelType)
        return context.recommendationDataStore.data.map { it[key] }.first()
    }

    suspend fun setChannelId(channelType: String, channelId: Long) {
        val key = keyForType(channelType)
        context.recommendationDataStore.edit { it[key] = channelId }
    }

    suspend fun clearChannelId(channelType: String) {
        val key = keyForType(channelType)
        context.recommendationDataStore.edit { it.remove(key) }
    }

    // ── Global toggle ──

    val isEnabledFlow = context.recommendationDataStore.data.map {
        it[KEY_RECOMMENDATIONS_ENABLED] ?: true
    }

    suspend fun isEnabled(): Boolean = isEnabledFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.recommendationDataStore.edit {
            it[KEY_RECOMMENDATIONS_ENABLED] = enabled
        }
    }

    // ── Helpers ──

    private fun keyForType(channelType: String) = longPreferencesKey("channel_id_$channelType")
}
