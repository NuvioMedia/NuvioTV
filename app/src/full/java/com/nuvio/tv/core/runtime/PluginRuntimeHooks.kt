package com.nuvio.tv.core.runtime

import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.ignoreAllSSLErrors
import com.nuvio.tv.NuvioApplication
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

object PluginRuntimeHooks {
    @Volatile private var application: Application? = null

    fun onApplicationCreate(application: Application) {
        // Defer heavy Conscrypt + baseClient init until a cloudstream extension is
        // actually invoked (player launch / source/plugin screens). On cold start
        // for users who never open those screens, this saves ~50-200ms of native
        // crypto provider setup on the main thread.
        this.application = application
        AcraApplication.context = application
    }

    @Volatile
    private var isCloudstreamInitialized = false

    /**
     * Lazily initialize Conscrypt + the cloudstream baseClient. Safe to call
     * repeatedly; only the first call performs work. Must be invoked before any
     * cloudstream extension code runs (loadExtension / downloadExtension /
     * extension test runners / CloudflareKiller).
     */
    fun ensureCloudstreamInitialized() {
        if (isCloudstreamInitialized) return
        
        synchronized(this) {
            if (isCloudstreamInitialized) return
            val currentApp = application ?: return

            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            } catch (e: Exception) {
                Log.w("NuvioApplication", "Failed to install Conscrypt: ${e.message}")
            }

            try {
                app.baseClient = OkHttpClient.Builder()
                    .cookieJar(NuvioApplication.extensionCookieJar)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .ignoreAllSSLErrors()
                    .cache(Cache(
                        directory = File(currentApp.cacheDir, "http_cache"),
                        maxSize = 50L * 1024L * 1024L
                    ))
                    .build()
            } catch (e: Throwable) {
                Log.w("NuvioApplication", "Failed to initialize NiceHttp client (API ${Build.VERSION.SDK_INT}): ${e.message}")
            }
            
            isCloudstreamInitialized = true
        }
    }

    fun onActivityCreate(activity: Activity) {
        AcraApplication.setActivity(activity)
    }

    fun onActivityDestroy() {
        AcraApplication.setActivity(null)
    }
}
