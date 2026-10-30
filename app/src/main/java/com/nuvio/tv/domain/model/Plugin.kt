package com.nuvio.tv.domain.model

import com.squareup.moshi.JsonClass

/**
 * Repository type distinguishing native JS plugins from external DEX extensions.
 */
enum class RepositoryType {
    NUVIO_JS,
    EXTERNAL_DEX
}

/**
 * Plugin info returned from Supabase sync, with optional type hint.
 */
data class RemotePluginInfo(
    val url: String,
    val repoType: String? = null
)

/**
 * Represents a plugin repository containing scrapers
 */
data class PluginRepository(
    val id: String,
    val name: String,
    val url: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val lastUpdated: Long = 0L,
    val scraperCount: Int = 0,
    val type: RepositoryType = RepositoryType.NUVIO_JS
)

/**
 * Represents manifest.json from a plugin repository
 */
@JsonClass(generateAdapter = true)
data class PluginManifest(
    val name: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val scrapers: List<ScraperManifestInfo>
)

/**
 * Scraper info from manifest.json
 */
@JsonClass(generateAdapter = true)
data class ScraperManifestInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: String,
    val filename: String,
    val supportedTypes: List<String> = listOf("movie", "tv"),
    val enabled: Boolean = true,
    val logo: String? = null,
    val contentLanguage: List<String>? = null,
    val supportedPlatforms: List<String>? = null,
    val disabledPlatforms: List<String>? = null,
    val formats: List<String>? = null,
    val supportedFormats: List<String>? = null,
    val supportsExternalPlayer: Boolean? = null,
    val limited: Boolean? = null
)

/**
 * Installed scraper info with runtime state
 */
data class ScraperInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val filename: String,
    val supportedTypes: List<String>,
    val enabled: Boolean,
    val manifestEnabled: Boolean,
    val logo: String?,
    val contentLanguage: List<String>,
    val repositoryId: String,
    val formats: List<String>?,
    val type: RepositoryType = RepositoryType.NUVIO_JS
) {
    fun supportsType(type: String): Boolean {
        val normalizedType = normalizePluginType(type)
        return supportedTypes.any { normalizePluginType(it) == normalizedType }
    }
}

private fun normalizePluginType(value: String): String =
    when (value.lowercase()) {
        "series", "show", "other" -> "tv"
        else -> value.lowercase()
    }

/**
 * Result from a local scraper execution
 */
data class LocalScraperResult(
    val title: String,
    val name: String? = null,
    val url: String,
    val quality: String? = null,
    val size: String? = null,
    val language: String? = null,
    val provider: String? = null,
    val type: String? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val infoHash: String? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<Subtitle> = emptyList(),
    val audioDelayMs: Int? = null,
    val audioDelayLanguage: String? = null
)

/**
 * Manifest format for external extension repositories.
 */
@JsonClass(generateAdapter = true)
data class ExternalRepoManifest(
    val name: String,
    val description: String? = null,
    val manifestVersion: Int = 1,
    val pluginLists: List<String>
)

/**
 * Entry for an individual extension in an external repository's plugins list.
 */
@JsonClass(generateAdapter = true)
data class ExternalPluginEntry(
    val name: String,
    val internalName: String,
    val description: String? = null,
    val version: Int = 1,
    val apiVersion: Int = 1,
    val status: Int = 1,
    val authors: List<String>? = null,
    val tvTypes: List<String>? = null,
    val iconUrl: String? = null,
    val url: String,
    val fileSize: Long? = null,
    val repositoryUrl: String? = null
)

const val PLUGIN_BINGE_GROUP_PREFIX = "local-plugin-"
private const val PLUGIN_BINGE_GROUP_SEPARATOR = "|"

/**
 * Binge group for one plugin stream.
 * Format: `local-plugin-{scraperId}|{sorted identity tokens}`.
 * Tokens are the plugin's own name, title, language, and quality. Episode-specific
 * pieces are not stripped here; [indexOfPluginBingeMatch] drops whatever the next
 * episode's own results no longer share.
 */
fun pluginBingeGroup(
    scraperId: String,
    url: String? = null,
    name: String? = null,
    title: String? = null,
    language: String? = null,
    quality: String? = null
): String {
    val tokens = pluginIdentityTokens(name, title, language, quality)
    return if (tokens.isEmpty()) {
        "$PLUGIN_BINGE_GROUP_PREFIX$scraperId"
    } else {
        "$PLUGIN_BINGE_GROUP_PREFIX$scraperId$PLUGIN_BINGE_GROUP_SEPARATOR${tokens.joinToString(" ")}"
    }
}

/** Normalized identity tokens from the fields a plugin actually fills in. */
fun pluginIdentityTokens(
    name: String? = null,
    title: String? = null,
    language: String? = null,
    quality: String? = null
): List<String> = tokenizePluginText(name, title, language, quality)

fun scraperIdFromPluginBingeGroup(bingeGroup: String?): String? {
    val value = bingeGroup?.trim().orEmpty()
    if (!value.startsWith(PLUGIN_BINGE_GROUP_PREFIX)) return null
    return value.removePrefix(PLUGIN_BINGE_GROUP_PREFIX)
        .substringBefore(PLUGIN_BINGE_GROUP_SEPARATOR)
        .takeIf { it.isNotBlank() }
}

/**
 * Index of the next-episode stream that continues the saved plugin choice.
 * Tokens shared by every candidate are that episode's boilerplate. Tokens present in the
 * saved choice but in none of the candidates were episode-specific and are ignored.
 * What remains is the plugin's own variant, in whatever language it uses.
 */
