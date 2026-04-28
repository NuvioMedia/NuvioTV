package com.nuvio.tv.ui.util

import android.content.Context
import com.nuvio.tv.R
import java.util.Locale

private val EPISODE_PATTERN = Regex("^Episode (\\d+)$", RegexOption.IGNORE_CASE)

fun String.localizeEpisodeTitle(context: Context): String {
    val match = EPISODE_PATTERN.matchEntire(this.trim()) ?: return this
    val number = match.groupValues[1]
    return "${context.getString(R.string.episodes_episode)} $number"
}

internal val LANGUAGE_OVERRIDES = mapOf(
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
    "es-419" to "es-419",
    "es_419" to "es-419",
    "es-la" to "es-419",
    "es-lat" to "es-419",
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
    "slv" to "sl",
    "zht" to "zh-TW",
    "zhs" to "zh-CN",
    "chi-tw" to "zh-TW",
    "chi-cn" to "zh-CN",
    "zh-tw" to "zh-TW",
    "zh_tw" to "zh-TW",
    "zh-cn" to "zh-CN",
    "zh_cn" to "zh-CN",
    "cat" to "ca",
    "alb" to "sq",
    "sqi" to "sq",
    "bos" to "bs",
    "mac" to "mk",
    "mkd" to "mk",
    "lav" to "lv",
    "lit" to "lt",
    "est" to "et",
    "isl" to "is",
    "ice" to "is",
    "glg" to "gl",
    "baq" to "eu",
    "eus" to "eu",
    "wel" to "cy",
    "cym" to "cy",
    "gle" to "ga",
    "ben" to "bn",
    "tam" to "ta",
    "tel" to "te",
    "mal" to "ml",
    "kan" to "kn",
    "mar" to "mr",
    "pan" to "pa",
    "guj" to "gu",
    "urd" to "ur",
    "fas" to "fa",
    "per" to "fa",
    "amh" to "am",
    "swa" to "sw",
    "zul" to "zu",
    "afr" to "af",
    "mlt" to "mt",
    "bel" to "be",
    "geo" to "ka",
    "kat" to "ka",
    "arm" to "hy",
    "hye" to "hy",
    "aze" to "az",
    "kaz" to "kk",
    "uzb" to "uz",
    "mon" to "mn",
    "khm" to "km",
    "lao" to "lo",
    "mya" to "my",
    "bur" to "my",
    "sin" to "si",
    "nep" to "ne",
    "tgl" to "tl",
    "fil" to "tl"
)

fun languageCodeToName(code: String): String {
    val lowerCode = code.lowercase()
    if (lowerCode == "none") return "None"
    val bcp47 = LANGUAGE_OVERRIDES[lowerCode] ?: lowerCode
    return try {
        val locale = Locale.forLanguageTag(bcp47)
        val name = if (locale.country.isNotEmpty()) {
            val displayName = locale.getDisplayName(Locale.getDefault())
            // Locale.forLanguageTag("es-419") returns country="419" which Android may not resolve
            if (displayName.contains("419")) {
                val langName = locale.getDisplayLanguage(Locale.getDefault())
                    .replaceFirstChar { it.uppercase() }
                "$langName (Latinoamérica)"
            } else {
                displayName
            }
        } else {
            locale.getDisplayLanguage(Locale.getDefault())
        }
        if (name.isNotBlank() && name != bcp47) name.replaceFirstChar { it.uppercase() }
        else code.uppercase()
    } catch (_: Exception) {
        code.uppercase()
    }
}
