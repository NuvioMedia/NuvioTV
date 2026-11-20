package com.nuvio.tv.core.usenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsenetLiveStatsTest {
    @Test fun `providers are totalled and the busiest one gives the response time`() {
        val stats = UsenetLiveStats.parse("""
            {"store":{"requests":10},"filledArticles":2,"streamFailure":"",
             "providers":[{"server":0,"articles":900,"bytes":700000000,"missing":3,"errors":1,"ttfbMs":84.5},
                          {"server":1,"articles":20,"bytes":15000000,"missing":0,"errors":0,"ttfbMs":210.0}]}
        """.trimIndent())!!
        assertEquals(715_000_000L, stats.bytes)
        assertEquals(3L, stats.missing)
        assertEquals(1L, stats.errors)
        assertEquals(2L, stats.filledArticles)
        assertEquals(84.5, stats.ttfbMs!!, 0.0)
        assertNull(stats.failure)
    }

    @Test fun `a session without provider reports reads as empty`() {
        val stats = UsenetLiveStats.parse("""{"streamFailure":"missing-article"}""")!!
        assertEquals(0L, stats.bytes)
        assertNull(stats.ttfbMs)
        assertEquals("missing-article", stats.failure)
    }

    @Test fun `a provider without a response time sample is skipped for it`() {
        val stats = UsenetLiveStats.parse("""{"providers":[{"bytes":5,"ttfbMs":0},{"bytes":1,"ttfbMs":40}]}""")!!
        assertEquals(40.0, stats.ttfbMs!!, 0.0)
    }

    @Test fun `an unreadable answer is ignored`() {
        assertNull(UsenetLiveStats.parse("not json"))
        assertNull(UsenetLiveStats.parse("[]"))
        assertNull(UsenetLiveStats.parse("""{"streamFailure":null}""")?.failure)
    }
}