fun indexOfPluginBingeMatch(targetGroup: String?, candidateGroups: List<String?>): Int? {
    val targetId = scraperIdFromPluginBingeGroup(targetGroup) ?: return null
    val indexed = candidateGroups.mapIndexedNotNull { index, group ->
        if (scraperIdFromPluginBingeGroup(group) == targetId) index to tokensOfPluginBingeGroup(group) else null
    }
    if (indexed.isEmpty()) return null
    val saved = tokensOfPluginBingeGroup(targetGroup)
    if (saved.isEmpty()) return indexed.first().first
    if (indexed.size == 1) {
        val only = indexed.first()
        val required = saved.filter { token -> token.none(Char::isDigit) || isResolutionToken(token) }
        return if (required.all(only.second::contains)) only.first else null
    }

    val sets = indexed.map { it.second }
    val union = sets.reduce(Set<String>::plus)
    val shared = sets.reduce(Set<String>::intersect)
    val distinctive = saved.intersect(union) - shared
    val savedResolution = saved.filter(::isResolutionToken).toSet()
    val availableResolution = savedResolution.intersect(union)
    // The quality that was playing has to be in the candidate. A higher quality listed
    // first is a different variant, not a match.
    if (savedResolution.isNotEmpty() && availableResolution.isEmpty()) return null

    val resolutionEligible = indexed.filter { (_, tokens) ->
        availableResolution.all(tokens::contains)
    }
    if (resolutionEligible.isEmpty()) return null
    val labelDistinctive = distinctive - savedResolution
    val fullLabel = resolutionEligible.filter { (_, tokens) ->
        labelDistinctive.all(tokens::contains)
    }
    if (fullLabel.isNotEmpty()) return closestPluginVariant(fullLabel, shared)

    return resolutionEligible
        .map { (index, tokens) ->
            val matched = labelDistinctive.intersect(tokens)
            val wordHits = matched.count { it.any(Char::isLetter) }
            Triple(index, wordHits, matched.size)
        }
        .filter { it.third > 0 }
        .maxWithOrNull(compareBy({ it.second }, { it.third }))
        ?.first
}

private fun closestPluginVariant(
    candidates: List<Pair<Int, Set<String>>>,
    shared: Set<String>
): Int? = candidates.minWithOrNull(
    compareBy<Pair<Int, Set<String>>> { (_, tokens) -> (tokens - shared).size }
        .thenBy { it.first }
)?.first

fun pluginBingeGroupMatches(candidate: String?, target: String?): Boolean {
    val left = candidate?.trim().orEmpty()
    val right = target?.trim().orEmpty()
    if (left.isEmpty() || right.isEmpty()) return false
    if (left == right) return true
    return indexOfPluginBingeMatch(right, listOf(left)) != null
}

private fun tokensOfPluginBingeGroup(bingeGroup: String?): Set<String> {
    val value = bingeGroup?.trim().orEmpty()
    val payload = value.substringAfter(PLUGIN_BINGE_GROUP_SEPARATOR, "")
    if (payload.isBlank()) return emptySet()
    return payload.split(' ')
        .map { canonicalizePluginToken(it) }
        .filter { it.isNotEmpty() }
        .toSet()
}

private fun tokenizePluginText(vararg parts: String?): List<String> {
    val tokens = linkedSetOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isEmpty()) return
        val token = canonicalizePluginToken(current.toString())
        if (token.isNotEmpty()) tokens += token
        current.clear()
    }
    for (part in parts) {
        if (part.isNullOrBlank()) continue
        for (char in part) {
            val lower = char.lowercaseChar()
            if (lower.isLetterOrDigit()) current.append(lower) else flush()
        }
        flush()
    }
    return tokens.sorted()
}

private val RESOLUTION_HEIGHTS = setOf(360, 480, 540, 576, 720, 800, 900, 1080, 1440, 2160, 4320)

private fun isResolutionToken(token: String): Boolean {
    if (token.all(Char::isDigit)) {
        val height = token.toIntOrNull() ?: return false
        return height in RESOLUTION_HEIGHTS
    }
    if (token.length >= 2 && token.last() == 'k' && token.dropLast(1).all(Char::isDigit)) {
        val multiplier = token.dropLast(1).toIntOrNull() ?: return false
        return multiplier in setOf(1, 2, 4, 8)
    }
    return false
}

private fun canonicalizePluginToken(token: String): String {
    var digits = 0
    while (digits < token.length && token[digits].isDigit()) digits++
    val numberOnly = digits == token.length
    val numberAndP = digits == token.length - 1 && token[digits] == 'p'
    return if (digits > 0 && (numberOnly || numberAndP)) token.substring(0, digits) else token
}

/**
 * Convert LocalScraperResult to Stream
 */
fun LocalScraperResult.toStream(scraper: ScraperInfo): com.nuvio.tv.domain.model.Stream {
    val displayTitle = buildString {
        append(title)
        if (!quality.isNullOrBlank() && !title.contains(quality)) {
            append(" $quality")
        }
    }
    
    val displayName = buildString {
        append(name ?: scraper.name)
        if (!quality.isNullOrBlank() && !(name ?: "").contains(quality)) {
            append(" - $quality")
        }
    }
    
    return Stream(
        name = displayName,
        title = displayTitle,
        description = size,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = pluginBingeGroup(
                scraperId = scraper.id,
                url = url,
                name = name,
                title = title,
                language = language,
                quality = quality
            ),
            countryWhitelist = null,
            proxyHeaders = headers?.let { ProxyHeaders(request = it, response = null) },
            audioDelayMs = audioDelayMs,
            audioDelayLanguage = audioDelayLanguage
        ),
        addonName = scraper.name,
        addonLogo = scraper.logo,
        subtitles = subtitles
    )
}
