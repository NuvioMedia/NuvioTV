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
    fun onApplicationCreate(application: Application) {
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
                    directory = File(application.cacheDir, "http_cache"),
                    maxSize = 50L * 1024L * 1024L
                ))
                .build()
        } catch (e: Throwable) {
            Log.w("NuvioApplication", "Failed to initialize NiceHttp client (API ${Build.VERSION.SDK_INT}): ${e.message}")
        }

        AcraApplication.context = application
    }

    fun onActivityCreate(activity: Activity) {
        AcraApplication.setActivity(activity)
    }

    fun onActivityDestroy() {
        AcraApplication.setActivity(null)
    }
}
