package com.nuvio.tv.core.livetv

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

/** Schedules a network- and storage-aware EPG refresh without starting work on the UI thread. */
@Singleton
class LiveTvRefreshScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<LiveTvGuideRefreshWorker>(
            REPEAT_INTERVAL_HOURS, TimeUnit.HOURS, FLEX_INTERVAL_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "telumia_live_tv_guide_refresh_v1"
        const val WORK_TAG = "live_tv_guide_refresh"
        const val REPEAT_INTERVAL_HOURS = 12L
        const val FLEX_INTERVAL_HOURS = 2L
        const val RETRY_BACKOFF_MINUTES = 30L
    }
}
