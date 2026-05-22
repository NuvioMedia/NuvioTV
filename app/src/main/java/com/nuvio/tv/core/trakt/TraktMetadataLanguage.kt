package com.nuvio.tv.core.trakt

import com.nuvio.tv.LocaleCache
import com.nuvio.tv.data.local.TraktMetadataLanguageSource
import java.util.Locale

/**
 * Resolves which language code to use when localizing Trakt-sourced titles via TMDB.
 */
object TraktMetadataLanguage {

    fun resolve(
        source: TraktMetadataLanguageSource,
        tmdbLanguage: String,
        localeTag: String = LocaleCache.localeTag
    ): String {
        val raw = when (source) {
            TraktMetadataLanguageSource.INTERFACE -> localeTagToLanguage(localeTag)
            TraktMetadataLanguageSource.TMDB -> tmdbLanguage
        }
        return normalizeLanguage(raw)
    }

    fun isEnglish(language: String): Boolean {
        val code = language.trim().substringBefore("-").lowercase(Locale.US)
        return code == "en"
    }

    private fun localeTagToLanguage(localeTag: String): String {
        val tag = localeTag.trim()
            .takeIf { it.isNotBlank() && it != LocaleCache.UNSET }
            ?: Locale.getDefault().toLanguageTag()
        return normalizeLanguage(tag)
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
