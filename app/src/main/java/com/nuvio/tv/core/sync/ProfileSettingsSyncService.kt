package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseProfileSettingsBlob
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.repository.MetaRepository
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSettingsSyncService"
private const val SETTINGS_PUSH_DEBOUNCE_MS = 1500L
private const val FOREGROUND_PULL_DELAY_MS = 2500L
private const val FOREGROUND_PULL_MIN_INTERVAL_MS = 60_000L
private const val SETTINGS_SYNC_PLATFORM = "tv"
private const val PLAYER_SETTINGS_FEATURE = "player_settings"

private val catalogKeysExcludedFromProfileSettingsBlob = setOf(
    "home_catalog_order_keys",
    "disabled_home_catalog_keys",
    "custom_catalog_titles"
)

private val localOnlyLayoutProfileSettingsKeys = setOf(
    "last_non_off_discover_location"
)

private val localOnlyPlayerProfileSettingsKeys = setOf(
    "player_preference",
    "internal_player_engine",
    "auto_switch_internal_player_on_error",
    "use_libass",
    "libass_render_type",
    "decoder_priority",
    "downmix_enabled",
    "audio_output_channels",
    "maintain_original_audio_on_downmix",
    "downmix_normalization_enabled",
    "tunneling_enabled",
    "audio_amplification_db",
    "center_mix_level_db",
    "persist_audio_amplification",
    "remember_audio_delay_per_device",
    "experimental_dv5_to_dv81_enabled",
    "experimental_dv7_to_dv81_preserve_mapping_enabled",
    "dv7_handling_mode",
    "map_dv7_to_hevc",
    "dv7_libdovi_mode_override",
    "mpv_hardware_decode_mode",
    "frame_rate_matching",
    "frame_rate_matching_mode",
    "resolution_matching_enabled",
    "external_player_forward_subtitles",
    "external_player_send_skip_segments",
    "vod_cache_enabled",
    "vod_cache_size_mode",
    "vod_cache_size_mb",
    "use_parallel_connections",
    "buffer_engine_enabled",
    "parallel_network_enabled",
    "allow_large_target_buffer",
    "buffer_budget_managed",
    "parallel_connection_count",
    "parallel_chunk_size_mb",
    "last_playback_diagnostics_json",
    "enable_buffer_logs",
    "resize_mode",
    "min_buffer_ms",
    "max_buffer_ms",
    "buffer_for_playback_ms",
    "buffer_for_playback_after_rebuffer_ms",
    "target_buffer_size_mb",
    "back_buffer_duration_ms",
    "retain_back_buffer_from_keyframe",
    "migration_load_control_defaults_aligned_done",
    "migration_load_control_defaults_retuned_done",
    "migration_load_control_min_buffer_retuned_done",
    "migration_vod_cache_split_done",
    "migration_back_buffer_duration_bumped_done",
    "migration_max_buffer_bumped_done",
    "migration_target_buffer_size_bumped_done",
    "migration_after_rebuffer_lowered_done",
    "migration_back_buffer_duration_reduced_done",
    "migration_target_buffer_size_reduced_done",
    "nuvio_performance_mode_enabled"
)

internal fun shouldExcludePreferenceFromProfileSettingsSync(feature: String, keyName: String): Boolean {
    return when {
        feature == "layout_settings" && keyName in catalogKeysExcludedFromProfileSettingsBlob -> true
        feature == "layout_settings" && keyName in localOnlyLayoutProfileSettingsKeys -> true
        feature == "layout_settings" && keyName == "search_discover_enabled" -> true
        feature == PLAYER_SETTINGS_FEATURE && keyName in localOnlyPlayerProfileSettingsKeys -> true
        else -> false
    }
}

