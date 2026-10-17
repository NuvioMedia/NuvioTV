package com.nuvio.tv.core.sync.androidtv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val TAG = "TvChannelSync"
private const val JOB_ID_PERIODIC = 10_010
private const val JOB_ID_IMMEDIATE = 10_011
private const val PERIODIC_INTERVAL_MS = 15 * 60_000L  // 15 minutes (JobScheduler OS minimum)

/**
 * Background job that keeps the Continue Watching preview channel in sync when the app
 * is not in the foreground. Reads from the CW enrichment cache on disk, which is
 * populated by the HomeViewModel CW pipeline during normal app usage.
 *
 * This ensures the launcher channel stays up-to-date even when the app is backgrounded,
 * using the same enriched data and settings-aware filtering as the in-app UI.
 *
 * Dependencies are obtained via Hilt EntryPoint since JobService is not a supported
 * Hilt injection target.
 */
class TvChannelRefreshJobService : JobService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TvChannelJobEntryPoint {
        fun channelSyncService(): AndroidTvChannelSyncService
        fun channelManager(): AndroidTvChannelManager
    }

    private var jobScope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, TvChannelJobEntryPoint::class.java
        )
        val syncService = entryPoint.channelSyncService()
        val manager = entryPoint.channelManager()

        if (!manager.isSupported()) {
            jobFinished(params, false)
            return false
        }

        jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobScope!!.launch {
            try {
                // Read from CW enrichment cache and reconcile with current settings.
                // The cache is on disk so it's available even when the app hasn't been
                // in the foreground recently.
                syncService.reconcileFromCache()
                Log.d(TAG, "Background job: reconciled from CW cache")
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
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
