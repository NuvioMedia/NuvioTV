package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UsenetPreparationLogTest {
    @Test fun `logged errors keep hosts but drop keys and credentials`() {
        val message = redacted("""Get "https://indexer.test/api?t=get&id=1&apikey=SECRET": timeout; nntps://user:PASS@news.test:563/20 refused""")
        assertFalse(message.contains("SECRET"))
        assertFalse(message.contains("PASS"))
        assertEquals("""Get "https://indexer.test/…": timeout; nntps://news.test:563/… refused""", message)
    }
}
