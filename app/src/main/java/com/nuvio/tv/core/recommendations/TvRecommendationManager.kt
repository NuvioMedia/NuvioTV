package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.pm.PackageManager
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level coordinator that orchestrates channel creation, program publishing,
 * and Watch Next row updates for Android TV Home Screen recommendations.
 *
 * All public methods are safe to call from any dispatcher — heavy work is
 * dispatched to [Dispatchers.IO] internally.
 */
@Singleton
class TvRecommendationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelManager: ChannelManager,
    private val programBuilder: ProgramBuilder,
    private val dataStore: RecommendationDataStore,
    private val watchProgressRepository: WatchProgressRepository
) {

    /** Serializes channel-update operations to avoid races from multiple triggers. */
    private val mutex = Mutex()

    /** Tracks the last set of items per channel to avoid redundant ContentProvider writes. */
    private val channelSignatures = mutableMapOf<String, String>()

    // ────────────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────────────

    /**
     * One-time initialization — clears orphan channels not in the user's enabled catalogs.
     * Called from [NuvioApplication.onCreate].
     */
    suspend fun initializeChannels() {
        if (!isTvDevice()) return
        withContext(Dispatchers.IO) {
            try {
                // Determine which catalogs are valid
                val validIds = dataStore.getEnabledCatalogs().toList()
                channelManager.cleanupLegacyChannels(validIds)
                
                // Force sync Watch Next items right on startup to refresh launcher UI and bust caches
                updateWatchNext()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Updates an arbitrary TV channel for a catalog. 
     * Called from [HomeViewModel] after catalog rows are loaded.
     */
    suspend fun updateCatalogChannel(catalogKey: String, catalogName: String, items: List<MetaPreview>) {
        if (!shouldRun()) return
        
        // Ensure this catalog is still chosen by the user
        val enabledCatalogs = dataStore.getEnabledCatalogs()
        if (!enabledCatalogs.contains(catalogKey)) return

        val maxItems = dataStore.getMaxItemsPerChannel()
        val useWidePoster = dataStore.getUseWidePoster()

        val trimmed = items.take(maxItems) // Dynamic Max Limit
        val signature = trimmed.joinToString("|") { it.id } + "_wide_$useWidePoster"
        if (signature == channelSignatures[catalogKey]) return

        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val channelId = channelManager.getOrCreateChannel(catalogKey, catalogName) ?: return@withContext
                    channelManager.clearProgramsForChannel(channelId)

                    val programs = trimmed.map { programBuilder.buildTrendingProgram(channelId, it, useWidePoster) }
                    channelManager.insertPrograms(programs)
                    
                    channelSignatures[catalogKey] = signature
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Updates the **Watch Next** system row with the user's in-progress items.
     * Performs a full clear-and-rebuild to ensure no stale entries remain.
     */
    suspend fun updateWatchNext() {
        if (!shouldRun()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    // Clear ALL our Watch Next entries first to remove stale ones
                    programBuilder.clearAllWatchNextPrograms()

                    val items = deduplicateByContent(
                        watchProgressRepository.continueWatching.first()
                    ).take(RecommendationConstants.MAX_WATCH_NEXT_ITEMS)

                    for (progress in items) {
                        val program = programBuilder.buildWatchNextProgram(progress)
                        val internalId = "wn_${progress.contentId}"
                        programBuilder.upsertWatchNextProgram(program, internalId)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Convenience method called when a single progress entry is saved/updated.
     * Refreshes Watch Next row.
     */
    suspend fun onProgressUpdated(progress: WatchProgress) {
        if (!shouldRun()) return
        updateWatchNext()
    }

    /**
     * Full sync — updates all base channels. Called by [TvRecommendationWorker].
     */
    suspend fun syncAllChannels() {
        if (!shouldRun()) return
        initializeChannels()
        updateWatchNext()
        // Note: Dynamic catalogs are updated from HomeViewModel when the row is successfully fetched
    }

    /**
     * Removes all dynamic channels and Watch Next entries created by this app.
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            // Delete ALL preview channels
            channelManager.cleanupLegacyChannels(emptyList())
            
            // Delete ALL watch next items
            programBuilder.clearAllWatchNextPrograms()
            
            channelSignatures.clear()
        }
    }

    /**
     * Called when a watch progress entry is removed by the user.
     * Removes the Watch Next entry.
     */
    suspend fun onProgressRemoved(contentId: String) {
        if (!shouldRun()) return
        withContext(Dispatchers.IO) {
            try {
                programBuilder.removeWatchNextProgram("wn_$contentId")
            } catch (_: Exception) {
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────

    /**
     * Deduplicates progress entries per contentId, keeping only the most
     * recently watched entry for each content item. This prevents showing
     * multiple episodes of the same series in Continue Watching / Watch Next.
     */
    private fun deduplicateByContent(items: List<WatchProgress>): List<WatchProgress> {
        return items
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
    }

    private suspend fun shouldRun(): Boolean =
        isTvDevice() && dataStore.isEnabled()

    private fun isTvDevice(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
