package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
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

    private companion object {
        const val TAG = "TvRecommendation"
    }

    // ────────────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────────────

    /**
     * One-time initialization — clears orphan channels not in the user's enabled catalogs.
     * Called from [NuvioApplication.onCreate].
     */
    suspend fun initializeChannels() {
        if (!isTvDevice()) {
            Log.d(TAG, "initializeChannels skipped: not a TV device")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                // Determine which catalogs are valid
                val validIds = dataStore.getEnabledCatalogs().toMutableList()
                Log.d(TAG, "initializeChannels enabledCatalogs=$validIds")
                validIds.add("nuvio_play_next")
                channelManager.cleanupLegacyChannels(validIds)

                // Force sync Watch Next items right on startup to refresh launcher UI and bust caches
                updateWatchNext()
            } catch (e: Exception) {
                Log.w(TAG, "initializeChannels failed", e)
            }
        }
    }

    /**
     * Updates an arbitrary TV channel for a catalog. 
     * Called from [HomeViewModel] after catalog rows are loaded.
     */
    suspend fun updateCatalogChannel(catalogKey: String, catalogName: String, items: List<MetaPreview>) {
        if (!isTvDevice()) {
            Log.d(TAG, "updateCatalogChannel skipped: not TV device key=$catalogKey")
            return
        }
        if (!dataStore.isEnabled()) {
            Log.d(TAG, "updateCatalogChannel skipped: globally disabled key=$catalogKey")
            return
        }

        // Ensure this catalog is still chosen by the user
        val enabledCatalogs = dataStore.getEnabledCatalogs()
        if (!enabledCatalogs.contains(catalogKey)) {
            Log.d(TAG, "updateCatalogChannel skipped: key=$catalogKey not in enabled=$enabledCatalogs")
            return
        }

        val maxItems = dataStore.getMaxItemsPerChannel()
        val useWidePoster = dataStore.getUseWidePoster()

        val trimmed = items.take(maxItems) // Dynamic Max Limit
        val signature = trimmed.joinToString("|") { it.id } + "_wide_$useWidePoster"
        if (signature == channelSignatures[catalogKey]) {
            Log.d(TAG, "updateCatalogChannel unchanged key=$catalogKey items=${trimmed.size}")
            return
        }

        Log.d(TAG, "updateCatalogChannel name='$catalogName' key=$catalogKey items=${trimmed.size}")
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val channelId = channelManager.getOrCreateChannel(catalogKey, catalogName)
                    if (channelId == null) {
                        Log.w(TAG, "updateCatalogChannel channelId=null for key=$catalogKey")
                        return@withContext
                    }
                    channelManager.clearProgramsForChannel(channelId)

                    val programs = trimmed.map { programBuilder.buildTrendingProgram(channelId, it, useWidePoster) }
                    channelManager.insertPrograms(programs)
                    Log.d(TAG, "updateCatalogChannel inserted ${programs.size} programs into channelId=$channelId")

                    channelSignatures[catalogKey] = signature
                } catch (e: Exception) {
                    Log.w(TAG, "updateCatalogChannel failed key=$catalogKey", e)
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
                    val items = deduplicateByContent(
                        watchProgressRepository.continueWatching.first()
                    ).take(RecommendationConstants.MAX_WATCH_NEXT_ITEMS)

                    val nuvioPlayNextEnabled = dataStore.getPlayNextEnabled()
                    Log.d(TAG, "updateWatchNext nuvioPlayNextEnabled=$nuvioPlayNextEnabled items=${items.size}")

                    if (nuvioPlayNextEnabled) {
                        // Nuvio Play Next channel takes over: clear the system Watch Next row
                        // so the launcher only shows our dedicated row and there is no duplication.
                        programBuilder.clearAllWatchNextPrograms()

                        val playNextChannelId = channelManager.getOrCreateChannel(
                            internalId = "nuvio_play_next",
                            displayName = "Nuvio Play Next"
                        )

                        if (playNextChannelId != null) {
                            channelManager.clearProgramsForChannel(playNextChannelId)
                            val previewPrograms = kotlinx.coroutines.coroutineScope {
                                items.map { progress ->
                                    async { programBuilder.buildContinueWatchingProgram(playNextChannelId, progress) }
                                }.map { it.await() }
                            }
                            channelManager.insertPrograms(previewPrograms)
                        }
                    } else {
                        // Fall back to the system Watch Next row and remove our custom channel programs.
                        val playNextChannelId = channelManager.getChannelId("nuvio_play_next")
                        if (playNextChannelId != null) {
                            channelManager.clearProgramsForChannel(playNextChannelId)
                        }

                        programBuilder.clearAllWatchNextPrograms()
                        for (progress in items) {
                            val program = programBuilder.buildWatchNextProgram(progress)
                            val internalId = "wn_${progress.contentId}"
                            programBuilder.upsertWatchNextProgram(program, internalId)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "updateWatchNext failed", e)
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
            
            // Just clear the "Play Next" channel programs instead of deleting it
            // so it hides but doesn't require user re-approval if toggled back on
            val playNextChannelId = channelManager.getChannelId("nuvio_play_next")
            if (playNextChannelId != null) {
                channelManager.clearProgramsForChannel(playNextChannelId)
            }
            
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
