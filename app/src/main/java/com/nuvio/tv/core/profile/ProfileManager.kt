package com.nuvio.tv.core.profile

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    private val credentialStores: Set<@JvmSuppressWildcards ProfileScopedCredentialStore>,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MAX_PROFILES = 6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeProfileId: StateFlow<Int> = profileDataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, 1)

    val activeProfileReady: StateFlow<Boolean> = profileDataStore.activeProfileId
        .map { true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val hasEverSelectedProfile: StateFlow<Boolean> = profileDataStore.hasEverSelectedProfile
        .stateIn(scope, SharingStarted.Eagerly, false)

    val rememberLastProfileEnabled: StateFlow<Boolean> = profileDataStore.rememberLastProfileEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    val confirmExitEnabled: StateFlow<Boolean> = profileDataStore.confirmExitEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    val startupSplashEnabled: StateFlow<Boolean> = profileDataStore.startupSplashEnabled
        .stateIn(scope, SharingStarted.Eagerly, true)

    val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, listOf(
            UserProfile(id = 1, name = context.getString(R.string.profile_default_name, 1), avatarColorHex = "#1E88E5")
        ))

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    val canCreateProfile: Boolean
        get() = profiles.value.size < MAX_PROFILES

    suspend fun setActiveProfile(id: Int) {
        val exists = profiles.value.any { it.id == id }
        if (exists) {
            profileDataStore.setActiveProfile(id)
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        profileDataStore.setRememberLastProfileEnabled(enabled)
    }

    suspend fun setConfirmExitEnabled(enabled: Boolean) {
        profileDataStore.setConfirmExitEnabled(enabled)
    }

    suspend fun setStartupSplashEnabled(enabled: Boolean) {
        profileDataStore.setStartupSplashEnabled(enabled)
    }

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean = false,
        usesPrimaryPlugins: Boolean = false,
        avatarId: String? = null
    ): UserProfile? {
        val current = profiles.value
        if (current.size >= MAX_PROFILES) return null

        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..MAX_PROFILES).firstOrNull { it !in usedIds } ?: return null

        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { context.getString(R.string.profile_default_name, nextId) },
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            usesPrimaryPlugins = usesPrimaryPlugins,
            avatarId = avatarId
        )
        factory.markProfileCreated(nextId)
        profileDataStore.upsertProfile(profile)
        profiles.first { entries -> entries.any { it.id == nextId } }
        return profile
    }

    suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        if (profiles.value.none { it.id == id }) return false
        removeLocalProfileData(id)
        profileDataStore.deleteProfile(id)
        return true
    }

    /**
     * Replaces the profile list with the account's list from the server. Profiles missing from it
     * were deleted on another device, so their local data goes too: profile ids are reused, and
     * the next profile created with the same id would otherwise inherit it.
     */
    suspend fun replaceAllProfiles(remoteProfiles: List<UserProfile>) {
        val previousIds = profileDataStore.profilesList.first().map { it.id }.toSet()
        profileDataStore.replaceAllProfiles(remoteProfiles)
        val remoteIds = remoteProfiles.map { it.id }.toSet()
        (previousIds - remoteIds).forEach { id -> removeLocalProfileData(id) }
        (remoteIds - previousIds).forEach { id -> factory.markProfileCreated(id) }
    }

    private suspend fun removeLocalProfileData(id: Int) {
        if (id == 1) return
        credentialStores.forEach { store -> store.removeProfile(id) }
        deleteProfileDataAsync(id)
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        if (profiles.value.none { it.id == profile.id }) return false
        profileDataStore.upsertProfile(profile)
        return true
    }

    private suspend fun deleteProfileDataAsync(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == 1) return@withContext

        factory.clearProfile(profileId)

        val suffixWithExtension = "_p${profileId}.preferences_pb"
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            dataStoreDir.listFiles()?.forEach { file ->
                // Shadow copies too: a store that fails to parse is restored from its .bak.
                if (file.name.endsWith(suffixWithExtension) || file.name.endsWith("$suffixWithExtension.bak")) {
                    file.delete()
                }
            }
        }

        val pluginCodeDir = File(context.filesDir, "plugin_code_p${profileId}")
        if (pluginCodeDir.exists()) {
            pluginCodeDir.deleteRecursively()
        }

        // Continue Watching cache that Home shows before its pipeline has run.
        File(context.filesDir, "cw_enrichment").listFiles()?.forEach { file ->
            if (file.name == "inprogress_$profileId.json" || file.name == "nextup_$profileId.json") {
                file.delete()
            }
        }
    }
}
