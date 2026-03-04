package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import java.util.Locale

/**
 * Represents a subtitle from a Stremio addon
 */
@Immutable
data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String?
) {
    /**
     * Returns a human-readable language name
     */
    fun getDisplayLanguage(): String {
        return languageCodeToName(lang)
    }
    
    companion object {
        private val languageOverrides = mapOf(
            "pt" to "pt",
            "pt-pt" to "pt",
            "pt_pt" to "pt",
            "por" to "pt",
            "pt-br" to "pt-BR",
            "pt_br" to "pt-BR",
            "br" to "pt-BR",
            "pob" to "pt-BR",
            "fre" to "fr",
            "ger" to "de",
            "deu" to "de",
            "dut" to "nl",
            "nld" to "nl",
            "chi" to "zh",
            "zho" to "zh",
            "jpn" to "ja",
            "kor" to "ko",
            "ara" to "ar",
            "hin" to "hi",
            "rus" to "ru",
            "pol" to "pl",
            "spa" to "es",
            "fra" to "fr",
            "ita" to "it",
            "eng" to "en",
            "swe" to "sv",
            "nor" to "no",
            "dan" to "da",
            "fin" to "fi",
            "tur" to "tr",
            "ell" to "el",
            "gre" to "el",
            "heb" to "he",
            "tha" to "th",
            "vie" to "vi",
            "ind" to "id",
            "msa" to "ms",
            "may" to "ms",
            "ces" to "cs",
            "cze" to "cs",
            "hun" to "hu",
            "ron" to "ro",
            "rum" to "ro",
            "ukr" to "uk",
            "bul" to "bg",
            "hrv" to "hr",
            "srp" to "sr",
            "slk" to "sk",
            "slo" to "sk",
            "slv" to "sl"
        )

        fun languageCodeToName(code: String): String {
            val lowerCode = code.lowercase()
            val bcp47 = languageOverrides[lowerCode] ?: lowerCode
            return try {
                val locale = Locale.forLanguageTag(bcp47)
                val name = locale.getDisplayLanguage(Locale.getDefault())
                if (name.isNotBlank() && name != bcp47) name.replaceFirstChar { it.uppercase() }
                else code.uppercase()
            } catch (_: Exception) {
                code.uppercase()
            }
        }
    }
}
