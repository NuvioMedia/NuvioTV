package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
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
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.remote.supabase.SupabaseProfileSettingsBlob
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

@Singleton
class ProfileSettingsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager,
    private val profileDataStoreFactory: ProfileDataStoreFactory
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
        "player_settings",
        "trailer_settings",
        "tmdb_settings",
        "mdblist_settings",
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
                }

                val response = withJwtRefreshRetry {
                    postgrest.rpc("sync_pull_profile_settings_blob", params)
                }
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

                importSettingsBlob(profileId, featuresJson)
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
                    mutablePrefs.clear()
                    featureJson.forEach { (keyName, encodedValue) ->
                        applyEncodedPreference(mutablePrefs, keyName, encodedValue)
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
                                "$feature={${buildFeatureSignature(prefs)}}"
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
            signatures += "$feature={${buildFeatureSignature(prefs)}}"
        }
        return signatures.joinToString(separator = "||")
    }

    private fun buildSettingsSignature(featuresJson: JsonObject): String {
        return syncedFeatures.joinToString(separator = "||") { feature ->
            val featureJson = featuresJson[feature]?.jsonObject ?: JsonObject(emptyMap())
            "$feature={${buildFeatureSignature(featureJson)}}"
        }
    }

    private fun buildFeatureSignature(prefs: Preferences): String {
        return prefs.asMap()
            .entries
            .mapNotNull { (key, rawValue) ->
                encodePreferenceValue(rawValue)?.let { encoded ->
                    key.name to encoded.toString()
                }
            }
            .sortedBy { it.first }
            .joinToString(separator = "|") { (key, value) -> "$key=$value" }
    }

    private fun buildFeatureSignature(featureJson: JsonObject): String {
        return featureJson.entries
            .sortedBy { it.key }
            .joinToString(separator = "|") { (key, value) -> "$key=$value" }
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
