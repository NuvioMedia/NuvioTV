package com.omnio.tv.core.profile

import android.content.Context
import com.omnio.tv.data.local.ProfileDataStore
import com.omnio.tv.data.local.ProfileDataStoreFactory
import com.omnio.tv.domain.model.AgeRatingTier
import com.omnio.tv.domain.model.AioSharingMode
import com.omnio.tv.domain.model.TraktSharingMode
import com.omnio.tv.domain.model.UserProfile
import com.omnio.tv.domain.profile.ProfileManager
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
class ProfileManagerImpl @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    @ApplicationContext private val context: Context
) : ProfileManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val activeProfileId: StateFlow<Int> = profileDataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, 1)

    override val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, listOf(
            UserProfile(id = 1, name = "Profile 1", avatarColorHex = "#1E88E5")
        ))

    override val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    override val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    override suspend fun setActiveProfile(id: Int) {
        val exists = profiles.value.any { it.id == id }
        if (exists) {
            profileDataStore.setActiveProfile(id)
        }
    }

    override suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean,
        usesPrimaryPlugins: Boolean,
        avatarId: String?,
        isKids: Boolean,
        maxAgeRating: AgeRatingTier?,
        traktSharing: TraktSharingMode,
        aioSharing: AioSharingMode
    ): Int? {
        val current = profiles.value
        if (current.size >= 4) return null

        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..4).firstOrNull { it !in usedIds } ?: return null

        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { "Profile $nextId" },
            avatarColorHex = avatarColorHex,
            // Kids profiles run their own AIOMetadata config (with cert-filtered
            // catalogs); they cannot live-mirror Main's addon list, otherwise
            // the kid-tuned manifest gets shadowed by Main's addons.
            usesPrimaryAddons = usesPrimaryAddons && !isKids,
            usesPrimaryPlugins = usesPrimaryPlugins,
            avatarId = avatarId,
            isKids = isKids,
            maxAgeRating = if (isKids) maxAgeRating else null,
            traktSharing = traktSharing,
            // Kids profiles cannot adopt Main's full config — only KEYS_ONLY
            // or INDEPENDENT make sense. FULL_MIRROR would discard the
            // kid-tuned catalogs.
            aioSharing = if (isKids && aioSharing == AioSharingMode.FULL_MIRROR) {
                AioSharingMode.KEYS_ONLY
            } else aioSharing
        )
        factory.markProfileCreated(nextId)
        profileDataStore.upsertProfile(profile)
        return nextId
    }

    override suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        if (profiles.value.none { it.id == id }) return false
        deleteProfileDataAsync(id)
        profileDataStore.deleteProfile(id)
        return true
    }

    override suspend fun updateProfile(profile: UserProfile): Boolean {
        if (profiles.value.none { it.id == profile.id }) return false
        val sanitized = if (profile.id == 1) {
            profile.copy(
                isKids = false,
                maxAgeRating = null,
                traktSharing = TraktSharingMode.OWN,
                aioSharing = AioSharingMode.INDEPENDENT
            )
        } else profile.copy(
            maxAgeRating = if (profile.isKids) profile.maxAgeRating else null,
            // Same invariant as createProfile: Kids profiles cannot mirror
            // Main's addons (their kid-tuned manifest would be shadowed).
            usesPrimaryAddons = profile.usesPrimaryAddons && !profile.isKids,
            aioSharing = if (profile.isKids && profile.aioSharing == AioSharingMode.FULL_MIRROR) {
                AioSharingMode.KEYS_ONLY
            } else profile.aioSharing
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
