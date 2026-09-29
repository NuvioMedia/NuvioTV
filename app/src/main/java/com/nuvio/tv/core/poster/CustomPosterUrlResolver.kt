package com.nuvio.tv.core.poster

/**
 * Resolves custom poster URL patterns by replacing placeholders with actual content IDs.
 *
 * Supported placeholders:
 *
 * **Nuvio native (recommended for new integrations):**
 * - `{id}`        - full Nuvio meta ID (e.g. "tt0137523", "tmdb:1396", "kitsu:7442")
 * - `{id_type}`   - ID namespace: "imdb", "tmdb", "kitsu", "anilist", "mal", "tvdb", "anidb"
 * - `{typed_id}`  - ID formatted for RPDB-style services without namespace prefix: "tt0137523" for IMDb,
 *                   "movie-1396" / "series-1396" for TMDB/TVDB/etc.
 * - `{type}`      - content type: "movie" or "series"
 * - `{shape}`     - poster shape: "poster", "landscape", or "square"
 *
 * **Per-service compatibility:**
 * - `{imdb_id}`    - IMDb ID (e.g. "tt0137523")
 * - `{tmdb_id}`    - TMDB numeric ID (e.g. "1396")
 * - `{tvdb_id}`    - TVDB numeric ID
 * - `{kitsu_id}`   - Kitsu numeric ID
 * - `{anilist_id}` - AniList numeric ID
 * - `{mal_id}`     - MyAnimeList numeric ID
 * - `{anidb_id}`   - AniDB numeric ID
 *
 * Every placeholder also has an **optional** form: `{imdb_id?}`.
 * Required placeholders with no value cause the resolver to return `null` (fallback to original art).
 * Optional placeholders with no value resolve to an empty string.
 *
 * **Pipe syntax (supported ID types):** `{imdb_id|kitsu_id|tvdb_id}` declares which ID types the
 * service supports. The resolver uses the available ID if it matches one of the declared types.
 * If the content's ID type is not in the list, the pattern returns `null` - no request is made,
 * avoiding unnecessary network traffic for unsupported ID types.
 *
 * **RPDB-family auto-detection:** URLs containing "ratingposterdb.com", "aioratings.com",
 * or "top-posters.com" automatically try alternative ID types when the primary ID is unavailable
 * (imdb -> tmdb -> tvdb), matching the behaviour of aiometadata.
 */
object CustomPosterUrlResolver {

    /**
     * All IDs extracted from a content item, used by the resolver.
     */
    data class ContentIds(
        val id: String,
        val imdbId: String? = null,
        val tmdbId: String? = null,
        val tvdbId: String? = null,
        val kitsuId: String? = null,
        val anilistId: String? = null,
        val malId: String? = null,
        val anidbId: String? = null
    )

    /**
     * Resolve a custom poster URL pattern.
     *
     * @param pattern URL pattern with `{placeholder}` tokens
     * @param ids content IDs extracted from the meta item
     * @param type content type ("movie" or "series")
     * @param shape poster shape ("poster", "landscape", or "square")
     * @return resolved URL, or `null` if a required placeholder has no value
     */
    fun resolve(
        pattern: String,
        ids: ContentIds,
        type: String,
        shape: String = "poster"
    ): String? {
        if (pattern.isBlank()) return null

        val result = if (isRpdbFamily(pattern)) {
            resolveRpdbWithFallback(pattern, ids, type, shape)
        } else {
            resolvePattern(pattern, ids, type, shape)
        }

        return result
    }

    /**
     * Extract [ContentIds] from a Nuvio meta ID and optional explicit imdbId field.
     *
     * Examples:
     * - `"tt0137523"` -> imdbId = "tt0137523"
     * - `"tmdb:1396"` -> tmdbId = "1396"
     * - `"kitsu:7442"` -> kitsuId = "7442"
     */
    fun extractIds(metaId: String, explicitImdbId: String? = null): ContentIds {
        var imdbId: String? = null
        var tmdbId: String? = null
        var tvdbId: String? = null
        var kitsuId: String? = null
        var anilistId: String? = null
        var malId: String? = null
        var anidbId: String? = null

        when {
            metaId.startsWith("tt") -> imdbId = metaId
            metaId.startsWith("tmdb:") -> tmdbId = metaId.removePrefix("tmdb:")
            metaId.startsWith("tvdb:") -> tvdbId = metaId.removePrefix("tvdb:")
            metaId.startsWith("kitsu:") -> kitsuId = metaId.removePrefix("kitsu:")
            metaId.startsWith("anilist:") -> anilistId = metaId.removePrefix("anilist:")
            metaId.startsWith("mal:") -> malId = metaId.removePrefix("mal:")
            metaId.startsWith("anidb:") -> anidbId = metaId.removePrefix("anidb:")
        }

        if (!explicitImdbId.isNullOrBlank()) {
            imdbId = explicitImdbId
        }

        return ContentIds(
            id = metaId,
            imdbId = imdbId,
            tmdbId = tmdbId,
            tvdbId = tvdbId,
            kitsuId = kitsuId,
            anilistId = anilistId,
            malId = malId,
            anidbId = anidbId
        )
    }

    // -- Internal --

