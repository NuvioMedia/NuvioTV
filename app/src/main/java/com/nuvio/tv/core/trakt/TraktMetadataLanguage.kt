package com.nuvio.tv.core.trakt

import com.nuvio.tv.LocaleCache
import java.util.Locale

/**
 * Resolves the app interface language for localizing Trakt-sourced library titles.
 * Trakt's API returns English titles; we map them via TMDB using this language.
 */
object TraktMetadataLanguage {

    fun resolveInterfaceLanguage(localeTag: String = LocaleCache.localeTag): String {
        val tag = localeTag.trim()
            .takeIf { it.isNotBlank() && it != LocaleCache.UNSET }
            ?: Locale.getDefault().toLanguageTag()
        return normalizeLanguage(tag)
    }

    fun isEnglish(language: String): Boolean {
        val code = language.trim().substringBefore("-").lowercase(Locale.US)
        return code == "en"
    }

    private fun normalizeLanguage(language: String): String {
        val raw = language.trim().replace('_', '-').takeIf { it.isNotBlank() } ?: return "en"
        val parts = raw.split("-")
        return if (parts.size == 2) {
            "${parts[0].lowercase(Locale.US)}-${parts[1].uppercase(Locale.US)}"
        } else {
            raw.lowercase(Locale.US)
        }
    }
}
