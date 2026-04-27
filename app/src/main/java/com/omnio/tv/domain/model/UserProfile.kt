package com.omnio.tv.domain.model

data class UserProfile(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false,
    val avatarId: String? = null,
    val isKids: Boolean = false,
    val maxAgeRating: AgeRatingTier? = null,
    val traktSharing: TraktSharingMode = TraktSharingMode.OWN,
    val aioSharing: AioSharingMode = AioSharingMode.INDEPENDENT
) {
    val isPrimary: Boolean get() = id == 1

    val entryPinRequiredCapable: Boolean get() = !isKids
    val exitPinRequiredCapable: Boolean get() = isKids
}
