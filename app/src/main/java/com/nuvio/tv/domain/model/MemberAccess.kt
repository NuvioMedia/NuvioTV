package com.nuvio.tv.domain.model

enum class MemberTier {
    SUPPORTER,
    SUPPORTER_PLUS
}

data class MemberAccess(
    val tier: MemberTier? = null,
    val entitlements: CosmeticEntitlements = CosmeticEntitlements.None
) {
    companion object {
        val None = MemberAccess()

        fun preview(tier: MemberTier) = MemberAccess(
            tier = tier,
            entitlements = CosmeticEntitlements.SupporterPreview
        )
    }
}
