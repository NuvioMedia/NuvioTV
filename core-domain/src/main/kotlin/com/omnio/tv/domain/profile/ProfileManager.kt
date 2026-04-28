package com.omnio.tv.domain.profile

import com.omnio.tv.domain.model.AgeRatingTier
import com.omnio.tv.domain.model.AioSharingMode
import com.omnio.tv.domain.model.TraktSharingMode
import com.omnio.tv.domain.model.UserProfile
import kotlinx.coroutines.flow.StateFlow

interface ProfileManager {

    val activeProfileId: StateFlow<Int>

    val profiles: StateFlow<List<UserProfile>>

    val activeProfile: UserProfile?

    val isPrimaryProfileActive: Boolean

    suspend fun setActiveProfile(id: Int)

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean = false,
        usesPrimaryPlugins: Boolean = false,
        avatarId: String? = null,
        isKids: Boolean = false,
        maxAgeRating: AgeRatingTier? = null,
        traktSharing: TraktSharingMode = TraktSharingMode.OWN,
        aioSharing: AioSharingMode = AioSharingMode.INDEPENDENT
    ): Int?

    suspend fun deleteProfile(id: Int): Boolean

    suspend fun updateProfile(profile: UserProfile): Boolean
}
