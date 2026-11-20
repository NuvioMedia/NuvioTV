package com.nuvio.tv.core.util

import android.app.job.JobInfo
import android.app.job.JobScheduler

fun JobScheduler.hasPendingJob(jobId: Int): Boolean =
    runCatching { getPendingJob(jobId) != null }.getOrDefault(false)

fun JobScheduler.scheduleSafely(job: JobInfo): Boolean =
    runCatching { schedule(job) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