@Singleton
class ProfileSettingsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager,
    private val profileDataStoreFactory: ProfileDataStoreFactory,
    private val syncClientIdentity: SyncClientIdentity,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val metaRepository: MetaRepository,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    @Volatile
    private var applyingRemoteBlob: Boolean = false

    @Volatile
    private var skipNextPushSignature: String? = null
    private var foregroundPullJob: Job? = null
    private var lastForegroundPullAtMs: Long = 0L

    private val syncedFeatures = listOf(
        "theme_settings",
        "layout_settings",
        ExperienceModeDataStore.FEATURE,
        PLAYER_SETTINGS_FEATURE,
        StreamBadgeSettingsDataStore.FEATURE,
        "trailer_settings",
        "tmdb_settings",
        "mdblist_settings",
        "trakt_settings",
        "debrid_settings",
        "animeskip_settings",
        "track_preference"
    )

    init {
        observeLocalSettingsChangesAndSync()
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun pushCurrentProfileToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val profileId = profileManager.activeProfileId.value
                val settingsJson = exportSettingsBlob(profileId)

                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_settings_json", settingsJson)
                    put("p_platform", SETTINGS_SYNC_PLATFORM)
                    putSyncOriginClientId(syncClientIdentity)
                }

                withJwtRefreshRetry {
                    postgrest.rpc("sync_push_profile_settings_blob", params)
                }

                Log.d(TAG, "Pushed profile settings blob for profile $profileId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push profile settings blob", e)
                Result.failure(e)
            }
        }
    }

    suspend fun pullCurrentProfileFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val profileId = profileManager.activeProfileId.value
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_platform", SETTINGS_SYNC_PLATFORM)
                }

                val response = withJwtRefreshRetry {
                    postgrest.rpc("sync_pull_profile_settings_blob", params)
                }
                lastForegroundPullAtMs = SystemClock.elapsedRealtime()
                val rows = response.decodeList<SupabaseProfileSettingsBlob>()
                val blob = rows.firstOrNull()?.settingsJson
                if (blob == null) {
                    Log.d(TAG, "No remote profile settings blob for profile $profileId; keeping local settings")
                    return@withLock Result.success(false)
                }

                val featuresJson = blob["features"]?.jsonObject ?: return@withLock Result.success(false)
                val remoteSignature = buildSettingsSignature(featuresJson)
                val localSignature = buildSettingsSignature(profileId)
                if (remoteSignature == localSignature) {
                    Log.d(TAG, "Remote profile settings already match local for profile $profileId")
                    return@withLock Result.success(false)
                }

                val previousUseReleaseDates = tmdbSettingsDataStore.settings.first().useReleaseDates
                importSettingsBlob(profileId, featuresJson)
                val currentUseReleaseDates = tmdbSettingsDataStore.settings.first().useReleaseDates
                if (previousUseReleaseDates != currentUseReleaseDates) {
                    metaRepository.clearCache()
                    cwEnrichmentCache.clearAll()
                }
                skipNextPushSignature = remoteSignature
                Log.d(TAG, "Applied remote profile settings blob for profile $profileId")
                Result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pull profile settings blob", e)
                Result.failure(e)
            }
        }
    }

    fun requestForegroundPull(force: Boolean = false) {
        if (!authManager.isAuthenticated) return

        val now = SystemClock.elapsedRealtime()
        if (!force && foregroundPullJob?.isActive == true) return
        if (!force && now - lastForegroundPullAtMs < FOREGROUND_PULL_MIN_INTERVAL_MS) return

        foregroundPullJob = scope.launch {
            if (!force) {
                delay(FOREGROUND_PULL_DELAY_MS)
            }
            if (!authManager.isAuthenticated) return@launch

            lastForegroundPullAtMs = SystemClock.elapsedRealtime()
            pullCurrentProfileFromRemote()
        }
    }

    private suspend fun exportSettingsBlob(profileId: Int): JsonObject {
        val features = buildJsonObject {
            syncedFeatures.forEach { feature ->
                val prefs = profileDataStoreFactory.get(profileId, feature).data.first()
                val serialized = buildJsonObject {
                    prefs.asMap().forEach { (key, rawValue) ->
                        if (shouldExcludePreferenceFromProfileSettingsSync(feature, key.name)) return@forEach
                        val encoded = encodePreferenceValue(rawValue) ?: return@forEach
                        put(key.name, encoded)
                    }
                }
                put(feature, serialized)
            }
        }

        return buildJsonObject {
            put("version", 1)
            put("features", features)
        }
    }

    private suspend fun importSettingsBlob(profileId: Int, featuresJson: JsonObject) {
        applyingRemoteBlob = true
        try {
            syncedFeatures.forEach { feature ->
                val featureJson = featuresJson[feature]?.jsonObject ?: return@forEach
                profileDataStoreFactory.get(profileId, feature).edit { mutablePrefs ->
                    val preservedEntries = captureLocalOnlyPreferenceEntries(feature, mutablePrefs)
                    val priorDiscoverLocation = if (feature == "layout_settings") {
                        mutablePrefs[stringPreferencesKey("discover_location")]
                    } else null
                    val priorLastNonOff = if (feature == "layout_settings") {
                        mutablePrefs[stringPreferencesKey("last_non_off_discover_location")]?.let {
                            runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
                        }?.takeIf { it != DiscoverLocation.OFF }
                    } else null

                    mutablePrefs.clear()
                    val hasWellFormedNewDiscoverKey = feature == "layout_settings" &&
                        extractDiscoverLocationString(featureJson) != null
                    featureJson.forEach { (keyName, encodedValue) ->
                        if (shouldExcludePreferenceFromProfileSettingsSync(feature, keyName)) {
                            if (feature != "layout_settings" || keyName != "search_discover_enabled") return@forEach
                            if (!hasWellFormedNewDiscoverKey) {
                                val legacy = (encodedValue as? JsonObject)
                                    ?.get("value")?.jsonPrimitive?.contentOrNull
                                    ?.toBooleanStrictOrNull()
                                if (legacy != null) {
                                    val translated = DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy)
                                    val priorLocation = priorDiscoverLocation?.let {
                                        runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
                                    }
                                    val priorIsValidNonOff = priorLocation != null && priorLocation != DiscoverLocation.OFF
                                    when {
                                        translated == DiscoverLocation.OFF ->
                                            mutablePrefs[stringPreferencesKey("discover_location")] = translated.name
                                        priorIsValidNonOff -> {}
                                        priorLastNonOff != null ->
                                            mutablePrefs[stringPreferencesKey("discover_location")] = priorLastNonOff.name
                                        else ->
                                            mutablePrefs[stringPreferencesKey("discover_location")] = translated.name
                                    }
                                }
                            }
                            return@forEach
                        }
                        if (feature == "layout_settings" && keyName == "discover_location" && !hasWellFormedNewDiscoverKey) return@forEach
                        applyEncodedPreference(mutablePrefs, keyName, encodedValue)
                    }

                    restorePreferenceEntries(mutablePrefs, preservedEntries)
                    if (feature == "layout_settings" && priorDiscoverLocation != null) {
                        val discoverKey = stringPreferencesKey("discover_location")
                        if (mutablePrefs[discoverKey] == null) {
                            mutablePrefs[discoverKey] = priorDiscoverLocation
                        }
                    }
                    if (feature == "layout_settings") {
                        val finalDiscover = mutablePrefs[stringPreferencesKey("discover_location")]?.let {
                            runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
                        }
                        if (finalDiscover != null && finalDiscover != DiscoverLocation.OFF) {
                            mutablePrefs[stringPreferencesKey("last_non_off_discover_location")] =
                                finalDiscover.name
                        }
                    }
                }
            }
        } finally {
            applyingRemoteBlob = false
        }
    }

    private fun observeLocalSettingsChangesAndSync() {
        scope.launch {
            profileManager.activeProfileId
                .flatMapLatest { profileId ->
                    val featureFlows = syncedFeatures.map { feature ->
                        profileDataStoreFactory.get(profileId, feature).data
                            .map { prefs ->
                                "$feature={${buildFeatureSignature(prefs, feature)}}"
                            }
                    }
                    combine(featureFlows) { signatures ->
                        signatures.joinToString(separator = "||")
                    }
                }
                .drop(1)
                .distinctUntilChanged()
                .debounce(SETTINGS_PUSH_DEBOUNCE_MS)
                .collect { signature ->
                    if (!authManager.isAuthenticated) return@collect
                    if (applyingRemoteBlob) return@collect
                    if (profileDataStoreFactory.corruptedFileNames.isNotEmpty()) {
                        Log.w(TAG, "DataStore corruption detected (${profileDataStoreFactory.corruptedFileNames}) — pulling from remote instead of pushing")
                        profileDataStoreFactory.corruptedFileNames.clear()
                        pullCurrentProfileFromRemote()
                        return@collect
                    }
                    if (signature == skipNextPushSignature) {
                        skipNextPushSignature = null
                        return@collect
                    }
                    pushCurrentProfileToRemote()
                }
        }
    }

    private suspend fun buildSettingsSignature(profileId: Int): String {
        val signatures = ArrayList<String>(syncedFeatures.size)
        syncedFeatures.forEach { feature ->
            val prefs = profileDataStoreFactory.get(profileId, feature).data.first()
            signatures += "$feature={${buildFeatureSignature(prefs, feature)}}"
        }
        return signatures.joinToString(separator = "||")
    }

    private fun extractDiscoverLocationString(featureJson: JsonObject): String? {
        val encoded = featureJson["discover_location"] as? JsonObject ?: return null
        val type = encoded["type"]?.jsonPrimitive?.contentOrNull
        if (type != "string") return null
        return encoded["value"]?.jsonPrimitive?.contentOrNull
    }

    private fun normalizeLayoutSettingsForSignature(featureJson: JsonObject): JsonObject {
        val hasLegacy = "search_discover_enabled" in featureJson
        val hasNewKey = "discover_location" in featureJson
        val hasLocalOnly = featureJson.keys.any { it in localOnlyLayoutProfileSettingsKeys }
        if (!hasLegacy && !hasNewKey && !hasLocalOnly) return featureJson
        val newDiscoverString = extractDiscoverLocationString(featureJson)
        if (!hasLegacy && newDiscoverString != null && !hasLocalOnly) return featureJson
        return buildJsonObject {
            featureJson.forEach { (keyName, encodedValue) ->
                when {
                    keyName == "search_discover_enabled" -> return@forEach
                    keyName == "discover_location" && newDiscoverString == null -> return@forEach
                    keyName in localOnlyLayoutProfileSettingsKeys -> return@forEach
                    else -> put(keyName, encodedValue)
                }
            }
            if (newDiscoverString == null && hasLegacy) {
                val legacy = (featureJson["search_discover_enabled"] as? JsonObject)
                    ?.get("value")?.jsonPrimitive?.contentOrNull
                    ?.toBooleanStrictOrNull()
                if (legacy != null) {
                    put(
                        "discover_location",
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "value",
                                DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy).name
                            )
                        }
                    )
                }
            }
        }
    }

    private fun buildSettingsSignature(featuresJson: JsonObject): String {
        return syncedFeatures.joinToString(separator = "||") { feature ->
            val featureJson = featuresJson[feature]?.jsonObject ?: JsonObject(emptyMap())
            val normalized = if (feature == "layout_settings") {
                normalizeLayoutSettingsForSignature(featureJson)
            } else {
                featureJson
            }
            "$feature={${buildFeatureSignature(normalized, feature)}}"
        }
    }

    private fun buildFeatureSignature(prefs: Preferences, feature: String = ""): String {
        return prefs.asMap()
            .entries
            .mapNotNull { (key, rawValue) ->
                if (shouldExcludePreferenceFromProfileSettingsSync(feature, key.name)) return@mapNotNull null
                encodePreferenceValue(rawValue)?.let { encoded ->
                    key.name to encoded.toString()
                }
            }
            .sortedBy { it.first }
            .joinToString(separator = "|") { (key, value) -> "$key=$value" }
    }

    private fun buildFeatureSignature(featureJson: JsonObject, feature: String = ""): String {
        return featureJson.entries
            .filterNot { (key, _) -> shouldExcludePreferenceFromProfileSettingsSync(feature, key) }
            .sortedBy { it.key }
            .joinToString(separator = "|") { (key, value) -> "$key=$value" }
    }

    private fun captureLocalOnlyPreferenceEntries(
        feature: String,
        mutablePrefs: MutablePreferences
    ): Map<Preferences.Key<*>, Any> {
        val keyNames = when (feature) {
            "layout_settings" -> catalogKeysExcludedFromProfileSettingsBlob + localOnlyLayoutProfileSettingsKeys
            PLAYER_SETTINGS_FEATURE -> localOnlyPlayerProfileSettingsKeys
            else -> emptySet()
        }
        if (keyNames.isEmpty()) return emptyMap()
        val entries = mutableMapOf<Preferences.Key<*>, Any>()
        keyNames.forEach { keyName ->
            val stringKey = stringPreferencesKey(keyName)
            runCatching { mutablePrefs[stringKey] }.getOrNull()?.let { entries[stringKey] = it }
            val booleanKey = booleanPreferencesKey(keyName)
            runCatching { mutablePrefs[booleanKey] }.getOrNull()?.let { entries[booleanKey] = it }
            val intKey = intPreferencesKey(keyName)
            runCatching { mutablePrefs[intKey] }.getOrNull()?.let { entries[intKey] = it }
            val longKey = longPreferencesKey(keyName)
            runCatching { mutablePrefs[longKey] }.getOrNull()?.let { entries[longKey] = it }
            val floatKey = floatPreferencesKey(keyName)
            runCatching { mutablePrefs[floatKey] }.getOrNull()?.let { entries[floatKey] = it }
            val doubleKey = doublePreferencesKey(keyName)
            runCatching { mutablePrefs[doubleKey] }.getOrNull()?.let { entries[doubleKey] = it }
            val stringSetKey = stringSetPreferencesKey(keyName)
            runCatching { mutablePrefs[stringSetKey] }.getOrNull()?.let { entries[stringSetKey] = it }
        }
        return entries
    }

    @Suppress("UNCHECKED_CAST")
    private fun restorePreferenceEntries(
        mutablePrefs: MutablePreferences,
        entries: Map<Preferences.Key<*>, Any>
    ) {
        entries.forEach { (key, value) ->
            when (value) {
                is String -> mutablePrefs[key as Preferences.Key<String>] = value
                is Boolean -> mutablePrefs[key as Preferences.Key<Boolean>] = value
                is Int -> mutablePrefs[key as Preferences.Key<Int>] = value
                is Long -> mutablePrefs[key as Preferences.Key<Long>] = value
                is Float -> mutablePrefs[key as Preferences.Key<Float>] = value
                is Double -> mutablePrefs[key as Preferences.Key<Double>] = value
                is Set<*> -> {
                    if (value.all { it is String }) {
                        mutablePrefs[key as Preferences.Key<Set<String>>] = value as Set<String>
                    }
                }
            }
        }
    }

    private fun encodePreferenceValue(rawValue: Any?): JsonObject? {
        return when (rawValue) {
            is String -> buildJsonObject {
                put("type", "string")
                put("value", rawValue)
            }
            is Boolean -> buildJsonObject {
                put("type", "boolean")
                put("value", rawValue)
            }
            is Int -> buildJsonObject {
                put("type", "int")
                put("value", rawValue)
            }
            is Long -> buildJsonObject {
                put("type", "long")
                put("value", rawValue)
            }
            is Float -> buildJsonObject {
                put("type", "float")
                put("value", rawValue)
            }
            is Double -> buildJsonObject {
                put("type", "double")
                put("value", rawValue)
            }
            is Set<*> -> {
                val allStrings = rawValue.all { it is String }
                if (!allStrings) return null
                buildJsonObject {
                    put("type", "string_set")
                    val values = rawValue.map { it as String }.sorted()
                    put("value", JsonArray(values.map { JsonPrimitive(it) }))
                }
            }
            else -> null
        }
    }

    private fun applyEncodedPreference(
        mutablePrefs: androidx.datastore.preferences.core.MutablePreferences,
        keyName: String,
        encodedValue: JsonElement
    ) {
        val obj = encodedValue as? JsonObject ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
        val value = obj["value"] ?: JsonNull

        when (type) {
            "string" -> {
                val parsed = value.jsonPrimitive.contentOrNull ?: return
                mutablePrefs[stringPreferencesKey(keyName)] = parsed
            }
            "boolean" -> {
                val parsed = value.jsonPrimitive.contentOrNull?.toBooleanStrictOrNull() ?: return
                mutablePrefs[booleanPreferencesKey(keyName)] = parsed
            }
            "int" -> {
                val parsed = value.jsonPrimitive.intOrNull ?: return
                mutablePrefs[intPreferencesKey(keyName)] = parsed
            }
            "long" -> {
                val parsed = value.jsonPrimitive.longOrNull ?: return
                mutablePrefs[longPreferencesKey(keyName)] = parsed
            }
            "float" -> {
                val parsed = value.jsonPrimitive.floatOrNull ?: return
                mutablePrefs[floatPreferencesKey(keyName)] = parsed
            }
            "double" -> {
                val parsed = value.jsonPrimitive.doubleOrNull ?: return
                mutablePrefs[doublePreferencesKey(keyName)] = parsed
            }
            "string_set" -> {
                val parsed = value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                mutablePrefs[stringSetPreferencesKey(keyName)] = parsed
            }
        }
    }
}
