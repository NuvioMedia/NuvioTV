package com.nuvio.tv

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the content-localization fix: TmdbSettingsDataStore's default language
 * needs to react immediately when the app's UI language changes (not just on the next cold
 * start), so LocaleCache.localeTag is now backed by a StateFlow rather than a plain @Volatile
 * var. This verifies the flow actually reflects writes made through the existing property API,
 * so every pre-existing `LocaleCache.localeTag = ...` call site keeps working unchanged.
 */
class LocaleCacheTest {

    @Test
    fun `localeTagFlow reflects the current value written via the property setter`() = runBlocking {
        val original = LocaleCache.localeTag
        try {
            LocaleCache.localeTag = "it"
            assertEquals("it", LocaleCache.localeTagFlow.first())

            LocaleCache.localeTag = "es"
            assertEquals("es", LocaleCache.localeTagFlow.first())
        } finally {
            LocaleCache.localeTag = original
        }
    }

    @Test
    fun `localeTag getter reads back exactly what was written`() {
        val original = LocaleCache.localeTag
        try {
            LocaleCache.localeTag = "en"
            assertEquals("en", LocaleCache.localeTag)
        } finally {
            LocaleCache.localeTag = original
        }
    }
}
