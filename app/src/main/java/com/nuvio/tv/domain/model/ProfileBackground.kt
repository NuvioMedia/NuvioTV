package com.nuvio.tv.domain.model

sealed interface ProfileBackgroundSelection {
    data class Catalog(val id: String) : ProfileBackgroundSelection
    data class Custom(val url: String) : ProfileBackgroundSelection
}

fun resolveProfileBackgroundSelection(
    profile: UserProfile?,
    entitlements: CosmeticEntitlements
): ProfileBackgroundSelection? {
    if (profile == null || !entitlements.includes(CosmeticEntitlement.PROFILE_BACKGROUNDS)) {
        return null
    }
    profile.profileBackgroundUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return ProfileBackgroundSelection.Custom(it)
    }
    return profile.profileBackgroundId?.let { ProfileBackgroundSelection.Catalog(it) }
}
