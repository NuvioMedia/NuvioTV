package com.nuvio.tv.core.sync

import com.nuvio.tv.data.local.PluginSyncProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Runs one plugin pull at a time.
 * The same profile is pulled once more after the current pull. A different profile cancels it.
 */
internal class PluginSyncScheduler(
    private val scope: CoroutineScope,
    private val onReplace: () -> Unit = {},
    private val currentGeneration: () -> Int = { 0 },
    private val pull: suspend (profileId: Int, generation: Int) -> Unit
) {
    private val lock = Any()
    private var job: Job? = null
    private var profileId: Int? = null
    private var rerun = false

    fun enqueue(profileId: Int) {
        val previous = synchronized(lock) {
            val running = job
            if (running?.isActive == true && this.profileId == profileId) {
                rerun = true
                return
            }
            val previous = running?.takeIf { it.isActive }
            if (previous != null) {
                rerun = false
                onReplace()
            }
            this.profileId = profileId
            val generation = currentGeneration()
            job = scope.launch(PluginSyncProfile(profileId)) {
                run(profileId, generation)
            }
            previous
        }
        previous?.cancel()
    }

    fun cancel() {
        val running = synchronized(lock) {
            val running = job
            job = null
            profileId = null
            rerun = false
            running
        }
        running?.cancel()
    }

    private suspend fun run(targetProfileId: Int, generation: Int) {
        val thisJob = coroutineContext[Job]
        try {
            while (coroutineContext.isActive) {
                pull(targetProfileId, generation)
                val stillActive = coroutineContext.isActive
                val again = synchronized(lock) {
                    val shouldRerun = rerun && profileId == targetProfileId && stillActive
                    rerun = false
                    if (!shouldRerun && job === thisJob) {
                        job = null
                        profileId = null
                    }
                    shouldRerun
                }
                if (!again) return
            }
        } catch (e: CancellationException) {
            synchronized(lock) {
                if (job === thisJob) {
                    job = null
                    profileId = null
                    rerun = false
                }
            }
            throw e
        }
    }
}
