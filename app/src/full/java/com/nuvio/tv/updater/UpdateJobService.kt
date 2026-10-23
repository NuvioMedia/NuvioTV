package com.nuvio.tv.updater

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.nuvio.tv.BuildConfig
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "UpdateJobs"
private const val JOB_ID_CHECK = 10_020
private const val JOB_ID_INSTALL = 10_021
private const val JOB_ID_CHECK_NOW = 10_022
private const val CHECK_INTERVAL_MS = 12 * 60 * 60_000L

/**
 * Background update work. The periodic job checks for a new release and downloads it, so the banner can offer an
 * install without waiting for the download. On Android 12+, with automatic installs enabled, a second job installs the
 * downloaded update while the device is idle, without a confirmation screen.
 *
 * Dependencies are obtained via Hilt EntryPoint since JobService is not a supported Hilt injection target.
 */
class UpdateJobService : JobService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdateJobEntryPoint {
        fun updateRepository(): UpdateRepository
        fun updatePreferences(): UpdatePreferences
        fun updateDownloadStore(): UpdateDownloadStore
    }

    private var jobScope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, UpdateJobEntryPoint::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobScope = scope
        scope.launch {
            // A failed check, download or install asks for a retry with backoff instead of waiting for the next run.
            var retry = false
            try {
                when (params.jobId) {
                    JOB_ID_CHECK, JOB_ID_CHECK_NOW -> checkAndDownload(entryPoint)
                    JOB_ID_INSTALL -> installDownloaded(entryPoint)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoEligibleUpdateException) {
                Log.d(TAG, e.message.orEmpty())
            } catch (e: Exception) {
                Log.w(TAG, "Update job ${params.jobId} failed", e)
                retry = true
            } finally {
                jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobScope?.cancel()
        return true // reschedule on stop
    }

    private suspend fun checkAndDownload(entryPoint: UpdateJobEntryPoint) {
        val preferences = entryPoint.updatePreferences()
        val autoInstallEnabled = preferences.autoInstallEnabled.first()
        if (!preferences.updateBannerEnabled.first() && !autoInstallEnabled) return

        val downloadStore = entryPoint.updateDownloadStore()
        downloadStore.clearIfInstalled()
        val channel = preferences.getOrInitializeUpdateChannel()
        val update = entryPoint.updateRepository().getLatestUpdate(channel).getOrThrow()
        preferences.setLastCheckAtMs(System.currentTimeMillis())
        if (!VersionUtils.isRemoteNewer(update.tag, BuildConfig.VERSION_NAME)) return
        if (update.tag == preferences.ignoredTag.first()) return

        downloadStore.download(update).getOrThrow()
        Log.d(TAG, "Downloaded ${update.tag} in the background")
        if (autoInstallEnabled) scheduleIdleInstall(applicationContext)
    }

    private suspend fun installDownloaded(entryPoint: UpdateJobEntryPoint) {
        val preferences = entryPoint.updatePreferences()
        if (!canInstallSilently(applicationContext) || !preferences.autoInstallEnabled.first()) return
        val (tag, apkFile) = entryPoint.updateDownloadStore().pendingInstall() ?: return
        if (tag == preferences.ignoredTag.first()) return
        Log.d(TAG, "Installing $tag while the device is idle")
        preferences.setAutoInstalledTag(tag)
        ApkInstaller.install(applicationContext, apkFile, background = true)
    }

    companion object {
        /** Silent self-updates need Android 12+ and the "install unknown apps" permission. */
        fun canInstallSilently(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ApkInstaller.canRequestPackageInstalls(context)

        fun schedulePeriodicCheck(context: Context) {
            val scheduler = context.jobScheduler()
            if (scheduler.allPendingJobs.any { it.id == JOB_ID_CHECK }) return
            val job = JobInfo.Builder(JOB_ID_CHECK, ComponentName(context, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(CHECK_INTERVAL_MS)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        /** Downloads a newer release found by the in-app check without waiting for the next periodic run. */
        fun scheduleCheckNow(context: Context) {
            val job = JobInfo.Builder(JOB_ID_CHECK_NOW, ComponentName(context, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            context.jobScheduler().schedule(job)
        }

        fun cancelPeriodicCheck(context: Context) {
            context.jobScheduler().cancel(JOB_ID_CHECK)
        }

        fun scheduleIdleInstall(context: Context) {
            if (!canInstallSilently(context)) return
            val job = JobInfo.Builder(JOB_ID_INSTALL, ComponentName(context, UpdateJobService::class.java))
                .setRequiresDeviceIdle(true)
                .setPersisted(true)
                .build()
            context.jobScheduler().schedule(job)
        }

        fun cancelIdleInstall(context: Context) {
            context.jobScheduler().cancel(JOB_ID_INSTALL)
        }

        private fun Context.jobScheduler(): JobScheduler =
            getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    }
}
