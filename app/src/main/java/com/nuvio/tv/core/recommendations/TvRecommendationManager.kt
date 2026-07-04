package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvRecommendationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val programBuilder: ProgramBuilder
) {

    private val mutex = Mutex()

    companion object {
        const val TAG = "TvRecommendation"
        val isPlaybackActive = MutableStateFlow(false)
    }

    suspend fun updateWatchNextFromCwItems(items: List<ContinueWatchingItem>) {
        if (!isTvDevice()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val inProgress = items
                        .filterIsInstance<ContinueWatchingItem.InProgress>()

                    programBuilder.clearAllWatchNextPrograms()
                    inProgress.forEach { item ->
                        val program = programBuilder.buildWatchNextProgram(item.progress)
                        programBuilder.upsertWatchNextProgram(
                            program,
                            programBuilder.watchNextId(item.progress)
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "updateWatchNextFromCwItems failed", e)
                }
            }
        }
    }

    suspend fun onProgressRemoved(contentId: String) {
        if (!isTvDevice()) return
        withContext(Dispatchers.IO) {
            try {
                programBuilder.removeWatchNextByContentId(contentId)
            } catch (_: Exception) {
            }
        }
    }

    private fun isTvDevice(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
