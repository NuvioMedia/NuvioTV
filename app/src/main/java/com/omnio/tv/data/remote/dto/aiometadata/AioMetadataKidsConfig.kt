package com.omnio.tv.data.remote.dto.aiometadata

import com.omnio.tv.domain.model.AgeRatingTier

/**
 * Builds a Kids-profile AIOMetadata configuration by deriving from the Main
 * profile's existing config:
 *  - the Main profile's API keys (TMDB, TVDB, Fanart, MAL, etc.) are preserved
 *  - TMDB movie catalogs gain a US `certification.lte` filter scaled to the
 *    profile's maxAgeRating ceiling, plus genre exclusions tailored to the
 *    same ceiling
 *  - TMDB tv catalogs are tighter: TMDB has no built-in cert filter for
 *    series, so for the lower tiers we force `with_genres` to the family /
 *    animation / kids triplet (TMDB IDs 10751 / 16 / 10762). For PG-13+ we
 *    fall back to a broader without-list — at that point a few mature shows
 *    may slip through but the detail-screen safety net catches playback.
 *
 * If [sourceConfig] is null (Main has never been configured), the build
 * function falls back to an empty starting point — the caller can layer the
 * default template on top before saving.
 */
object AioMetadataKidsConfig {

    private const val CERT_COUNTRY = "US"

    // TMDB movie genre IDs.
    private const val MOVIE_HORROR = "27"
    private const val MOVIE_THRILLER = "53"
    private const val MOVIE_CRIME = "80"
    private const val MOVIE_WAR = "10752"
    private const val MOVIE_WESTERN = "37"

    // TMDB tv genre IDs.
    private const val TV_ACTION_ADVENTURE = "10759"
    private const val TV_ANIMATION = "16"
    private const val TV_CRIME = "80"
    private const val TV_MYSTERY = "9648"
    private const val TV_WAR_POLITICS = "10768"
    private const val TV_WESTERN = "37"
    private const val TV_FAMILY = "10751"
    private const val TV_KIDS = "10762"

    fun build(sourceConfig: AioConfigInnerDto?, maxAgeRating: AgeRatingTier?): AioConfigInnerDto {
        val source = sourceConfig ?: AioConfigInnerDto()
        val filters = filtersFor(maxAgeRating)
        val filteredCatalogs = source.catalogs.map { applyKidsFiltersToCatalog(it, filters) }

        return AioConfigInnerDto(
            providers = source.providers,
            apiKeys = source.apiKeys,
            catalogs = filteredCatalogs,
            settings = source.settings,
        )
    }

    private data class TierFilters(
        val movieCertificationLte: String?,
        val movieWithoutGenres: List<String>,
        val tvWithGenres: List<String>?, // null = no with_genres restriction
        val tvWithoutGenres: List<String>,
    )

    private fun filtersFor(tier: AgeRatingTier?): TierFilters {
        // Strictest two tiers (G/PG): force TV to family/animation/kids and
        // exclude a wide swath of mature-leaning genres on both axes.
        // Middle tiers (PG-13/TV-14): keep the cert ladder for movies, drop
        // the with-list for TV so non-cartoon kid-appropriate shows can come
        // through, but still exclude the obviously adult genres.
        // Top tiers (R/NC-17): we don't really expect Kids profiles to pick
        // these — fall through to the same shape as middle tiers.
        return when (tier) {
            AgeRatingTier.G, AgeRatingTier.PG -> TierFilters(
                movieCertificationLte = if (tier == AgeRatingTier.G) "G" else "PG",
                movieWithoutGenres = listOf(MOVIE_HORROR, MOVIE_THRILLER, MOVIE_CRIME, MOVIE_WAR, MOVIE_WESTERN),
                tvWithGenres = listOf(TV_FAMILY, TV_ANIMATION, TV_KIDS),
                tvWithoutGenres = listOf(TV_ACTION_ADVENTURE, TV_CRIME, TV_MYSTERY, TV_WAR_POLITICS, TV_WESTERN),
            )
            AgeRatingTier.PG_13 -> TierFilters(
                movieCertificationLte = "PG-13",
                movieWithoutGenres = listOf(MOVIE_HORROR, MOVIE_THRILLER, MOVIE_WAR),
                tvWithGenres = null,
                tvWithoutGenres = listOf(TV_CRIME, TV_WAR_POLITICS),
            )
            AgeRatingTier.TV_14 -> TierFilters(
                // TMDB cert ladder for movies stops at PG-13 below R; map
                // TV-14 to PG-13 to stay conservative for movies.
                movieCertificationLte = "PG-13",
                movieWithoutGenres = listOf(MOVIE_HORROR, MOVIE_WAR),
                tvWithGenres = null,
                tvWithoutGenres = listOf(TV_WAR_POLITICS),
            )
            AgeRatingTier.R, AgeRatingTier.NC_17 -> TierFilters(
                movieCertificationLte = null,
                movieWithoutGenres = emptyList(),
                tvWithGenres = null,
                tvWithoutGenres = emptyList(),
            )
            null -> TierFilters(
                // No tier specified — be conservative anyway so callers that
                // miswire never accidentally serve unfiltered catalogs to a
                // Kids profile.
                movieCertificationLte = "PG",
                movieWithoutGenres = listOf(MOVIE_HORROR, MOVIE_THRILLER, MOVIE_CRIME, MOVIE_WAR),
                tvWithGenres = listOf(TV_FAMILY, TV_ANIMATION, TV_KIDS),
                tvWithoutGenres = listOf(TV_ACTION_ADVENTURE, TV_CRIME, TV_WAR_POLITICS),
            )
        }
    }

