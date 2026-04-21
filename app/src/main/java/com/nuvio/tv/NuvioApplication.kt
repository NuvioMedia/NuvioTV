package com.nuvio.tv

import android.app.Application
import android.os.Build
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import android.util.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.ignoreAllSSLErrors
import com.nuvio.tv.core.sync.StartupSyncService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var startupSyncService: StartupSyncService

    companion object {
        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return store[url.host]?.filter { cookie ->
                    cookie.expiresAt > System.currentTimeMillis()
                } ?: emptyList()
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                cookies.forEach { newCookie ->
                    hostCookies.removeAll { it.name == newCookie.name }
                    hostCookies.add(newCookie)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Install Conscrypt as the primary TLS provider.
        // This gives OkHttp a browser-compatible TLS fingerprint (JA3/JA4),
        // which is critical for bypassing Cloudflare's bot detection.
        // Without it, Cloudflare blocks OkHttp even with valid cf_clearance cookies.
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (e: Exception) {
            Log.w("NuvioApplication", "Failed to install Conscrypt: ${e.message}")
        }

        // Initialize the CloudStream NiceHTTP singleton's OkHttpClient.
        // Matches CloudStream's RequestsHelper.initClient() setup.
        // Wrapped in try catch because java.lang.BootstrapMethodError 
        // doesn't exist on API < 26 (e.g. Fire TV 4K Gen 1 running Android 7.1.2)
        try {
            app.baseClient = OkHttpClient.Builder()
                .cookieJar(extensionCookieJar)
                .followRedirects(true)
                .followSslRedirects(true)
                .ignoreAllSSLErrors()
                .cache(Cache(
                    directory = File(cacheDir, "http_cache"),
                    maxSize = 50L * 1024L * 1024L
                ))
                .build()
        } catch (e: Throwable) {
            Log.w("NuvioApplication", "Failed to initialize NiceHttp client (API ${Build.VERSION.SDK_INT}): ${e.message}")
        }

        // Set AcraApplication context early so CS3 stubs can access it
        AcraApplication.context = this
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.33)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(4))
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(8))
            .bitmapFactoryMaxParallelism(4)
            .allowHardware(true)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
}
