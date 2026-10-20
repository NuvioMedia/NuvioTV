package com.nuvio.tv.core.livetv

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveTvGuideRefreshDependencies {
    fun refreshJob(): LiveTvGuideRefreshJob
}

class LiveTvGuideRefreshWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val dependencies = EntryPointAccessors.fromApplication(
                applicationContext,
                LiveTvGuideRefreshDependencies::class.java,
            )
            val result = dependencies.refreshJob().run()
            if (result.sourceCount > 0 && result.refreshedCount == 0 && result.failedCount > 0) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
