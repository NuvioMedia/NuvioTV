package com.omnio.tv.data.remote.dto.aiometadata

import com.omnio.tv.domain.model.AgeRatingTier

/**
 * Builds a Kids-profile AIOMetadata configuration by deriving from the Main
 * profile's existing config:
 *  - the Main profile's API keys (TMDB, TVDB, Fanart, MAL, etc.) are preserved
 *  - TMDB movie catalogs gain a US `certification.lte` filter scaled to the
 *    profile's maxAgeRating ceiling, so the addon never hands back over-rated
 *    items in the first place
 *  - both movie and tv catalogs gain a defensive `without_genres` block for
 *    Horror to keep stragglers off the home rows
 *
 * If [sourceConfig] is null (Main has never been configured), the build
 * function falls back to an empty starting point — the caller can layer the
 * default template on top before saving.
 */
object AioMetadataKidsConfig {

    private const val CERT_COUNTRY = "US"
    private const val WITHOUT_GENRES_MOVIE = "27" // Horror
    private const val WITHOUT_GENRES_TV = "10759,10768" // Action & Adventure, War & Politics

    fun build(sourceConfig: AioConfigInnerDto?, maxAgeRating: AgeRatingTier?): AioConfigInnerDto {
        val source = sourceConfig ?: AioConfigInnerDto()
        val movieCertCeiling = maxAgeRating?.let(::tmdbMovieCertificationFor)
        val filteredCatalogs = source.catalogs.map { applyKidsFiltersToCatalog(it, movieCertCeiling) }

        return AioConfigInnerDto(
            providers = source.providers,
            apiKeys = source.apiKeys,
            catalogs = filteredCatalogs,
            settings = source.settings,
        )
    }

    private fun tmdbMovieCertificationFor(tier: AgeRatingTier): String = when (tier) {
        AgeRatingTier.G -> "G"
        AgeRatingTier.PG -> "PG"
        AgeRatingTier.PG_13 -> "PG-13"
        // TMDB cert ladder doesn't carry a TV-14 step for movies — fall through
        // to PG-13 to stay conservative.
        AgeRatingTier.TV_14 -> "PG-13"
        AgeRatingTier.R -> "R"
        AgeRatingTier.NC_17 -> "NC-17"
    }

    private fun applyKidsFiltersToCatalog(
        catalog: Map<String, Any?>,
        movieCertCeiling: String?,
    ): Map<String, Any?> {
        val metadata = catalog["metadata"] as? Map<String, Any?> ?: return catalog
        val discover = metadata["discover"] as? Map<String, Any?> ?: return catalog
        val mediaType = discover["mediaType"] as? String
        val source = discover["source"] as? String

        // Only TMDB-sourced discover catalogs carry the params we know how to tune.
        if (source != "tmdb") return catalog

        val params = (discover["params"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        params["include_adult"] = false
        when (mediaType) {
            "movie" -> {
                if (movieCertCeiling != null) {
                    params["certification_country"] = CERT_COUNTRY
                    params["certification.lte"] = movieCertCeiling
                }
                appendCommaSeparated(params, "without_genres", WITHOUT_GENRES_MOVIE)
            }
            "tv" -> {
                appendCommaSeparated(params, "without_genres", WITHOUT_GENRES_TV)
            }
        }

        val formState = (discover["formState"] as? Map<String, Any?>)?.toMutableMap()
        if (formState != null) {
            formState["includeAdult"] = false
            if (mediaType == "movie" && movieCertCeiling != null) {
                formState["certificationCountry"] = CERT_COUNTRY
                formState["maxCertification"] = movieCertCeiling
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