    private fun resolvePattern(
        pattern: String,
        ids: ContentIds,
        type: String,
        shape: String
    ): String? {
        val (rawId, rawIdType) = extractRawIdAndType(ids)
        val typedId = formatTypedId(rawId, rawIdType, type)

        val idLookup = mapOf(
            "id" to ids.id,
            "id_type" to rawIdType,
            "typed_id" to typedId,
            "type" to type,
            "shape" to shape,
            "imdb_id" to (ids.imdbId ?: ""),
            "tmdb_id" to (ids.tmdbId ?: ""),
            "tvdb_id" to (ids.tvdbId ?: ""),
            "kitsu_id" to (ids.kitsuId ?: ""),
            "anilist_id" to (ids.anilistId ?: ""),
            "mal_id" to (ids.malId ?: ""),
            "anidb_id" to (ids.anidbId ?: "")
        )

        var url = resolvePipePlaceholders(pattern, idLookup) ?: return null

        for ((key, value) in idLookup) {
            val placeholder = "{$key}"
            val optional = "{$key?}"
            if (optional in url) {
                url = url.replace(optional, value)
                continue
            }
            if (placeholder in url) {
                if (value.isBlank()) return null // required but missing - fallback
                url = url.replace(placeholder, value)
            }
        }

        return url
    }

    /**
     * Resolves pipe-syntax placeholders: `{imdb_id|kitsu_id|tvdb_id}`.
     * Declares supported ID types - uses the available one if it matches. Returns null if none match.
     */
    private fun resolvePipePlaceholders(pattern: String, lookup: Map<String, String>): String? {
        var url = pattern
        val pipeRegex = Regex("""\{([a-z_]+(?:\|[a-z_]+)+)\}""")

        for (match in pipeRegex.findAll(pattern)) {
            val fullToken = match.value          // e.g. "{imdb_id|kitsu_id|tvdb_id}"
            val keys = match.groupValues[1].split("|")  // ["imdb_id", "kitsu_id", "tvdb_id"]
            val resolved = keys.firstOrNull { key -> lookup[key]?.isNotBlank() == true }
                ?.let { lookup[it] }
            if (resolved.isNullOrBlank()) return null // none of the declared IDs available
            url = url.replace(fullToken, resolved)
        }

        return url
    }

    /**
     * Extracts the raw ID (without namespace prefix) and the ID type string.
     * For `{id}` placeholder we strip the prefix so that Nuvio-native URLs get a clean numeric ID,
     * while `{imdb_id}` etc. keep the full value including "tt" prefix.
     */
    private fun extractRawIdAndType(ids: ContentIds): Pair<String, String> {
        val metaId = ids.id
        return when {
            metaId.startsWith("tt") -> metaId to "imdb"
            metaId.contains(":") -> {
                val colonIndex = metaId.indexOf(':')
                val prefix = metaId.substring(0, colonIndex)
                val rawId = metaId.substring(colonIndex + 1)
                rawId to prefix
            }
            else -> metaId to "unknown"
        }
    }

    /**
     * Formats the ID for RPDB-style services without namespace prefix.
     * IMDb IDs stay as-is ("tt0137523"). TMDB and TVDB get a type prefix ("movie-1396",
     * "series-81189") as required by RPDB/aioratings. All others return the raw numeric ID.
     */
    private fun formatTypedId(rawId: String, idType: String, contentType: String): String {
        if (idType == "imdb") return rawId
        if (idType == "tmdb" || idType == "tvdb") {
            val prefix = if (contentType == "movie") "movie" else "series"
            return "$prefix-$rawId"
        }
        return rawId
    }

    // -- RPDB-family fallback --

    private val RPDB_DOMAINS = listOf(
        "ratingposterdb.com",
        "aioratings.com",
        "top-posters.com",
        "btttr.cc"
    )

    private fun isRpdbFamily(pattern: String): Boolean =
        RPDB_DOMAINS.any { domain -> domain in pattern }

    /**
     * For RPDB-compatible services, try the pattern as-is, then swap the ID type segment
     * and placeholder to try alternative IDs: imdb -> tmdb -> tvdb (and permutations).
     */
    private fun resolveRpdbWithFallback(
        pattern: String,
        ids: ContentIds,
        type: String,
        shape: String
    ): String? {
        // Try pattern as written
        val direct = resolvePattern(pattern, ids, type, shape)
        if (direct != null) return direct

        val typePrefix = if (type == "movie") "movie" else "series"

        // Build fallback variants by swapping the ID type and placeholder
        val fallbacks = mutableListOf<String>()

        if ("{imdb_id}" in pattern) {
            // Original uses imdb — try tmdb, then tvdb
            if (!ids.tmdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/imdb/", "/tmdb/")
                    .replace("{imdb_id}", "$typePrefix-${ids.tmdbId}")
            }
            if (!ids.tvdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/imdb/", "/tvdb/")
                    .replace("{imdb_id}", "$typePrefix-${ids.tvdbId}")
            }
        } else if ("{tmdb_id}" in pattern || "{type}-{tmdb_id}" in pattern) {
            if (!ids.imdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/tmdb/", "/imdb/")
                    .replace("$typePrefix-{tmdb_id}", ids.imdbId)
                    .replace("{tmdb_id}", ids.imdbId)
            }
            if (!ids.tvdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/tmdb/", "/tvdb/")
                    .replace("{tmdb_id}", ids.tvdbId)
            }
        } else if ("{tvdb_id}" in pattern || "{type}-{tvdb_id}" in pattern) {
            if (!ids.imdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/tvdb/", "/imdb/")
                    .replace("$typePrefix-{tvdb_id}", ids.imdbId)
                    .replace("{tvdb_id}", ids.imdbId)
            }
            if (!ids.tmdbId.isNullOrBlank()) {
                fallbacks += pattern
                    .replace("/tvdb/", "/tmdb/")
                    .replace("{tvdb_id}", ids.tmdbId)
            }
        }

        for (fb in fallbacks) {
            val resolved = resolvePattern(fb, ids, type, shape)
            if (resolved != null) return resolved
        }

        return null
    }
}
