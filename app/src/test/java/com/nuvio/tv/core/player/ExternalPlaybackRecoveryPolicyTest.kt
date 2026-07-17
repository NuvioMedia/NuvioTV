package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPlaybackRecoveryPolicyTest {
    @Test
    fun `refreshes when covered player quickly returns without a usable result`() {
        assertTrue(
            ExternalPlaybackRecoveryPolicy.shouldInvalidateCachedLink(
                hasUsableResult = false,
                externalPlayerCoveredApp = true,
                launchStartedAtMs = 1_000L,
                returnedAtMs = 12_000L
            )
        )
    }

    @Test
    fun `does not refresh a normal result`() {
        assertFalse(
            ExternalPlaybackRecoveryPolicy.shouldInvalidateCachedLink(
                hasUsableResult = true,
                externalPlayerCoveredApp = true,
                launchStartedAtMs = 1_000L,
                returnedAtMs = 2_000L
            )
        )
    }

    @Test
    fun `does not refresh when the player never covered the app`() {
        assertFalse(
            ExternalPlaybackRecoveryPolicy.shouldInvalidateCachedLink(
                hasUsableResult = false,
                externalPlayerCoveredApp = false,
                launchStartedAtMs = 1_000L,
                returnedAtMs = 2_000L
            )
        )
    }

    @Test
    fun `does not refresh a long playback from a player without result support`() {
        assertFalse(
            ExternalPlaybackRecoveryPolicy.shouldInvalidateCachedLink(
                hasUsableResult = false,
                externalPlayerCoveredApp = true,
                launchStartedAtMs = 1_000L,
                returnedAtMs = 16_001L
            )
        )
    }

    @Test
    fun `does not refresh a recovered process without an in memory launch time`() {
        assertFalse(
            ExternalPlaybackRecoveryPolicy.shouldInvalidateCachedLink(
                hasUsableResult = false,
                externalPlayerCoveredApp = true,
                launchStartedAtMs = 0L,
                returnedAtMs = 2_000L
            )
        )
    }

    @Test
    fun `includes the exact quick return boundary`() {
        assertTrue(
            ExternalPlaybackRecoveryPolicy.shouldInvalidateCachedLink(
                hasUsableResult = false,
                externalPlayerCoveredApp = true,
                launchStartedAtMs = 1_000L,
                returnedAtMs = 16_000L
            )
        )
    }
}
