package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.domain.model.UserProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "profile_settings")

@Singleton
class ProfileDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi
) {
    private val dataStore = context.profileDataStore

    private val profilesJsonKey = stringPreferencesKey("profiles_json")
    private val activeProfileIdKey = intPreferencesKey("active_profile_id")

    private val profileListType = Types.newParameterizedType(List::class.java, ProfileJson::class.java)

    val profilesList: Flow<List<UserProfile>> = dataStore.data.map { prefs ->
        val json = prefs[profilesJsonKey]
        if (json != null) {
            parseProfiles(json)
        } else {
            listOf(defaultPrimaryProfile())
        }
    }

    val activeProfileId: Flow<Int> = dataStore.data.map { prefs ->
        prefs[activeProfileIdKey] ?: 1
    }

    suspend fun setActiveProfile(id: Int) {
        dataStore.edit { prefs ->
            prefs[activeProfileIdKey] = id
        }
    }

    suspend fun upsertProfile(profile: UserProfile) {
        dataStore.edit { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) {
                current[index] = profile
            } else {
                current.add(profile)
            }
            prefs[profilesJsonKey] = serializeProfiles(current)
        }
    }

    suspend fun deleteProfile(id: Int) {
        if (id == 1) return
        dataStore.edit { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            current.removeAll { it.id == id }
            prefs[profilesJsonKey] = serializeProfiles(current)
            if ((prefs[activeProfileIdKey] ?: 1) == id) {
                prefs[activeProfileIdKey] = 1
            }
        }
    }

    suspend fun replaceAllProfiles(profiles: List<UserProfile>) {
        dataStore.edit { prefs ->
            val normalizedProfiles = normalizeProfiles(profiles)
            prefs[profilesJsonKey] = serializeProfiles(normalizedProfiles)
            val activeId = prefs[activeProfileIdKey] ?: 1
            if (normalizedProfiles.none { it.id == activeId }) {
                prefs[activeProfileIdKey] = 1
            }
        }
    }

    private fun defaultPrimaryProfile() = UserProfile(
        id = 1,
        name = "Profile 1",
        avatarColorHex = "#1E88E5"
    )

    private fun parseProfiles(json: String?): List<UserProfile> {
        if (json.isNullOrBlank()) return listOf(defaultPrimaryProfile())
        return try {
            val adapter = moshi.adapter<List<ProfileJson>>(profileListType)
            val parsed = adapter.fromJson(json) ?: return listOf(defaultPrimaryProfile())
            normalizeProfiles(parsed.map { it.toDomain() })
        } catch (e: Exception) {
            listOf(defaultPrimaryProfile())
        }
    }

    private fun normalizeProfiles(profiles: List<UserProfile>): List<UserProfile> {
        val nonEmpty = profiles.ifEmpty { listOf(defaultPrimaryProfile()) }
        if (nonEmpty.any { it.id == 1 }) return nonEmpty
        return listOf(defaultPrimaryProfile()) + nonEmpty
    }

    private fun serializeProfiles(profiles: List<UserProfile>): String {
        val adapter = moshi.adapter<List<ProfileJson>>(profileListType)
        return adapter.toJson(profiles.map { ProfileJson.fromDomain(it) })
    }
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
internal data class ProfileJson(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false
) {
    fun toDomain() = UserProfile(
        id = id,
        name = name,
        avatarColorHex = avatarColorHex,
        usesPrimaryAddons = usesPrimaryAddons,
        usesPrimaryPlugins = usesPrimaryPlugins
    )

    companion object {
        fun fromDomain(profile: UserProfile) = ProfileJson(
            id = profile.id,
            name = profile.name,
            avatarColorHex = profile.avatarColorHex,
            usesPrimaryAddons = profile.usesPrimaryAddons,
            usesPrimaryPlugins = profile.usesPrimaryPlugins
        )
    }
}
