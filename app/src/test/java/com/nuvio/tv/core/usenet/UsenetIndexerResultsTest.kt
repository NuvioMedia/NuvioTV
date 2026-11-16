package com.nuvio.tv.core.usenet

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsenetIndexerResultsTest {
    private val unlimited = UsenetSourceConfiguration.UNLIMITED_RESULTS

    @Test fun `auto reads as many results per indexer as before`() {
        val auto = UsenetSourceConfiguration()
        assertEquals(UsenetSourceConfiguration.AUTO_INDEXER_RESULTS, auto.indexerResultsLimit)
        assertEquals(200, auto.copy(maxResults = 50).indexerResults)
        assertEquals(200, auto.copy(maxResults = 200).indexerResults)
        assertEquals(500, auto.copy(maxResults = 500).indexerResults)
        assertEquals(1000, auto.copy(maxResults = unlimited).indexerResults)
    }

    @Test fun `a chosen number is read as it is, whatever is listed`() {
        val config = UsenetSourceConfiguration(indexerResultsLimit = 100)
        assertEquals(100, config.copy(maxResults = 50).indexerResults)
        assertEquals(100, config.copy(maxResults = 500).indexerResults)
        assertEquals(100, config.copy(maxResults = unlimited).indexerResults)
        assertEquals(1000, config.copy(indexerResultsLimit = 1000, maxResults = 10).indexerResults)
    }

    @Test fun `settings saved before the option read as auto`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<UsenetSourceConfiguration>("""{"maxResults":500}""")
        assertEquals(UsenetSourceConfiguration.AUTO_INDEXER_RESULTS, old.indexerResultsLimit)
        assertEquals(500, old.indexerResults)
    }

    @Test fun `only auto or a count up to the indexer bound is valid`() {
        UsenetSourceConfiguration(indexerResultsLimit = 0).validate()
        UsenetSourceConfiguration.INDEXER_RESULT_CHOICES.forEach { UsenetSourceConfiguration(indexerResultsLimit = it).validate() }
        assertThrows(IllegalArgumentException::class.java) { UsenetSourceConfiguration(indexerResultsLimit = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) { UsenetSourceConfiguration(indexerResultsLimit = 1001).validate() }
    }
}
