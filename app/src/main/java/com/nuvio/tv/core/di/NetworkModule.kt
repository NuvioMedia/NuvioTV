package com.nuvio.tv.core.di

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.api.AniSkipApi
import com.nuvio.tv.data.remote.api.ArmApi
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.ImdbTapframeApi
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.api.ParentalGuideApi
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import com.nuvio.tv.data.remote.api.TmdbApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Named
import javax.inject.Singleton

private object TraktHttpTrace {
    private val requestCounter = AtomicLong(0L)
    fun nextRequestId(): Long = requestCounter.incrementAndGet()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024 * 1024)) // 50 MB disk cache
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    @Provides
    @Singleton
    @Named("trakt")
    fun provideTraktOkHttpClient(
        okHttpClient: OkHttpClient
    ): OkHttpClient = okHttpClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val version = BuildConfig.VERSION_NAME.ifBlank { "dev" }
            val newRequest = request.newBuilder()
                .header("Content-Type", "application/json")
                .header("User-Agent", "Nuvio/$version")
                .header("trakt-api-key", BuildConfig.TRAKT_CLIENT_ID)
                .header("trakt-api-version", "2")
                .build()

            if (!BuildConfig.DEBUG) {
                return@addInterceptor chain.proceed(newRequest)
            }

            val requestId = TraktHttpTrace.nextRequestId()
            val target = buildString {
                append(newRequest.url.encodedPath)
                newRequest.url.encodedQuery?.let { query ->
                    append('?')
                    append(query)
                }
            }
            val startNs = System.nanoTime()
            Log.d("TraktHttp", "REQ #$requestId ${newRequest.method} $target")

            try {
                val response = chain.proceed(newRequest)
                val durationMs = (System.nanoTime() - startNs) / 1_000_000L
                val retryAfter = response.header("Retry-After")
                val rateLimit = response.header("X-Ratelimit")
                val page = response.header("X-Pagination-Page")
                val pageCount = response.header("X-Pagination-Page-Count")
                val pageInfo = if (page != null || pageCount != null) {
                    " page=${page ?: "-"} pageCount=${pageCount ?: "-"}"
                } else {
                    ""
                }
                val retryInfo = retryAfter?.let { " retryAfter=${it}s" } ?: ""
                val rateInfo = rateLimit?.let { " rate=$it" } ?: ""
                Log.d(
                    "TraktHttp",
                    "RES #$requestId ${response.code} ${newRequest.method} $target ${durationMs}ms$retryInfo$pageInfo$rateInfo"
                )
                response
            } catch (error: Exception) {
                val durationMs = (System.nanoTime() - startNs) / 1_000_000L
                Log.w(
                    "TraktHttp",
                    "ERR #$requestId ${newRequest.method} $target ${durationMs}ms ${error.javaClass.simpleName}: ${error.message}"
                )
                throw error
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://placeholder.nuvio.tv/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("tmdb")
    fun provideTmdbRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("trakt")
    fun provideTraktRetrofit(
        @Named("trakt") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.TRAKT_API_URL.ifBlank { "https://api.trakt.tv/" })
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAddonApi(retrofit: Retrofit): AddonApi =
        retrofit.create(AddonApi::class.java)

    @Provides
    @Singleton
    fun provideTmdbApi(@Named("tmdb") retrofit: Retrofit): TmdbApi =
        retrofit.create(TmdbApi::class.java)

    @Provides
    @Singleton
    fun provideTraktApi(@Named("trakt") retrofit: Retrofit): TraktApi =
        retrofit.create(TraktApi::class.java)

    @Provides
    @Singleton
    @Named("parentalGuide")
    fun provideParentalGuideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.PARENTAL_GUIDE_API_URL.ifEmpty { "https://localhost/" })
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideParentalGuideApi(@Named("parentalGuide") retrofit: Retrofit): ParentalGuideApi =
        retrofit.create(ParentalGuideApi::class.java)

    // --- Skip Intro APIs ---

    @Provides
    @Singleton
    @Named("introDb")
    fun provideIntroDbRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.INTRODB_API_URL.ifEmpty { "https://localhost/" })
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideIntroDbApi(@Named("introDb") retrofit: Retrofit): IntroDbApi =
        retrofit.create(IntroDbApi::class.java)

    @Provides
    @Singleton
    @Named("aniSkip")
    fun provideAniSkipRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.aniskip.com/v2/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAniSkipApi(@Named("aniSkip") retrofit: Retrofit): AniSkipApi =
        retrofit.create(AniSkipApi::class.java)

    @Provides
    @Singleton
    @Named("arm")
    fun provideArmRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://arm.haglund.dev/api/v2/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideArmApi(@Named("arm") retrofit: Retrofit): ArmApi =
        retrofit.create(ArmApi::class.java)

    // --- GitHub Releases API (in-app updates) ---

    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideGitHubReleaseApi(@Named("github") retrofit: Retrofit): GitHubReleaseApi =
        retrofit.create(GitHubReleaseApi::class.java)

    // --- Trailer API ---

    @Provides
    @Singleton
    @Named("trailer")
    fun provideTrailerRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.TRAILER_API_URL.ifEmpty { "https://localhost/" })
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideTrailerApi(@Named("trailer") retrofit: Retrofit): TrailerApi =
        retrofit.create(TrailerApi::class.java)

    // --- MDBList API ---

    @Provides
    @Singleton
    @Named("mdblist")
    fun provideMDBListRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.mdblist.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideMDBListApi(@Named("mdblist") retrofit: Retrofit): MDBListApi =
        retrofit.create(MDBListApi::class.java)

    // --- SeriesGraph API ---

    @Provides
    @Singleton
    @Named("seriesGraph")
    fun provideSeriesGraphRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        val rawBaseUrl = BuildConfig.IMDB_RATINGS_API_BASE_URL
        val normalizedBaseUrl = if (rawBaseUrl.isNotBlank()) {
            if (rawBaseUrl.endsWith('/')) rawBaseUrl else "$rawBaseUrl/"
        } else {
            "http://localhost/"
        }
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideSeriesGraphApi(@Named("seriesGraph") retrofit: Retrofit): SeriesGraphApi =
        retrofit.create(SeriesGraphApi::class.java)

    @Provides
    @Singleton
    @Named("imdbTapframe")
    fun provideImdbTapframeRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        val rawBaseUrl = BuildConfig.IMDB_TAPFRAME_API_BASE_URL
        val normalizedBaseUrl = if (rawBaseUrl.isNotBlank()) {
            if (rawBaseUrl.endsWith('/')) rawBaseUrl else "$rawBaseUrl/"
        } else {
            "http://localhost/"
        }
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideImdbTapframeApi(@Named("imdbTapframe") retrofit: Retrofit): ImdbTapframeApi =
        retrofit.create(ImdbTapframeApi::class.java)
}
