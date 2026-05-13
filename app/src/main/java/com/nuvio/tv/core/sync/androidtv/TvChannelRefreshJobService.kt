package com.nuvio.tv.core.sync.androidtv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "TvChannelSync"
private const val JOB_ID_PERIODIC = 10_010
private const val JOB_ID_IMMEDIATE = 10_011
private const val PERIODIC_INTERVAL_MS = 15 * 60_000L  // 15 minutes (JobScheduler OS minimum)
private const val MAX_CHANNEL_ROWS = 20

/**
 * Background job that keeps the Continue Watching preview channel in sync when the app
 * is not in the foreground. Scheduled as a periodic job so progress from other devices
 * (e.g. phone) appears without requiring the TV app to be opened.
 *
 * Dependencies are obtained via Hilt EntryPoint since JobService is not a supported
 * Hilt injection target.
 */
class TvChannelRefreshJobService : JobService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TvChannelJobEntryPoint {
        fun watchProgressRepository(): WatchProgressRepository
        fun channelManager(): AndroidTvChannelManager
    }

    private var jobScope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, TvChannelJobEntryPoint::class.java
        )
        val repo = entryPoint.watchProgressRepository()
        val manager = entryPoint.channelManager()

        jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobScope!!.launch {
            try {
                // Wait for continue watching to emit a non-empty list. Trakt has an 8s grace
                // period before its flow produces data; give it 25s total.
                val items = withTimeoutOrNull(25_000L) {
                    repo.continueWatching
                        .dropWhile { it.isEmpty() }
                        .first()
                } ?: emptyList()

                if (items.isNotEmpty()) {
                    Log.d(TAG, "Background job reconciling ${items.size} items")
                    manager.reconcile(items.take(MAX_CHANNEL_ROWS))
                } else {
                    Log.d(TAG, "Background job: no items to reconcile (timeout or empty)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Background job reconcile failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobScope?.cancel()
        return true // reschedule on stop
    }

    companion object {
        fun schedulePeriodic(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.allPendingJobs.any { it.id == JOB_ID_PERIODIC }) return
            val job = JobInfo.Builder(
                JOB_ID_PERIODIC,
                ComponentName(context, TvChannelRefreshJobService::class.java)
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIODIC_INTERVAL_MS)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
            Log.d(TAG, "Scheduled periodic TV channel refresh every ${PERIODIC_INTERVAL_MS / 60_000}min")
        }

        fun scheduleImmediate(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(
                JOB_ID_IMMEDIATE,
                ComponentName(context, TvChannelRefreshJobService::class.java)
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(5_000)
                .build()
            scheduler.schedule(job)
            Log.d(TAG, "Scheduled immediate TV channel refresh")
        }
    }
}
