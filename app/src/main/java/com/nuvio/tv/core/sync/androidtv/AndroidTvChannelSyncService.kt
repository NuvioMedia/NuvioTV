package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import android.util.Log
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TvChannelSync"
private const val DEBOUNCE_MS = 2_000L

/**
 * Keeps the Android TV "Continue Watching" preview channel in sync with the app's
 * CW enrichment cache. Uses the same enriched data that the in-app Continue Watching
 * section displays, respecting user settings (days cap, show unaired, etc.).
 *
 * Items are sourced from [ContinueWatchingEnrichmentCache] which is populated by the
 * HomeViewModel CW pipeline. This ensures the launcher channel shows the same items
 * the user sees in the app UI.
 *
 * Before publishing new programs, all stale programs are removed first to avoid
 * the launcher showing outdated tiles while new ones are being inserted.
 */
@Singleton
class AndroidTvChannelSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: AndroidTvChannelManager,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val tvRecommendationManager: TvRecommendationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The launcher channel is only visible while the app is in the background. Reconciling on
    // every in-playback cache bump (~10s) thrashes the TvProvider and stops launchers like
    // Projectivy from repainting. We instead reconcile once when the app goes to background
    // (user returns to the launcher) — see [onForegroundChanged].
    @Volatile private var appInForeground = false

    /** Called from the host Activity's onStart/onStop. On background we reconcile once so the
     *  channel reflects the latest watch progress exactly as the launcher regains foreground. */
    fun onForegroundChanged(foreground: Boolean) {
        val wasForeground = appInForeground
        appInForeground = foreground
        if (wasForeground && !foreground) {
            scope.launch { reconcileFromCache() }
        }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (!manager.isSupported()) {
            Log.d(TAG, "Non-leanback device; channel sync skipped")
            return
        }
        TvChannelRefreshJobService.schedulePeriodic(context)

        // Populate the channel once on startup from the current cache.
        scope.launch { reconcileFromCache() }

        scope.launch {
            // Observe cache snapshot updates and settings changes to trigger reconciliation.
            // snapshotVersion bumps every time the CW pipeline writes new data to disk cache.
            // We drop the initial value (0) to avoid reconciling with stale disk cache before
            // the pipeline has had a chance to produce fresh data.
            combine(
                cwEnrichmentCache.snapshotVersion
                    .dropWhile { it == 0 },
                traktSettingsDataStore.continueWatchingDaysCap,
                traktSettingsDataStore.dismissedNextUpKeys,
                layoutPreferenceDataStore.useEpisodeThumbnailsInCw
            ) { _, daysCap, dismissed, useEpisodeThumbnails ->
                ChannelSettingsSnapshot(daysCap, dismissed, useEpisodeThumbnails)
            }
                .debounce(DEBOUNCE_MS)
                .collect { settings ->
                    // Skip while the app is foregrounded — the launcher channel isn't visible
                    // then, and reconciling on every ~10s in-playback cache bump thrashes the
                    // provider (and stops launchers like Projectivy from repainting). The
                    // background transition (onForegroundChanged) + periodic job cover it.
                    if (appInForeground) return@collect
                    reconcileFromCache(settings)
                }
        }
    }

    /**
     * Reads the CW enrichment cache and reconciles the launcher channel.
     * Called both from the live observer and from [TvChannelRefreshJobService].
     */
    suspend fun reconcileFromCache(settings: ChannelSettingsSnapshot? = null) {
        val resolvedSettings = settings ?: run {
            val daysCap = traktSettingsDataStore.continueWatchingDaysCap.first()
            val dismissed = traktSettingsDataStore.dismissedNextUpKeys.first()
            val useEpisodeThumbnails = layoutPreferenceDataStore.useEpisodeThumbnailsInCw.first()
            ChannelSettingsSnapshot(daysCap, dismissed, useEpisodeThumbnails)
        }

        val inProgressItems = runCatching { cwEnrichmentCache.getInProgressSnapshot() }
            .getOrDefault(emptyList())
        val nextUpItems = runCatching { cwEnrichmentCache.getNextUpSnapshot() }
            .getOrDefault(emptyList())

        val channelItems = buildChannelItems(inProgressItems, nextUpItems, resolvedSettings)

        Log.d(
            TAG,
            "Reconciling from cache: ${channelItems.size} items " +
                "(${inProgressItems.size} in-progress, ${nextUpItems.size} next-up raw)"
        )
        manager.reconcile(channelItems)

        val cutoffMs = if (resolvedSettings.daysCap == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL) {
            null
        } else {
            val windowMs = resolvedSettings.daysCap.toLong() * 24L * 60L * 60L * 1000L
            System.currentTimeMillis() - windowMs
        }
        val watchNextInProgress = inProgressItems
            .filter { cutoffMs == null || it.lastWatched >= cutoffMs }

        runCatching {
            val cwItems = watchNextInProgress.map {
                ContinueWatchingItem.InProgress(it.toWatchProgress(resolvedSettings.useEpisodeThumbnails))
            }
            tvRecommendationManager.updateWatchNextFromCwItems(cwItems)
        }
    }

    /**
     * Merges in-progress and next-up cached items into a single list of [WatchProgress]
     * suitable for the launcher channel, applying user settings:
     * - Days cap filtering
     * - Dismissed next-up filtering
     * - Unreleased/unaired filtering (next-up items that haven't aired yet are excluded
     *   to avoid showing content that can't be played)
     * - Deduplication (in-progress takes priority over next-up for same contentId)
     */
    private fun buildChannelItems(
        inProgress: List<CachedInProgressItem>,
        nextUp: List<CachedNextUpItem>,
        settings: ChannelSettingsSnapshot
    ): List<WatchProgress> {
        val cutoffMs = if (settings.daysCap == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL) {
            null
        } else {
            val windowMs = settings.daysCap.toLong() * 24L * 60L * 60L * 1000L
            System.currentTimeMillis() - windowMs
        }

        // Filter in-progress items by days cap
        val filteredInProgress = inProgress
            .filter { cutoffMs == null || it.lastWatched >= cutoffMs }

        // Filter next-up items: exclude unaired (launcher should only show playable content),
        // respect days cap and dismissed keys
        val filteredNextUp = nextUp
            .filter { it.hasAired }  // Never show unaired in launcher — can't be played
            .filter { cutoffMs == null || it.lastWatched >= cutoffMs }
            .filter { nextUpDismissKey(it) !in settings.dismissedNextUp }

        // Deduplicate: in-progress wins over next-up for same contentId
        val inProgressContentIds = filteredInProgress.mapTo(mutableSetOf()) { it.contentId }
        val deduplicatedNextUp = filteredNextUp.filter { it.contentId !in inProgressContentIds }

        // Sort: in-progress by lastWatched, next-up by sortTimestamp (which is
        // releaseTimestamp for release alerts, lastWatched for regular next-up).
        // This mirrors the in-app CW order for aired content in both sort modes.
        data class SortableItem(val watchProgress: WatchProgress, val sortKey: Long)

        val inProgressSorted = filteredInProgress.map { item ->
            SortableItem(item.toWatchProgress(settings.useEpisodeThumbnails), item.lastWatched)
        }

        val nextUpSorted = deduplicatedNextUp.map { item ->
            SortableItem(item.toWatchProgress(settings.useEpisodeThumbnails), item.sortTimestamp)
        }

        return (inProgressSorted + nextUpSorted)
            .sortedByDescending { it.sortKey }
            .map { it.watchProgress }
            .distinctBy { it.contentId }
    }

    private fun nextUpDismissKey(item: CachedNextUpItem): String {
        return buildString {
            append(item.contentId)
            if (item.seedSeason != null) {
                append("_s${item.seedSeason}")
                if (item.seedEpisode != null) append("e${item.seedEpisode}")
            }
        }
    }

    data class ChannelSettingsSnapshot(
        val daysCap: Int,
        val dismissedNextUp: Set<String>,
        val useEpisodeThumbnails: Boolean
    )
}

/**
 * Converts a [CachedInProgressItem] to [WatchProgress] for the launcher channel.
 */
private fun CachedInProgressItem.toWatchProgress(useEpisodeThumbnails: Boolean): WatchProgress {
    val image = if (useEpisodeThumbnails) {
        episodeThumbnail ?: backdrop
    } else {
        backdrop ?: episodeThumbnail
    }
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = image,
        logo = logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = progressPercent
    )
}

/**
 * Converts a [CachedNextUpItem] to [WatchProgress] for the launcher channel.
 * Next-up items don't have playback position, so they appear as "0% watched"
 * which launchers typically render without a progress bar.
 */
private fun CachedNextUpItem.toWatchProgress(useEpisodeThumbnails: Boolean): WatchProgress {
    val image = if (useEpisodeThumbnails) {
        thumbnail ?: backdrop
    } else {
        backdrop ?: thumbnail
    }
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = image,
        logo = logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = 0,
        duration = 0,
        lastWatched = sortTimestamp
    )
}
