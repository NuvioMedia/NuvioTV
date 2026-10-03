package com.nuvio.tv.core.remote

import org.junit.Assert.*
import org.junit.Test

class RemotePairingAuthorityTest {
    private var now = 0L
    private var sequence = 0
    private var persisted: String? = null
    private fun authority() = RemotePairingAuthority(persisted, { now }, { (++sequence).toString().padStart(43, '0') }, { persisted = it })

    @Test fun pairingIsOneUseAndPersistsOnlyDigest() {
        val auth = authority()
        val secret = auth.open()
        val credential = requireNotNull(auth.pair(secret))
        assertTrue(auth.authorized(credential))
        assertFalse(auth.authorized(secret))
        assertNotEquals(credential, persisted)
        assertEquals(64, persisted!!.length)
        assertNull(auth.pair(secret))
        assertTrue(authority().authorized(credential))
    }
    @Test fun expiredClosedAndReplacedCodesFail() {
        val auth = authority()
        val expired = auth.open()
        now = 120_000
        assertNull(auth.pair(expired))
        val old = auth.open()
        val current = auth.open()
        assertNull(auth.pair(old))
        auth.close()
        assertNull(auth.pair(current))
    }
    @Test fun replacingAndRevokingInvalidatePreviousCredentials() {
        val auth = authority()
        val first = requireNotNull(auth.pair(auth.open()))
        val second = requireNotNull(auth.pair(auth.open()))
        assertFalse(auth.authorized(first))
        assertTrue(auth.authorized(second))
        auth.revoke()
        assertFalse(auth.authorized(second))
        assertNull(persisted)
        assertFalse(authority().hasCredential)
    }
    @Test fun guessesAreRateLimitedEvenWhenCorrectCodeFollows() {
        val auth = authority()
        val secret = auth.open()
        repeat(8) { assertNull(auth.pair("wrong")) }
        assertNull(auth.pair(secret))
        now = 60_000
        assertNotNull(auth.pair(secret))
    }
}
