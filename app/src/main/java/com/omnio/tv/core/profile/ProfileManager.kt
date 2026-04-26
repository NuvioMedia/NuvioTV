package com.omnio.tv.core.profile

import android.content.Context
import com.omnio.tv.data.local.ProfileDataStore
import com.omnio.tv.data.local.ProfileDataStoreFactory
import com.omnio.tv.domain.model.AgeRatingTier
import com.omnio.tv.domain.model.TraktSharingMode
import com.omnio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeProfileId: StateFlow<Int> = profileDataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, 1)

    val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, listOf(
            UserProfile(id = 1, name = "Profile 1", avatarColorHex = "#1E88E5")
        ))

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    suspend fun setActiveProfile(id: Int) {
        val exists = profiles.value.any { it.id == id }
        if (exists) {
            profileDataStore.setActiveProfile(id)
        }
    }

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean = false,
        usesPrimaryPlugins: Boolean = false,
        avatarId: String? = null,
        isKids: Boolean = false,
        maxAgeRating: AgeRatingTier? = null,
        traktSharing: TraktSharingMode = TraktSharingMode.OWN
    ): Int? {
        val current = profiles.value
        if (current.size >= 4) return null

        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..4).firstOrNull { it !in usedIds } ?: return null

        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { "Profile $nextId" },
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            usesPrimaryPlugins = usesPrimaryPlugins,
            avatarId = avatarId,
            isKids = isKids,
            maxAgeRating = if (isKids) maxAgeRating else null,
            traktSharing = traktSharing
        )
        factory.markProfileCreated(nextId)
        profileDataStore.upsertProfile(profile)
        return nextId
    }

    suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        if (profiles.value.none { it.id == id }) return false
        deleteProfileDataAsync(id)
        profileDataStore.deleteProfile(id)
        return true
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        if (profiles.value.none { it.id == profile.id }) return false
        val sanitized = if (profile.id == 1) {
            profile.copy(
                isKids = false,
                maxAgeRating = null,
                traktSharing = TraktSharingMode.OWN
            )
        } else profile.copy(
            maxAgeRating = if (profile.isKids) profile.maxAgeRating else null
        )
        profileDataStore.upsertProfile(sanitized)
        return true
    }

    private suspend fun deleteProfileDataAsync(profileId: Int) {
        if (profileId == 1) return

        factory.clearProfile(profileId)

        val suffixWithExtension = "_p${profileId}.preferences_pb"
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            dataStoreDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(suffixWithExtension)) {
                    file.delete()
                }
            }
        }

        val pluginCodeDir = File(context.filesDir, "plugin_code_p${profileId}")
        if (pluginCodeDir.exists()) {
            pluginCodeDir.deleteRecursively()
        }
    }
}
