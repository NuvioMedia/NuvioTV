package com.nuvio.tv

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.nuvio.tv.core.image.CustomPosterFallbackInterceptor
import coil3.disk.DiskCache
import com.nuvio.tv.core.storage.TvMediaCache
import com.nuvio.tv.core.storage.MediaCacheCategory
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism

import okio.Path.Companion.toOkioPath
import com.nuvio.tv.core.diagnostics.SentryInitializer
import com.nuvio.tv.core.image.StaleWhileRevalidateCacheStrategy
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.runtime.TvPerformanceProfile
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var mediaCache: TvMediaCache

    @Inject lateinit var androidTvChannelSyncService: AndroidTvChannelSyncService
    @Inject lateinit var sentrySettingsDataStore: SentrySettingsDataStore
    @Inject lateinit var imagePerformancePreferences: ImagePerformancePreferences
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder

    companion object {
        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SentryInitializer.start(this, sentrySettingsDataStore)
        PluginRuntimeHooks.onApplicationCreate(this)
        androidTvChannelSyncService.start()
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val performanceProfile = TvPerformanceProfile.detect(this)

        val imageOkHttpClient by lazy {
            val imageDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = performanceProfile.imageRequests
                maxRequestsPerHost = performanceProfile.imageRequestsPerHost
            }
            OkHttpClient.Builder()
                .dispatcher(imageDispatcher)
                .dns(IPv4FirstDns())
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    try {
                        chain.proceed(chain.request())
                    } catch (e: java.net.SocketTimeoutException) {
                        chain.withConnectTimeout(3, TimeUnit.SECONDS)
                            .withReadTimeout(4, TimeUnit.SECONDS)
                            .proceed(chain.request())
                    }
                }
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        val imageLoaderRef: () -> ImageLoader = { SingletonImageLoader.get(this) }

        return ImageLoader.Builder(this)
            .components {
                add(CustomPosterFallbackInterceptor())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient },
                        cacheStrategy = {
                            StaleWhileRevalidateCacheStrategy(
                                revalidationClient = { imageOkHttpClient },
                                imageLoaderProvider = imageLoaderRef,
                                writesEnabled = { mediaCache.writesEnabled },
                            )
                        },
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, performanceProfile.imageMemoryCachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(mediaCache.coilQuota(MediaCacheCategory.IMAGES))
                    .build()
            }
            .diskCachePolicy(mediaCache.requestPolicy)
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(false)
            .allowRgb565(imagePerformancePreferences.rgb565Enabled)
            .bitmapFactoryMaxParallelism(performanceProfile.parallelImageDecodes)
            .build()
    }
}
