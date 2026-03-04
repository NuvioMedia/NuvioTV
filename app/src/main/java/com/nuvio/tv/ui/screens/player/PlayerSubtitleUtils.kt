package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes

internal object PlayerSubtitleUtils {
    fun normalizeLanguageCode(lang: String): String {
        val code = lang.trim().lowercase()
        if (code.isBlank()) return ""

        val normalizedCode = code.replace('_', '-')
        val tokenized = normalizedCode
            .replace('-', ' ')
            .replace('.', ' ')
            .replace('/', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        fun containsAny(vararg values: String): Boolean = values.any { value ->
            tokenized.contains(value)
        }

        if (containsAny("portuguese", "portugues")) {
            if (containsAny("brazil", "brasil", "brazilian", "brasileiro", "pt br", "ptbr", "pob")) {
                return "pt-br"
            }
            if (containsAny("portugal", "european", "europeu", "iberian", "pt pt", "ptpt")) {
                return "pt"
            }
            return "pt"
        }

        // Normalize Serbo-Croatian (HBS) macrolanguage tags and common scene variants.
        // HBS is ISO 639-3 for Serbo-Croatian, a macrolanguage that includes Bosnian (bos),
        // Croatian (hrv), Montenegrin (cnr) and Serbian (srp).
        if (containsAny("hbs", "serbo croatian", "serbo-croatian", "bosnian/croatian/serbian", "bcs", "bhs")) {
            // If a specific member language is hinted (e.g. HBS-HRV), prefer that.
            return when {
                containsAny("hrv", "croat", "hr ") || normalizedCode.startsWith("hrv-") || normalizedCode.startsWith("hr-") -> "hr"
                containsAny("srp", "serb", "sr ") || normalizedCode.startsWith("srp-") || normalizedCode.startsWith("sr-") -> "sr"
                containsAny("bos", "bosn", "bs ") || normalizedCode.startsWith("bos-") || normalizedCode.startsWith("bs-") -> "bs"
                containsAny("cnr", "montenegrin", "mne") || normalizedCode.startsWith("cnr-") -> "cnr"
                else -> "hbs"
            }
        }

        return when (code) {
            "pt-br", "pt_br", "br", "pob" -> "pt-br"
            "pt", "pt-pt", "pt_pt", "por" -> "pt"
            "eng" -> "en"
            "spa" -> "es"
            "fre", "fra" -> "fr"
            "ger", "deu" -> "de"
            "ita" -> "it"
            "rus" -> "ru"
            "jpn" -> "ja"
            "kor" -> "ko"
            "chi", "zho" -> "zh"
            "ara" -> "ar"
            "hin" -> "hi"
            "nld", "dut" -> "nl"
            "pol" -> "pl"
            "swe" -> "sv"
            "nor" -> "no"
            "dan" -> "da"
            "fin" -> "fi"
            "tur" -> "tr"
            "ell", "gre" -> "el"
            "heb" -> "he"
            "tha" -> "th"
            "vie" -> "vi"
            "ind" -> "id"
            "msa", "may" -> "ms"
            "ces", "cze" -> "cs"
            "hun" -> "hu"
            "ron", "rum" -> "ro"
            "ukr" -> "uk"
            "bul" -> "bg"
            "hrv" -> "hr"
            "srp" -> "sr"
            "slk", "slo" -> "sk"
            "slv" -> "sl"
            else -> normalizedCode
        }
    }

    fun matchesLanguageCode(language: String?, target: String): Boolean {
        if (language.isNullOrBlank()) return false
        val normalizedLanguage = normalizeLanguageCode(language)
        val normalizedTarget = normalizeLanguageCode(target)
        if (normalizedTarget == "pt") {
            return normalizedLanguage == "pt"
        }
        return normalizedLanguage == normalizedTarget ||
            normalizedLanguage.startsWith("$normalizedTarget-") ||
            normalizedLanguage.startsWith("${normalizedTarget}_")
    }

    fun mimeTypeFromUrl(url: String): String {
        val normalizedPath = url
            .substringBefore('#')
            .substringBefore('?')
            .lowercase()

        return when {
            normalizedPath.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            normalizedPath.endsWith(".vtt") || normalizedPath.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            normalizedPath.endsWith(".ass") || normalizedPath.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            normalizedPath.endsWith(".ttml") || normalizedPath.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}
