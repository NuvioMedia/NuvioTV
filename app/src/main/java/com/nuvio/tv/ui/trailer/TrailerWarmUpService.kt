package com.nuvio.tv.ui.trailer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Lightweight Background Service running in the `:trailer` subprocess.
 *
 * Its sole purpose is to keep the `:trailer` OS process alive ("warm") and pre-load
 * the WebView engine on the main thread. This avoids the 1.5s - 2.0s cold-start penalty
 * (Zygote fork + WebView classloader loading + Chrome DevTools context initialization)
 * when the user clicks on "Fragman".
 */
class TrailerWarmUpService : Service() {

    companion object {
        private const val TAG = "TrailerWarmUpService"

        fun start(context: Context) {
            try {
                val intent = Intent(context, TrailerWarmUpService::class.java)
                context.startService(intent)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to start TrailerWarmUpService", e)
            }
        }
    }

    private var dummyWebView: WebView? = null

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i(TAG, "TrailerWarmUpService starting in process: ${android.os.Process.myPid()}")

        // Pre-load WebView on the main thread
        Handler(Looper.getMainLooper()).post {
            try {
                // Initialize WebView with applicationContext. This loads WebView native libraries
                // and sets up the Chromium environment early in the background.
                dummyWebView = WebView(applicationContext)
                android.util.Log.i(TAG, "WebView preloaded successfully in background process.")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to pre-load WebView in warm-up service", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Return START_STICKY to keep the process alive as much as possible
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        dummyWebView?.destroy()
        dummyWebView = null
        super.onDestroy()
    }
}
