package com.nuvio.tv.domain.model

enum class CosmeticEntitlement {
    GOLD_THEME,
    JADE_THEME,
    ROSE_GOLD_THEME,
    ARCTIC_BLUE_THEME,
    GRAPHITE_THEME,
    PROFILE_BACKGROUNDS,
    PROFILE_AVATARS
}

data class CosmeticEntitlements(
    val unlocked: Set<CosmeticEntitlement> = emptySet()
) {
    fun includes(entitlement: CosmeticEntitlement): Boolean = entitlement in unlocked

    companion object {
        val None = CosmeticEntitlements()
        val SupporterPreview = CosmeticEntitlements(CosmeticEntitlement.entries.toSet())
    }
}
