package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.ui.util.LANGUAGE_OVERRIDES
import java.text.Normalizer
import java.util.Locale

internal object PlayerSubtitleUtils {
    const val LANGUAGE_MATCH_EXACT = 100
    const val LANGUAGE_MATCH_ALIAS = 90
    const val LANGUAGE_MATCH_GENERIC_FALLBACK = 75
    const val LANGUAGE_MATCH_RELATED_VARIANT = 50

    private val TRACK_TIME_REGEX = Regex("""\d+:\d+""")
    private val TRACK_NUMBER_REGEX = Regex("""#?\d+""")

    data class NormalizedLanguage(
        val raw: String,
        val tag: String,
        val base: String,
        val region: String? = null,
        val script: String? = null,
        val isGeneric: Boolean = false
    )

    fun normalizeLanguageCode(lang: String): String {
        normalizeLanguage(lang)?.let { return it.tag }

        val code = lang.trim().lowercase(Locale.ROOT)
        if (code.isBlank()) return ""
        return LANGUAGE_OVERRIDES[code]?.lowercase(Locale.ROOT) ?: code.replace('_', '-')
    }

    fun matchesLanguageCode(language: String?, target: String): Boolean {
        if (language.isNullOrBlank()) return false
        if (scoreLanguageMatch(language, target) >= LANGUAGE_MATCH_GENERIC_FALLBACK) return true

        val subtags = language.trim().lowercase(Locale.ROOT)
            .replace('_', '-')
            .split('-', '.', '/', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (subtags.size <= 1) return false

        return subtags.drop(1).any { subtag ->
            subtag.length == 3 &&
                scoreLanguageMatch(normalizeLanguageCode(subtag), target) >= LANGUAGE_MATCH_GENERIC_FALLBACK
        }
    }

    fun scoreLanguageMatch(language: String?, target: String): Int {
        if (language.isNullOrBlank() || target.isBlank()) return 0

        val candidate = normalizeLanguage(language) ?: return 0
        val desired = normalizeLanguage(target) ?: return 0
        if (candidate.tag == desired.tag) {
            return if (candidate.isExactTagFor(desired.tag) || candidate.raw.equals(desired.raw, ignoreCase = true)) {
                LANGUAGE_MATCH_EXACT
            } else {
                LANGUAGE_MATCH_ALIAS
            }
        }

        if (candidate.base != desired.base) return 0

        // Forced subtitle providers often collapse regional preferences into generic labels:
        // pt-BR can arrive as Portuguese/por, and es-419 can arrive as Spanish/spa.
        // Keep generic fallback below explicit regional aliases so specific tracks still win.
        if (candidate.isGeneric && !desired.isGeneric) return LANGUAGE_MATCH_GENERIC_FALLBACK
        if (!candidate.isGeneric && desired.isGeneric) {
            return if (candidate.base == "pt" || candidate.base == "es") {
                LANGUAGE_MATCH_RELATED_VARIANT
            } else {
                LANGUAGE_MATCH_GENERIC_FALLBACK
            }
        }
        return LANGUAGE_MATCH_RELATED_VARIANT
    }

    fun scoreLanguageMatch(
        language: String?,
        name: String?,
        trackId: String?,
        target: String
    ): Int {
        val detectedVariant = detectTrackLanguageVariant(language, name, trackId)
            .takeIf { it.isNotBlank() }
        val scores = listOfNotNull(detectedVariant, language, name, trackId)
            .map { scoreLanguageMatch(it, target) }
        val bestSpecific = scores.maxOrNull() ?: 0
        if (bestSpecific >= LANGUAGE_MATCH_ALIAS) return bestSpecific
        if (scores.any { it == LANGUAGE_MATCH_RELATED_VARIANT }) return LANGUAGE_MATCH_RELATED_VARIANT
        return bestSpecific
    }

    fun normalizeLanguage(language: String): NormalizedLanguage? {
        val raw = language.trim()
        if (raw.isBlank()) return null

        val code = raw.lowercase(Locale.ROOT).replace('_', '-')
        val override = LANGUAGE_OVERRIDES[code]?.lowercase(Locale.ROOT)
        val canonicalCode = if (code == "pt-pt") code else override ?: code
        fun result(
            tag: String,
            base: String = tag.substringBefore('-'),
            region: String? = null,
            script: String? = null,
            generic: Boolean = false
        ) = NormalizedLanguage(raw, tag, base, region, script, generic)

        when (canonicalCode) {
            "pt-br" -> return result("pt-br", region = "BR")
            "pt-pt" -> return result("pt-pt", region = "PT")
            "pt", "por" -> return result("pt", generic = true)
            "pob" -> return result("pt-br", region = "BR")
            "es-419" -> return result("es-419", region = "419")
            "es-la", "es-lat", "es-mx" -> return result("es-419", region = "419")
            "es-es" -> return result("es-es", region = "ES")
            "es", "spa" -> return result("es", generic = true)
            "zh-hans", "zh-cn", "zh-sg" -> return result("zh-hans", base = "zh", script = "Hans")
            "zh-hant", "zh-tw", "zh-hk", "zh-mo" -> return result("zh-hant", base = "zh", script = "Hant")
            "zh-yue", "yue" -> return result("zh-yue", base = "zh")
            "cmn" -> return result("zh-cmn", base = "zh")
            "zh", "chi", "zho" -> return result("zh", generic = true)
            "fr-ca" -> return result("fr-ca", region = "CA")
            "fr-fr" -> return result("fr-fr", region = "FR")
            "fr", "fre", "fra" -> return result("fr", generic = true)
            "nb", "nb-no" -> return result("nb", base = "no")
            "nn", "nn-no" -> return result("nn", base = "no")
            "no", "nor" -> return result("no", generic = true)
            "sr-cyrl" -> return result("sr-cyrl", base = "sr", script = "Cyrl")
            "sr-latn" -> return result("sr-latn", base = "sr", script = "Latn")
            "sr", "srp" -> return result("sr", generic = true)
            "de", "de-de" -> return result("de", region = "DE", generic = canonicalCode == "de")
            "de-at" -> return result("de-at", base = "de", region = "AT")
            "de-ch" -> return result("de-ch", base = "de", region = "CH")
            "nl-be" -> return result("nl-be", region = "BE")
            "nl-nl" -> return result("nl-nl", region = "NL")
            "nl", "dut", "nld" -> return result("nl", generic = true)
            "en", "eng" -> return result("en", generic = true)
            "ar", "ara" -> return result("ar", generic = true)
            "fa", "fas", "per" -> return result("fa", generic = true)
            "he", "iw" -> return result("he", generic = true)
            "id", "in" -> return result("id", generic = true)
            "ms", "msa", "may" -> return result("ms", generic = true)
            "jv", "jw" -> return result("jv", generic = true)
            "fil", "tl" -> return result("fil", generic = true)
            "el", "gr" -> return result("el", generic = true)
            "ro", "mo" -> return result("ro", generic = true)
        }

        val compact = canonicalCode.replace(Regex("[^a-z0-9]+"), "")
        val text = searchableLanguageText(raw)

        if (compact in setOf("ptbr", "pob") ||
            text.containsAny(
                "portuguese brazil",
                "portuguese br",
                "portuguese (br)",
                "portugues brasil",
                "portugues br",
                "brazilian portuguese",
                "portugues brasileiro"
            )
        ) return result("pt-br", region = "BR")
        if (compact == "ptpt" ||
            text.containsAny("portuguese portugal", "portugues portugal", "european portuguese", "portugues europeu")
        ) return result("pt-pt", region = "PT")
        if (text.containsAny("portuguese", "portugues")) return result("pt", generic = true)

        if (compact in setOf("es419", "esla", "eslat", "esmx") ||
            text.containsAny("latin american spanish", "spanish latin america", "spanish latino", "espanol latino", "latinoamerica")
        ) return result("es-419", region = "419")
        if (text.containsAny("spanish spain", "castilian", "castellano", "espanol espana")) {
            return result("es-es", region = "ES")
        }
        if (text.containsAny("spanish", "espanol")) return result("es", generic = true)

        if (text.containsAny("chinese simplified", "mandarin simplified", "simplified", "简体")) {
            return result("zh-hans", base = "zh", script = "Hans")
        }
        if (text.containsAny("chinese traditional", "traditional", "繁體", "繁体")) {
            return result("zh-hant", base = "zh", script = "Hant")
        }
        if (text.containsAny("cantonese", "yue", "粵語", "广东话", "廣東話")) return result("zh-yue", base = "zh")
        if (text.containsAny("mandarin", "cmn", "putonghua", "普通话", "國語")) return result("zh-cmn", base = "zh")
        if (text.contains("chinese")) return result("zh", generic = true)

        if (text.containsAny("french canadian", "canadian french", "francais canada", "quebecois", "quebec french")) {
            return result("fr-ca", region = "CA")
        }
        if (text.containsAny("french france", "francais france")) return result("fr-fr", region = "FR")
        if (text.containsAny("french", "francais")) return result("fr", generic = true)

        if (text.containsAny("nynorsk", "norwegian nynorsk", "norsk nynorsk")) return result("nn", base = "no")
        if (text.containsAny("bokmal", "bokmål", "norwegian bokmal", "norwegian bokmål", "norsk bokmal", "norsk bokmål")) {
            return result("nb", base = "no")
        }
        if (text.containsAny("norwegian", "norsk")) return result("no", generic = true)

        if (text.containsAny("serbian cyrillic", "српски", "cyrillic")) return result("sr-cyrl", base = "sr", script = "Cyrl")
        if (text.containsAny("serbian latin", "srpski latin")) return result("sr-latn", base = "sr", script = "Latn")
        if (text.contains("serbian")) return result("sr", generic = true)

        if (text.containsAny("flemish", "vlaams", "belgian dutch")) return result("nl-be", region = "BE")
        if (text.containsAny("dutch netherlands", "nederlands")) return result("nl-nl", region = "NL")
        if (text.contains("dutch")) return result("nl", generic = true)

        if (text.containsAny("persian", "farsi", "فارسی")) return result("fa", generic = true)
        if (text.containsAny("hebrew", "עברית")) return result("he", generic = true)
        if (text.containsAny("indonesian", "bahasa indonesia")) return result("id", generic = true)
        if (text.containsAny("malay", "bahasa melayu")) return result("ms", generic = true)
        if (text.contains("javanese")) return result("jv", generic = true)
        if (text.containsAny("filipino", "pilipino", "tagalog")) return result("fil", generic = true)
        if (text.containsAny("greek", "ελληνικά")) return result("el", generic = true)
        if (text.contains("romanian")) return result("ro", generic = true)
        if (text.contains("german")) return result("de", generic = true)
        if (text.contains("english")) return result("en", generic = true)
        if (text.contains("arabic")) return result("ar", generic = true)

        val parts = canonicalCode.split('-').filter { it.isNotBlank() }
        val base = parts.firstOrNull() ?: return null
        if (base.length !in 2..3 || !base.all { it in 'a'..'z' }) return null
        return result(
            tag = canonicalCode,
            base = base,
            region = parts.getOrNull(1)?.uppercase(Locale.ROOT)
        )
    }

    /**
     * Detects the regional variant of an embedded subtitle track by inspecting
     * its name, language, and trackId fields. Returns a normalized language key
     * that preserves the accent (e.g. "pt-br", "es-419") when detectable,
     * or falls back to the base language code.
     */
    fun detectTrackLanguageVariant(language: String?, name: String?, trackId: String?): String {
        val normalizedLanguage = language?.let { normalizeLanguage(it) }
        val descriptorText = searchableLanguageText(listOfNotNull(name, trackId).joinToString(" "))
        val haystack = searchableLanguageText(listOfNotNull(name, language, trackId).joinToString(" "))
        val descriptorHasBrazilian = descriptorText.containsAnyTag(BRAZILIAN_TAGS)
        val descriptorHasEuropeanPortuguese = descriptorText.containsAnyTag(EUROPEAN_PT_TAGS)
        val hasBrazilian = haystack.containsAnyTag(BRAZILIAN_TAGS)
        val hasEuropeanPortuguese = haystack.containsAnyTag(EUROPEAN_PT_TAGS)
        val isPortugueseTrack = normalizedLanguage?.base == "pt" ||
            haystack.containsAny("portuguese", "portugues")
        if (descriptorHasBrazilian && !descriptorHasEuropeanPortuguese) return "pt-br"
        if (isPortugueseTrack && descriptorHasEuropeanPortuguese && !descriptorHasBrazilian) return "pt-pt"
        if (!isPortugueseTrack && hasBrazilian && !hasEuropeanPortuguese) return "pt-br"
        if (isPortugueseTrack) {
            if (hasBrazilian && !hasEuropeanPortuguese) return "pt-br"
            if (hasEuropeanPortuguese && !hasBrazilian) return "pt-pt"
        }

        val hasLatino = haystack.containsAnyTag(LATINO_TAGS)
        val hasCastilian = haystack.containsAnyTag(CASTILIAN_TAGS)
        val isSpanishTrack = normalizedLanguage?.base == "es" ||
            haystack.containsAny("spanish", "espanol")
        if (isSpanishTrack) {
            if (hasLatino && !hasCastilian) return "es-419"
            if (hasCastilian && !hasLatino) return "es-es"
        }

        return listOfNotNull(name, language, trackId)
            .mapNotNull { normalizeLanguage(it) }
            .maxByOrNull { if (it.isGeneric) 0 else 1 }
            ?.tag
            ?: normalizeLanguageCode(language ?: "")
    }

    internal val BRAZILIAN_TAGS = listOf(
        "pt-br", "pt_br", "pob", "brazilian", "brazil", "brasil", "brasileiro", " br", "(br)"
    )
    internal val EUROPEAN_PT_TAGS = listOf(
        "pt-pt", "pt_pt", "iberian", "european", "portugal", "europeu", " eu", "(eu)"
    )
    internal val LATINO_TAGS = listOf(
        "es-419", "es_419", "es-la", "es-lat", "es-mx", "latino", "latinoamerica",
        "latinoamericano", "latam", "lat am", "latin america"
    )
    internal val CASTILIAN_TAGS = listOf(
        "es-es", "es_es", "castilian", "castellano", "spain", "españa", "espana", "iberian"
    )

    fun mimeTypeFromUrl(url: String): String {
        val normalizedPath = url
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .lowercase(Locale.ROOT)

        return when {
            normalizedPath.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            normalizedPath.endsWith(".vtt") || normalizedPath.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            normalizedPath.endsWith(".ass") || normalizedPath.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            normalizedPath.endsWith(".ttml") || normalizedPath.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun NormalizedLanguage.isExactTagFor(targetTag: String): Boolean {
        return raw.lowercase(Locale.ROOT).replace('_', '-') == targetTag
    }

    private fun searchableLanguageText(value: String): String {
        val lower = value.lowercase(Locale.ROOT)
        val ascii = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return "$lower $ascii"
            .replace('-', ' ')
            .replace('_', ' ')
            .replace('.', ' ')
            .replace('/', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.containsAny(vararg values: String): Boolean {
        return values.any { value -> contains(value) }
    }

    private fun String.containsAnyTag(tags: List<String>): Boolean {
        return tags.any { tag ->
            val searchableTag = searchableLanguageText(tag)
            if (searchableTag.length <= 2) {
                Regex("""(^|\s)${Regex.escape(searchableTag)}($|\s)""").containsMatchIn(this)
            } else {
                contains(searchableTag)
            }
        }
    }

    fun isTechnicalTrackLabel(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim()
        return TRACK_TIME_REGEX.matches(trimmed) ||
            TRACK_NUMBER_REGEX.matches(trimmed)
    }
}
