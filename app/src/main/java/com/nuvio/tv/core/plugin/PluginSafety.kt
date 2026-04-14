package com.nuvio.tv.core.plugin

internal object PluginSafety {
    fun isVideoEasyScraper(
        scraperId: String?,
        scraperName: String? = null,
        filename: String? = null
    ): Boolean {
        return listOf(scraperId, scraperName, filename).any { value ->
            value?.contains("videasy", ignoreCase = true) == true
        }
    }
}
