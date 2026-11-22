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

    @Test fun `completion counts the articles a provider had`() {
        val stats = UsenetLiveStats.parse("""{"providers":[{"server":0,"articles":995,"missing":5},{"server":1,"articles":0,"missing":0}]}""")!!
        assertEquals(99.5, stats.completion!!, 0.0)
        assertNull(UsenetLiveStats.parse("""{"providers":[{"server":0}]}""")!!.completion)
    }

    @Test fun `each provider has its own completion and maps back to its server`() {
        val stats = UsenetLiveStats.parse("""
            {"providers":[{"server":0,"articles":98,"missing":2},{"server":1,"articles":0,"missing":0},{"server":2,"articles":2,"missing":0}]}
        """.trimIndent())!!.copy(servers = listOf("nntps://a:1/20", "nntps://b:1/20", "nntps://c:1/20"))
        assertEquals(98.0, stats.providers[0].completion!!, 0.0)
        assertNull(stats.providers[1].completion)
        assertEquals(100.0, stats.providers[2].completion!!, 0.0)
        assertEquals("nntps://c:1/20", stats.server(stats.providers[2]))
        assertNull(stats.copy(servers = emptyList()).server(stats.providers.first()))
    }

    @Test fun `an unreadable answer is ignored`() {
        assertNull(UsenetLiveStats.parse("not json"))
        assertNull(UsenetLiveStats.parse("[]"))
        assertNull(UsenetLiveStats.parse("""{"streamFailure":null}""")?.failure)
    }
}
