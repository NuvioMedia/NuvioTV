package com.nuvio.tv.core.usenet

import android.content.Context
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How Nuvio names itself to indexers and NZB servers, like the app's other HTTP requests.
 * Built from the installed version, so it follows every update.
 */
internal val NUVIO_USER_AGENT = "Nuvio/${BuildConfig.VERSION_NAME.ifBlank { "dev" }}"

internal const val USER_AGENT = "User-Agent"

/** An HTTP header value: printable ASCII on one line. */
internal fun isValidUserAgent(value: String) = value.length <= 256 && value.all { it in ' '..'~' }

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
    val fallbackMaxAttempts: Int = 5,
    /** Blank means [NUVIO_USER_AGENT]; only a User-Agent the user chose is stored. */
    val searchUserAgent: String = "",
    /** Blank means [NUVIO_USER_AGENT]; independent of the search User-Agent. */
    val downloadUserAgent: String = ""
) {
    /** Sent by built-in indexer searches, capability checks and connection tests. */
    val searchAgent: String get() = searchUserAgent.trim().ifEmpty { NUVIO_USER_AGENT }
    /** Sent by NZB downloads, unless an addon supplies its own User-Agent. */
    val downloadAgent: String get() = downloadUserAgent.trim().ifEmpty { NUVIO_USER_AGENT }
}

/** Device-local tuning; never contains provider credentials or NZB URLs. */
@Singleton
class UsenetSettings @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("usenet_performance", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read(context))
    val settings = state.asStateFlow()

    fun update(configuration: UsenetConfiguration) {
        // Saving Nuvio's own User-Agent keeps the automatic one, which follows app updates.
        fun customAgent(agent: String) = agent.trim().takeUnless { it == NUVIO_USER_AGENT }.orEmpty()
        val value = configuration.copy(
            searchUserAgent = customAgent(configuration.searchUserAgent),
            downloadUserAgent = customAgent(configuration.downloadUserAgent)
        )
        require(isValidUserAgent(value.searchUserAgent))
        require(isValidUserAgent(value.downloadUserAgent))
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
            .putInt("fallbackMaxAttempts", value.fallbackMaxAttempts)
            .putString("searchUserAgent", value.searchUserAgent)
            .putString("downloadUserAgent", value.downloadUserAgent)
            .remove("userAgent").apply()
        state.value = value
        UsenetSidecar.get(context).settingsChanged()
    }

    companion object {
        fun read(context: Context): UsenetConfiguration {
            val prefs = context.getSharedPreferences("usenet_performance", Context.MODE_PRIVATE)
            val maxMissingArticles = prefs.getInt("maxMissingArticles", 5).coerceIn(1, 50)
            // Keep the old shared override for both until the separate fields have been saved.
            // An explicitly saved blank must stay automatic, without reviving the old override.
            val legacyAgent = prefs.getString("userAgent", null)?.takeIf(::isValidUserAgent).orEmpty()
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
                fallbackMaxAttempts = prefs.getInt("fallbackMaxAttempts", 5).coerceIn(1, 50),
                searchUserAgent = prefs.getString("searchUserAgent", legacyAgent)?.takeIf(::isValidUserAgent).orEmpty(),
                downloadUserAgent = prefs.getString("downloadUserAgent", legacyAgent)?.takeIf(::isValidUserAgent).orEmpty()
            )
        }
    }
}