    private fun applyKidsFiltersToCatalog(
        catalog: Map<String, Any?>,
        filters: TierFilters,
    ): Map<String, Any?> {
        val metadata = catalog["metadata"] as? Map<String, Any?> ?: return catalog
        val discover = metadata["discover"] as? Map<String, Any?> ?: return catalog
        val mediaType = discover["mediaType"] as? String
        val source = discover["source"] as? String

        // Only TMDB-sourced discover catalogs carry the params we know how to
        // tune. Non-TMDB catalogs (Trakt addons etc.) are out of scope here —
        // the client-side KidsContentFilter + detail-screen block remain the
        // safety net for anything that bypasses our discover params.
        if (source != "tmdb") return catalog

        val params = (discover["params"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        params["include_adult"] = false
        when (mediaType) {
            "movie" -> {
                if (filters.movieCertificationLte != null) {
                    params["certification_country"] = CERT_COUNTRY
                    params["certification.lte"] = filters.movieCertificationLte
                }
                if (filters.movieWithoutGenres.isNotEmpty()) {
                    appendCommaSeparated(params, "without_genres", filters.movieWithoutGenres.joinToString(","))
                }
            }
            "tv" -> {
                if (filters.tvWithGenres != null) {
                    // TMDB joins with_genres values with `|` for OR semantics.
                    // We replace any existing list (rather than appending) so
                    // catalogs the user originally tuned for, say, an Action
                    // niche don't conflict — for a Kids profile we want
                    // family/animation/kids regardless of the source niche.
                    params["with_genres"] = filters.tvWithGenres.joinToString("|")
                }
                if (filters.tvWithoutGenres.isNotEmpty()) {
                    appendCommaSeparated(params, "without_genres", filters.tvWithoutGenres.joinToString(","))
                }
            }
        }

        val formState = (discover["formState"] as? Map<String, Any?>)?.toMutableMap()
        if (formState != null) {
            formState["includeAdult"] = false
            if (mediaType == "movie" && filters.movieCertificationLte != null) {
                formState["certificationCountry"] = CERT_COUNTRY
                formState["maxCertification"] = filters.movieCertificationLte
            }
        }

        val newDiscover = discover.toMutableMap().apply {
            this["params"] = params
            if (formState != null) this["formState"] = formState
        }
        val newMetadata = metadata.toMutableMap().apply {
            this["discover"] = newDiscover
        }
        return catalog.toMutableMap().apply {
            this["metadata"] = newMetadata
        }
    }

    private fun appendCommaSeparated(
        params: MutableMap<String, Any?>,
        key: String,
        valuesToAdd: String,
    ) {
        val current = params[key] as? String
        val combined = if (current.isNullOrBlank()) valuesToAdd else "$current,$valuesToAdd"
        val deduped = combined.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        params[key] = deduped.joinToString(",")
    }
}
