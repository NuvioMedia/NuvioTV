package com.nuvio.tv.core.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nuvio.tv.R

/**
 * Foreground service that prevents the system from killing Nuvio while an
 * external video player is active.
 *
 *
 * IMPORTANT: startForeground() must be called immediately in onStartCommand()
 * to avoid ForegroundServiceDidNotStartInTime crashes on Android 8+.
 */
class ExternalPlaybackKeepAliveService : Service() {

    companion object {
        private const val TAG = "ExtPlaybackKeepAlive"
        private const val CHANNEL_ID = "external_playback_channel"
        private const val NOTIFICATION_ID = 9529 // Zidoo port number as a nod :)
        private const val MAX_ALIVE_MS = 8L * 60 * 60 * 1000 // 8 hours safety limit

        fun start(context: Context) {
            val intent = Intent(context, ExternalPlaybackKeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Service start requested")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start keep-alive service", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, ExternalPlaybackKeepAliveService::class.java)
                context.stopService(intent)
                Log.d(TAG, "Service stop requested")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop keep-alive service", e)
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        Log.d(TAG, "Safety timeout reached, stopping service")
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        // Safety timeout - auto-stop after 8 hours in case stop() is never called
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, MAX_ALIVE_MS)
        Log.d(TAG, "Foreground service started")

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed, keeping service alive")
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.external_playback_channel_description)
                setShowBadge(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.external_playback_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
