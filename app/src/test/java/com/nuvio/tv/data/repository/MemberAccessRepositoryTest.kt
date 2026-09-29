package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.MemberTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberAccessRepositoryTest {
    @Test
    fun releaseBuildIgnoresStoredDebugTier() {
        val access = resolveMemberAccess(
            isDebugBuild = false,
            memberTier = MemberTier.SUPPORTER_PLUS
        )

        assertEquals(MemberAccess.None, access)
    }

    @Test
    fun noTierKeepsDebugUsersOnStandardAccess() {
        val access = resolveMemberAccess(
            isDebugBuild = true,
            memberTier = null
        )

        assertEquals(MemberAccess.None, access)
    }

    @Test
    fun bothPreviewTiersUnlockCurrentSupporterBenefits() {
        MemberTier.entries.forEach { tier ->
            val access = resolveMemberAccess(
                isDebugBuild = true,
                memberTier = tier
            )

            assertEquals(tier, access.tier)
            assertEquals(CosmeticEntitlement.entries.toSet(), access.entitlements.unlocked)
        }
    }

    @Test
    fun missingVerificationRefreshesOnForeground() {
        assertTrue(shouldRefreshMemberAccess(lastVerifiedAtMs = null, nowMs = 1_000L))
    }

    @Test
    fun foregroundRefreshWaitsForFifteenMinuteStaleness() {
        val verifiedAt = 5_000L

        assertFalse(
            shouldRefreshMemberAccess(
                lastVerifiedAtMs = verifiedAt,
                nowMs = verifiedAt + MemberAccessStaleAfterMs - 1L
            )
        )
        assertTrue(
            shouldRefreshMemberAccess(
                lastVerifiedAtMs = verifiedAt,
                nowMs = verifiedAt + MemberAccessStaleAfterMs
            )
        )
    }

    @Test
    fun elapsedClockResetForcesRefresh() {
        assertTrue(shouldRefreshMemberAccess(lastVerifiedAtMs = 10_000L, nowMs = 1_000L))
    }

    @Test
    fun retryScheduleUsesBoundedExponentialBackoff() {
        assertEquals(1_000L, memberAccessRetryDelayMs(0))
        assertEquals(2_000L, memberAccessRetryDelayMs(1))
        assertEquals(4_000L, memberAccessRetryDelayMs(2))
        assertNull(memberAccessRetryDelayMs(3))
    }
}
