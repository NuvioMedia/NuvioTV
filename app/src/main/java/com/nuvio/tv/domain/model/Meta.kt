package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Meta(
    val id: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val status: String? = null,
    val imdbRating: Float?,
    val genres: List<String>,
    val runtime: String?,
    val director: List<String>,
    val writer: List<String> = emptyList(),
    val cast: List<String>,
    val castMembers: List<MetaCastMember> = emptyList(),
    val videos: List<Video>,
    val productionCompanies: List<MetaCompany> = emptyList(),
    val networks: List<MetaCompany> = emptyList(),
    val ageRating: String? = null,
    val country: String?,
    val awards: String?,
    val language: String?,
    val links: List<MetaLink>,
    val trailerYtIds: List<String> = emptyList(),
    val imdbId: String? = null,
    val slug: String? = null,
    val released: String? = null,
    val landscapePoster: String? = null,
    val rawPosterUrl: String? = null,
    val behaviorHints: MetaBehaviorHints? = null,
    val trailers: List<MetaTrailer> = emptyList(),
    val releaseDates: List<MetaReleaseDateCountry> = emptyList(),
    val hasPoster: Boolean? = null,
    val hasBackground: Boolean? = null,
    val hasLandscapePoster: Boolean? = null,
    val hasLogo: Boolean? = null,
    val hasLinks: Boolean? = null,
    val hasVideos: Boolean? = null
) {
    val apiType: String
        get() = type.toApiString(rawType)

    val backdropUrl: String?
        get() = background ?: landscapePoster ?: poster

    /**
     * Returns the list of watchable episodes — non-special (season > 0),
     * excluding entire seasons where the first episode is not yet available
     * (either via the `available` flag or because its release date is in the future).
     */
    fun watchableEpisodes(): List<Video> {
        val today = java.time.LocalDate.now()
        val candidates = videos.filter {
            it.season != null && it.episode != null && (it.season ?: 0) > 0
        }
        val unavailableSeasons = candidates.groupBy { it.season }
            .filter { (_, eps) ->
                val first = eps.minByOrNull { it.episode ?: Int.MAX_VALUE }
                    ?: return@filter false
                // Exclude if explicitly marked unavailable
                if (first.available == false) return@filter true
                // Exclude if release date is in the future
                val released = first.released?.substringBefore('T')?.trim()
                if (!released.isNullOrBlank()) {
                    try {
                        return@filter java.time.LocalDate.parse(
                            released,
                            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                        ).isAfter(today)
                    } catch (_: java.time.format.DateTimeParseException) { }
                }
                false
            }.keys
        return if (unavailableSeasons.isEmpty()) candidates
        else candidates.filter { it.season !in unavailableSeasons }
    }
}

/**
 * Resolves the original content language as an ISO 639-1 code.
 * Falls back to [country]-based inference when [Meta.language] is absent.
 */
fun Meta.resolveContentLanguage(): String? {
    normalizeLanguageCode(language)?.let { return it }
    countryToLanguageCode(country)?.let { return it }
    return null
}

/** Normalize a language string (full name, ISO 639-2/3, etc.) to ISO 639-1 when possible. */
internal fun normalizeLanguageCode(language: String?): String? {
    val normalized = language?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    if (normalized.length == 2) return normalized
    return LANGUAGE_NORMALIZATION_MAP[normalized] ?: normalized
}

/** Best-effort mapping from country name/code to ISO 639-1 primary language. */
internal fun countryToLanguageCode(country: String?): String? {
    val normalized = country?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return COUNTRY_TO_LANGUAGE_MAP[normalized]
}

