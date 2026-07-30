package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress

/**
 * Resolves the episode-specific videoId used when launching streams from Continue Watching.
 *
 * The meta addon owns the real episode video IDs (including anime trackers like
 * `mal:` / `kitsu:` that have no season segment). Do not invent IDs such as
 * `contentId:season:episode` — that breaks those trackers and can disagree with
 * what the meta catalog actually exposes.
 *
 * Preference rules:
 * - Remote progress (Trakt / Simkl): always prefer a non-blank meta video id when
 *   available (existing 0.8 behavior).
 * - Local progress: prefer meta only when the persisted videoId is missing or is
 *   just the series-level [contentId] (legacy snapshots that never stored an
 *   episode-specific id). Otherwise keep the stored episode videoId.
 */
internal fun resolveContinueWatchingVideoId(
    progressVideoId: String,
    contentId: String,
    source: String,
    metaVideoId: String?
): String {
    val fromMeta = metaVideoId?.takeIf { it.isNotBlank() }
        ?: return progressVideoId.ifBlank { contentId }

    val preferMeta = source != WatchProgress.SOURCE_LOCAL ||
        progressVideoId.isBlank() ||
        progressVideoId == contentId

    return if (preferMeta) fromMeta else progressVideoId
}
