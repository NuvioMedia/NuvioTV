package com.nuvio.tv.ui.util

import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LanguageLookupCacheTest {
    @Test
    fun `language names follow the app locale after being cached`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val english = languageCodeToName("pol")
            assertEquals(english, languageCodeToName("pol"))

            Locale.setDefault(Locale.forLanguageTag("pl"))
            val polish = languageCodeToName("pol")
            assertNotEquals(english, polish)
            assertEquals(polish, languageCodeToName("pol"))

            Locale.setDefault(Locale.ENGLISH)
            assertEquals(english, languageCodeToName("pol"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `normalized language codes are stable across repeated lookups`() {
        listOf("Brazilian Portuguese", "pt_BR", "Spanish (Latin America)", "pol", "English").forEach { raw ->
            val first = PlayerSubtitleUtils.normalizeLanguageCode(raw)
            repeat(3) { assertEquals(first, PlayerSubtitleUtils.normalizeLanguageCode(raw)) }
        }
        assertEquals("pt-br", PlayerSubtitleUtils.normalizeLanguageCode("Brazilian Portuguese"))
        assertEquals("es-419", PlayerSubtitleUtils.normalizeLanguageCode("Spanish (Latin America)"))
    }
}
