package com.nuvio.tv.core.usenet

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsenetConfiguration(
    val profile: String = "balanced",
    val readAhead: Int = 0,
    val maxConnections: Int = 0,
    val prewarmOnLaunch: Boolean = false,
    val fastMkvStartup: Boolean = true,
    val fastNzbFetch: Boolean = true,
    val prefetchResults: Boolean = false,
    val cacheNzb: Boolean = true,
    val allowPrivateNetwork: Boolean = false,
    val holeFilling: Boolean = false,
    val maxMissingArticles: Int = 5,
    val maxConsecutiveMissing: Int = 2,
    val fallbackEnabled: Boolean = false,
    val fallbackMaxAttempts: Int = 5
)

/** Device-local tuning; never contains provider credentials or NZB URLs. */
@Singleton
class UsenetSettings @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("usenet_performance", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read(context))
    val settings = state.asStateFlow()

    fun update(value: UsenetConfiguration) {
        require(value.profile in listOf("low-memory", "balanced", "throughput"))
        require(value.readAhead in 0..512 && value.maxConnections in 0..4096)
        require(value.fallbackMaxAttempts in 1..50)
        require(value.maxMissingArticles in 1..50)
        require(value.maxConsecutiveMissing in 1..10 && value.maxConsecutiveMissing <= value.maxMissingArticles)
        preferences.edit().putString("profile", value.profile)
            .putInt("readAhead", value.readAhead).putInt("maxConnections", value.maxConnections)
            .putBoolean("prewarmOnLaunch", value.prewarmOnLaunch)
            .putBoolean("fastMkvStartup", value.fastMkvStartup)
            .putBoolean("fastNzbFetch", value.fastNzbFetch)
            .putBoolean("prefetchResults", value.prefetchResults)
            .putBoolean("cacheNzb", value.cacheNzb)
            .putBoolean("allowPrivateNetwork", value.allowPrivateNetwork)
            .putBoolean("holeFilling", value.holeFilling)
            .putInt("maxMissingArticles", value.maxMissingArticles)
            .putInt("maxConsecutiveMissing", value.maxConsecutiveMissing)
            .putBoolean("fallbackEnabled", value.fallbackEnabled)
            .putInt("fallbackMaxAttempts", value.fallbackMaxAttempts).apply()
        state.value = value
        UsenetSidecar.get(context).settingsChanged()
    }

    companion object {
        fun read(context: Context): UsenetConfiguration {
            val prefs = context.getSharedPreferences("usenet_performance", Context.MODE_PRIVATE)
            val maxMissingArticles = prefs.getInt("maxMissingArticles", 5).coerceIn(1, 50)
            return UsenetConfiguration(
                profile = prefs.getString("profile", "balanced")?.takeIf { it in listOf("low-memory", "balanced", "throughput") } ?: "balanced",
                readAhead = prefs.getInt("readAhead", 0).coerceIn(0, 512),
                maxConnections = prefs.getInt("maxConnections", 0).coerceIn(0, 4096),
                prewarmOnLaunch = prefs.getBoolean("prewarmOnLaunch", false),
                fastMkvStartup = prefs.getBoolean("fastMkvStartup", true),
                fastNzbFetch = prefs.getBoolean("fastNzbFetch", true),
                prefetchResults = prefs.getBoolean("prefetchResults", false),
                cacheNzb = prefs.getBoolean("cacheNzb", true),
                allowPrivateNetwork = prefs.getBoolean("allowPrivateNetwork", false),
                holeFilling = prefs.getBoolean("holeFilling", false),
                maxMissingArticles = maxMissingArticles,
                maxConsecutiveMissing = prefs.getInt("maxConsecutiveMissing", 2).coerceIn(1, minOf(10, maxMissingArticles)),
                fallbackEnabled = prefs.getBoolean("fallbackEnabled", false),
                fallbackMaxAttempts = prefs.getInt("fallbackMaxAttempts", 5).coerceIn(1, 50)
            )
        }
    }
}
