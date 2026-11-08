package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterArt
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@Singleton
class TvRecommendationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val programBuilder: ProgramBuilder,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) {

    private val mutex = Mutex()
    private val syncedFingerprints = ConcurrentHashMap<String, ProgramFingerprint>()
    private val deferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Latest progress seen while frames are on screen. Leanback IPC is deferred until playback
    // stops so the provider write does not hitch the player, and so the position is not dropped.
    private var pendingWatchNext: WatchProgress? = null

    init {
        deferScope.launch {
            isPlaybackActive.collect { active ->
                if (!active) flushPendingWatchNext()
            }
        }
    }

    private data class ProgramFingerprint(
        val title: String?,
        val position: Long,
        val duration: Long,
        val poster: String?,
        val backdrop: String?,
        val season: Int?,
        val episode: Int?
    )

    companion object {
        const val TAG = "TvRecommendation"
        val isPlaybackActive = MutableStateFlow(false)
    }

    suspend fun updateWatchNextFromCwItems(items: List<ContinueWatchingItem>) {
        val progressList = items.mapNotNull { item ->
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress
                is ContinueWatchingItem.NextUp -> WatchProgress(
                    contentId = item.info.contentId,
                    contentType = item.info.contentType,
                    name = item.info.name,
                    poster = item.info.poster,
                    backdrop = item.info.backdrop,
                    logo = item.info.logo,
                    videoId = item.info.videoId,
                    season = item.info.season,
                    episode = item.info.episode,
                    episodeTitle = item.info.episodeTitle,
                    position = 0L,
                    duration = 0L,
                    lastWatched = item.info.lastWatched
                )
            }
        }
        updateWatchNext(progressList)
    }

    suspend fun updateWatchNext(items: List<WatchProgress>) {
        if (!isTvDevice()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val posterPattern = recommendationPosterPattern()
                    val decorated = items.withCustomPosterArt(posterPattern)
                    val currentInternalIds = decorated.map { programBuilder.watchNextId(it) }.toSet()
                    val existingInternalIds = programBuilder.getAllWatchNextInternalIds()

                    // Remove only stale programs that are no longer in CW
                    (existingInternalIds - currentInternalIds).forEach { staleId ->
                        programBuilder.removeWatchNextProgram(staleId)
                        syncedFingerprints.remove(staleId)
                    }

                    // Upsert current CW programs only if they actually changed
                    decorated.forEach { p ->
                        val id = programBuilder.watchNextId(p)
                        val newFingerprint = ProgramFingerprint(
                            title = p.name,
                            position = p.position,
                            duration = p.duration,
                            poster = p.poster,
                            backdrop = p.backdrop,
                            season = p.season,
                            episode = p.episode
                        )
                        val oldFingerprint = syncedFingerprints[id]
                        if (oldFingerprint != null &&
                            oldFingerprint.title == newFingerprint.title &&
                            oldFingerprint.poster == newFingerprint.poster &&
                            oldFingerprint.backdrop == newFingerprint.backdrop &&
                            oldFingerprint.season == newFingerprint.season &&
                            oldFingerprint.episode == newFingerprint.episode &&
                            oldFingerprint.duration == newFingerprint.duration &&
                            abs(oldFingerprint.position - newFingerprint.position) < 2_000L
                        ) {
                            // No meaningful change: skip database update to prevent waking up launcher
                            return@forEach
                        }

                        val program = programBuilder.buildWatchNextProgram(p)
                        programBuilder.upsertWatchNextProgram(program, id)
                        syncedFingerprints[id] = newFingerprint
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "updateWatchNext failed", e)
                }
            }
        }
    }

    suspend fun updateSingleWatchNextProgram(progress: WatchProgress) {
        if (!isTvDevice()) return
        mutex.withLock {
            pendingWatchNext = progress
            if (isPlaybackActive.value) return@withLock
            pendingWatchNext = null
            upsertSingleWatchNextLocked(progress)
        }
    }

    private suspend fun flushPendingWatchNext() {
        if (!isTvDevice()) return
        mutex.withLock {
            if (isPlaybackActive.value) return@withLock
            val progress = pendingWatchNext ?: return@withLock
            pendingWatchNext = null
            upsertSingleWatchNextLocked(progress)
        }
    }

    private suspend fun upsertSingleWatchNextLocked(progress: WatchProgress) {
        withContext(Dispatchers.IO) {
            if (isPlaybackActive.value) {
                pendingWatchNext = progress
                return@withContext
            }
            try {
                val decorated = progress.withCustomPosterArt(recommendationPosterPattern())
                val id = programBuilder.watchNextId(decorated)
                val newFingerprint = ProgramFingerprint(
                    title = decorated.name,
                    position = decorated.position,
                    duration = decorated.duration,
                    poster = decorated.poster,
                    backdrop = decorated.backdrop,
                    season = decorated.season,
                    episode = decorated.episode
                )
                val oldFingerprint = syncedFingerprints[id]
                if (oldFingerprint != null &&
                    oldFingerprint.title == newFingerprint.title &&
                    oldFingerprint.poster == newFingerprint.poster &&
                    oldFingerprint.backdrop == newFingerprint.backdrop &&
                    oldFingerprint.season == newFingerprint.season &&
                    oldFingerprint.episode == newFingerprint.episode &&
                    oldFingerprint.duration == newFingerprint.duration &&
                    abs(oldFingerprint.position - newFingerprint.position) < 2_000L
                ) {
                    return@withContext
                }

                val program = programBuilder.buildWatchNextProgram(decorated)
                programBuilder.upsertWatchNextProgram(program, id)
                syncedFingerprints[id] = newFingerprint
            } catch (e: Exception) {
                Log.w(TAG, "updateSingleWatchNextProgram failed", e)
            }
        }
    }

    private suspend fun recommendationPosterPattern(): String {
        val pattern = layoutPreferenceDataStore.customPosterUrlPattern.first()
        val screens = layoutPreferenceDataStore.customPosterEnabledScreens.first()
        return patternForScreen(pattern, CustomPosterScreen.TV_RECOMMENDATIONS, screens)
    }

    suspend fun onProgressRemoved(contentId: String) {
        if (!isTvDevice()) return
        mutex.withLock {
            pendingWatchNext?.let { pending ->
                if (watchNextIdMatchesContentId(programBuilder.watchNextId(pending), contentId)) {
                    pendingWatchNext = null
                }
            }
            withContext(Dispatchers.IO) {
                try {
                    programBuilder.removeWatchNextByContentId(contentId)
                    syncedFingerprints.keys.removeAll { watchNextIdMatchesContentId(it, contentId) }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun isTvDevice(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
