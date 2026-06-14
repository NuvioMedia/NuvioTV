package com.nuvio.tv.core.recommendations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramBuilderTest {

    @Test
    fun `watch next content id match does not delete prefix collisions`() {
        assertTrue(watchNextIdMatchesContentId("wn_tt1", "tt1"))
        assertTrue(watchNextIdMatchesContentId("wn_tt1_s2e3", "tt1"))

        assertFalse(watchNextIdMatchesContentId("wn_tt10", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt10_s2e3", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt1extra", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt1_series", "tt1"))
        assertFalse(watchNextIdMatchesContentId(null, "tt1"))
    }
}
