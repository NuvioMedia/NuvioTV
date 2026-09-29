package com.nuvio.tv.core.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Process
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

@Singleton
class AppRestarter @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun restart(): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return false
        launchIntent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            RESTART_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
            pendingIntent
        )
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    private companion object {
        const val RESTART_REQUEST_CODE = 8842
        const val RESTART_DELAY_MS = 400L
    }
}
