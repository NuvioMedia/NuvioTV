package com.nuvio.tv.domain.model

data class MembershipOverview(
    val status: String = "inactive",
    val tier: MemberTier? = null,
    val supporterSince: String? = null,
    val providerConnected: Boolean = false,
    val subscriptionActive: Boolean = false,
    val membershipLevel: MemberTier? = null,
    val currentPeriodEnd: String? = null,
    val cancelsAtPeriodEnd: Boolean = false,
    val hasActiveGrant: Boolean = false,
    val grantIsLifetime: Boolean = false,
    val grantExpiresAt: String? = null,
    val grantKind: String? = null,
    val grantTier: MemberTier? = null,
    val hasLifetimeGrant: Boolean = false,
    val lifetimeGrantTier: MemberTier? = null
) {
    val active: Boolean
        get() = status == "active" && tier != null
}

data class MembershipOverviewState(
    val overview: MembershipOverview? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasError: Boolean = false
)
