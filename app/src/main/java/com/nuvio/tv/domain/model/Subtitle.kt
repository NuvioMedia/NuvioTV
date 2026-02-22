package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Representa un subtítulo proveniente de un addon de Stremio
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
     * Devuelve el nombre del idioma en un formato legible para el usuario
     */
    fun getDisplayLanguage(): String {
        return languageCodeToName(lang)
    }
    
    companion object {
        private val languageNames = mapOf(
            "en" to "Inglés",
            "eng" to "Inglés",
            "es" to "Español",
            "spa" to "Español",
            "fr" to "Francés",
            "fra" to "Francés",
            "fre" to "Francés",
            "de" to "Alemán",
            "deu" to "Alemán",
            "ger" to "Alemán",
            "it" to "Italiano",
            "ita" to "Italiano",
            "pt" to "Portugués (Portugal)",
            "pt-pt" to "Portugués (Portugal)",
            "pt_pt" to "Portugués (Portugal)",
            "por" to "Portugués (Portugal)",
            "pt-br" to "Portugués (Brasil)",
            "pt_br" to "Portugués (Brasil)",
            "br" to "Portugués (Brasil)",
            "pob" to "Portugués (Brasil)",
            "ru" to "Ruso",
            "rus" to "Ruso",
            "ja" to "Japonés",
            "jpn" to "Japonés",
            "ko" to "Coreano",
            "kor" to "Coreano",
            "zh" to "Chino",
            "chi" to "Chino",
            "zho" to "Chino",
            "ar" to "Árabe",
            "ara" to "Árabe",
            "hi" to "Hindi",
            "hin" to "Hindi",
            "nl" to "Holandés",
            "nld" to "Holandés",
            "dut" to "Holandés",
            "pl" to "Polaco",
            "pol" to "Polaco",
            "sv" to "Sueco",
            "swe" to "Sueco",
            "no" to "Noruego",
            "nor" to "Noruego",
            "da" to "Danés",
            "dan" to "Danés",
            "fi" to "Finlandés",
            "fin" to "Finlandés",
            "tr" to "Turco",
            "tur" to "Turco",
            "el" to "Griego",
            "ell" to "Griego",
            "gre" to "Griego",
            "he" to "Hebreo",
            "heb" to "Hebreo",
            "th" to "Tailandés",
            "tha" to "Tailandés",
            "vi" to "Vietnamita",
            "vie" to "Vietnamita",
            "id" to "Indonesio",
            "ind" to "Indonesio",
            "ms" to "Malayo",
            "msa" to "Malayo",
            "may" to "Malayo",
            "cs" to "Checo",
            "ces" to "Checo",
            "cze" to "Checo",
            "hu" to "Húngaro",
            "hun" to "Húngaro",
            "ro" to "Rumano",
            "ron" to "Rumano",
            "rum" to "Rumano",
            "uk" to "Ucraniano",
            "ukr" to "Ucraniano",
            "bg" to "Búlgaro",
            "bul" to "Búlgaro",
            "hr" to "Croata",
            "hrv" to "Croata",
            "sr" to "Serbio",
            "srp" to "Serbio",
            "sk" to "Eslovaco",
            "slk" to "Eslovaco",
            "slo" to "Eslovaco",
            "sl" to "Esloveno",
            "slv" to "Esloveno"
        )
        
        fun languageCodeToName(code: String): String {
            val lowerCode = code.lowercase()
            return languageNames[lowerCode] ?: code.uppercase()
        }
    }
}