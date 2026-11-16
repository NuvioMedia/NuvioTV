package com.nuvio.tv.core.player

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsenetPlaybackFailureTest {
    @Test fun `failure kind is read from the engine header whatever its case`() {
        assertEquals("missing-article", usenetFailureKind(mapOf("x-usenet-failure" to listOf("missing-article"))))
        assertEquals("hole-limit", usenetFailureKind(mapOf("X-Usenet-Failure" to listOf("", " hole-limit "))))
        assertNull(usenetFailureKind(mapOf("Content-Type" to listOf("video/x-matroska"))))
        assertNull(usenetFailureKind(mapOf("X-Usenet-Failure" to emptyList())))
        assertNull(usenetFailureKind(emptyMap()))
    }

    @Test fun `each engine failure kind names its reason instead of an expired link`() {
        assertEquals(R.string.player_error_usenet_missing_parts, usenetFailureExplanation("missing-article"))
        assertEquals(R.string.player_error_usenet_missing_parts, usenetFailureExplanation("hole-limit"))
        assertEquals(R.string.player_error_usenet_damaged_parts, usenetFailureExplanation("invalid-article"))
        assertEquals(R.string.player_error_usenet_provider_login, usenetFailureExplanation("provider-authentication"))
        assertEquals(R.string.player_error_usenet_provider_quota, usenetFailureExplanation("provider-quota"))
        assertEquals(R.string.player_error_usenet_empty_release, usenetFailureExplanation("blank-content"))
    }

    @Test fun `streams without an engine verdict keep the HTTP status hint`() {
        assertNull(usenetFailureExplanation(null))
        assertNull(usenetFailureExplanation("something-new"))
    }
}
