package com.nuvio.tv.core.trakt

import com.nuvio.tv.LocaleCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktMetadataLanguageTest {

    @Test
    fun isEnglish_matchesBareAndRegionalCodes() {
        assertTrue(TraktMetadataLanguage.isEnglish("en"))
        assertTrue(TraktMetadataLanguage.isEnglish("en-US"))
        assertTrue(TraktMetadataLanguage.isEnglish("EN-gb"))
        assertFalse(TraktMetadataLanguage.isEnglish("he"))
        assertFalse(TraktMetadataLanguage.isEnglish("he-IL"))
        assertFalse(TraktMetadataLanguage.isEnglish("fr"))
    }

    @Test
    fun resolveInterfaceLanguage_usesExplicitLocaleTag() {
        assertEquals("he", TraktMetadataLanguage.resolveInterfaceLanguage("he"))
        assertEquals("he-IL", TraktMetadataLanguage.resolveInterfaceLanguage("he-il"))
        assertEquals("pt-BR", TraktMetadataLanguage.resolveInterfaceLanguage("pt_br"))
        assertEquals("fr-FR", TraktMetadataLanguage.resolveInterfaceLanguage("fr-FR"))
    }

    @Test
    fun resolveInterfaceLanguage_ignoresUnsetSentinel() {
        // When the user has not chosen an app language, fall back to system locale
        // rather than the internal UNSET sentinel string.
        val resolved = TraktMetadataLanguage.resolveInterfaceLanguage(LocaleCache.UNSET)
        assertFalse(resolved.equals(LocaleCache.UNSET, ignoreCase = true))
        assertTrue(resolved.isNotBlank())
    }
}
