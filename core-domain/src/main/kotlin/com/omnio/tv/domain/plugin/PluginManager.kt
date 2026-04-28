package com.omnio.tv.domain.plugin

import com.omnio.tv.domain.model.LocalScraperResult
import com.omnio.tv.domain.model.PluginRepository
import com.omnio.tv.domain.model.ScraperInfo
import kotlinx.coroutines.flow.Flow

interface PluginManager {

    val repositories: Flow<List<PluginRepository>>

    val scrapers: Flow<List<ScraperInfo>>

    val pluginsEnabled: Flow<Boolean>

    val enabledScrapers: Flow<List<ScraperInfo>>

    var isSyncingFromRemote: Boolean

    suspend fun addRepository(manifestUrl: String): Result<PluginRepository>

    suspend fun removeRepository(repoId: String)

    suspend fun reconcileWithRemoteRepoUrls(
        remoteUrls: List<String>,
        removeMissingLocal: Boolean = true
    )

    suspend fun refreshRepository(repoId: String): Result<Unit>

    suspend fun toggleScraper(scraperId: String, enabled: Boolean)

    suspend fun setPluginsEnabled(enabled: Boolean)

    suspend fun executeScrapers(
        tmdbId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null
    ): List<LocalScraperResult>

    fun executeScrapersStreaming(
        tmdbId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null
    ): Flow<Pair<String, List<LocalScraperResult>>>

    suspend fun executeScraper(
        scraper: ScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult>

    suspend fun testScraper(scraperId: String): Result<List<LocalScraperResult>>
}