private val LANGUAGE_NORMALIZATION_MAP = mapOf(
    // ISO 639-2 / 639-3
    "jpn" to "ja", "kor" to "ko", "zho" to "zh", "chi" to "zh",
    "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de",
    "ita" to "it", "spa" to "es", "por" to "pt", "rus" to "ru",
    "hin" to "hi", "tur" to "tr", "pol" to "pl", "nld" to "nl",
    "dut" to "nl", "swe" to "sv", "nor" to "no", "dan" to "da",
    "fin" to "fi", "tha" to "th", "ara" to "ar", "heb" to "he",
    "ces" to "cs", "cze" to "cs", "ron" to "ro", "rum" to "ro",
    "hun" to "hu", "ukr" to "uk", "ell" to "el", "gre" to "el",
    "eng" to "en", "vie" to "vi", "ind" to "id", "msa" to "ms",
    "may" to "ms", "tam" to "ta", "tel" to "te", "ben" to "bn",
    // Full names
    "japanese" to "ja", "korean" to "ko", "chinese" to "zh",
    "mandarin" to "zh", "cantonese" to "zh", "french" to "fr",
    "german" to "de", "italian" to "it", "spanish" to "es",
    "portuguese" to "pt", "russian" to "ru", "hindi" to "hi",
    "turkish" to "tr", "polish" to "pl", "dutch" to "nl",
    "swedish" to "sv", "norwegian" to "no", "danish" to "da",
    "finnish" to "fi", "thai" to "th", "arabic" to "ar",
    "hebrew" to "he", "czech" to "cs", "romanian" to "ro",
    "hungarian" to "hu", "ukrainian" to "uk", "greek" to "el",
    "english" to "en", "vietnamese" to "vi", "indonesian" to "id",
    "malay" to "ms", "tamil" to "ta", "telugu" to "te",
    "bengali" to "bn"
)

private val COUNTRY_TO_LANGUAGE_MAP = mapOf(
    // ISO 3166-1 alpha-2
    "jp" to "ja", "kr" to "ko", "cn" to "zh", "tw" to "zh",
    "fr" to "fr", "de" to "de", "it" to "it", "es" to "es",
    "pt" to "pt", "br" to "pt", "ru" to "ru", "in" to "hi",
    "tr" to "tr", "pl" to "pl", "nl" to "nl", "se" to "sv",
    "no" to "no", "dk" to "da", "fi" to "fi", "th" to "th",
    "il" to "he", "cz" to "cs", "ro" to "ro", "hu" to "hu",
    "ua" to "uk", "gr" to "el",
    // ISO 3166-1 alpha-3
    "jpn" to "ja", "kor" to "ko", "chn" to "zh", "twn" to "zh",
    "fra" to "fr", "deu" to "de", "ita" to "it", "esp" to "es",
    "prt" to "pt", "bra" to "pt", "rus" to "ru", "ind" to "hi",
    "tur" to "tr", "pol" to "pl", "nld" to "nl", "swe" to "sv",
    "nor" to "no", "dnk" to "da", "fin" to "fi", "tha" to "th",
    "isr" to "he", "cze" to "cs", "rou" to "ro", "hun" to "hu",
    "ukr" to "uk", "grc" to "el",
    // Common full names
    "japan" to "ja", "south korea" to "ko", "korea" to "ko",
    "china" to "zh", "taiwan" to "zh", "france" to "fr",
    "germany" to "de", "italy" to "it", "spain" to "es",
    "portugal" to "pt", "brazil" to "pt", "russia" to "ru",
    "india" to "hi", "turkey" to "tr", "poland" to "pl",
    "netherlands" to "nl", "sweden" to "sv", "norway" to "no",
    "denmark" to "da", "finland" to "fi", "thailand" to "th",
    "israel" to "he", "romania" to "ro", "hungary" to "hu",
    "ukraine" to "uk", "greece" to "el"
)

@Immutable
data class MetaCastMember(
    val name: String,
    val character: String? = null,
    val photo: String? = null,
    val tmdbId: Int? = null
)

@Immutable
data class MetaCompany(
    val name: String,
    val logo: String? = null,
    val tmdbId: Int? = null
)

@Immutable
data class Video(
    val id: String,
    val title: String,
    val released: String?,
    val thumbnail: String?,
    val streams: List<Stream> = emptyList(),
    val season: Int?,
    val episode: Int?,
    val overview: String?,
    val runtime: Int? = null, // episode runtime in minutes
    val available: Boolean? = null
)

@Immutable
data class MetaLink(
    val name: String,
    val category: String,
    val url: String
)

@Immutable
data class MetaBehaviorHints(
    val defaultVideoId: String? = null,
    val hasScheduledVideos: Boolean? = null
)

@Immutable
data class MetaTrailer(
    val source: String? = null,
    val type: String? = null,
    val name: String? = null,
    val ytId: String? = null,
    val lang: String? = null
)

@Immutable
data class MetaReleaseDateCountry(
    val countryCode: String,
    val releaseDates: List<MetaReleaseDate> = emptyList()
)

@Immutable
data class MetaReleaseDate(
    val certification: String? = null,
    val descriptors: List<String> = emptyList(),
    val languageCode: String? = null,
    val note: String? = null,
    val releaseDate: String? = null,
    val type: Int? = null
)
