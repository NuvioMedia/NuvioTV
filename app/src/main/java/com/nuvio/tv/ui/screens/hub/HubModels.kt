package com.nuvio.tv.ui.screens.hub

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.MetaPreview

/** Which merged hub this is: every movie catalog, or every series/tv catalog. */
enum class HubKind { MOVIES, TV }

/** One catalog feeding a hub — the per-service source behind the filter chips. */
@Immutable
data class HubSource(
    /** "${addonId}_${apiType}_${catalogId}" — matches Home's catalogKey(). */
    val key: String,
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    /** catalogName with a trailing " - Movie/Series/TV/Anime" stripped, for the chip row. */
    val sourceLabel: String,
    val type: String,
    val supportsSkip: Boolean,
    val skipStep: Int
)

@Immutable
data class HubUiState(
    val kind: HubKind = HubKind.MOVIES,
    val sources: List<HubSource> = emptyList(),
    /** null => "All" (the merged grid); a HubSource key => that single source's grid. */
    val selectedSourceKey: String? = null,
    /** Already sliced by [selectedSourceKey]. */
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null
)

/**
 * Cross-source identity for merged-grid dedup. Prefer IMDb id when present
 * (tmdb-discover-plus item ids ARE tt... ids) so the same title from "Netflix"
 * and "Prime" catalogs collapses to one poster; the apiType prefix keeps a
 * movie and a series with the same id distinct.
 */
internal fun hubDedupKey(item: MetaPreview): String {
    val id = item.imdbId?.takeIf { it.isNotBlank() } ?: item.id
    return "${item.apiType}:$id"
}

internal fun hubSourceLabel(catalogName: String): String =
    catalogName
        .replaceFirst(Regex("\\s*-\\s*(movie|series|tv|anime)\\s*$", RegexOption.IGNORE_CASE), "")
        .trim()
        .ifBlank { catalogName }
