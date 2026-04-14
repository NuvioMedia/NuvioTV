package com.nuvio.tv.core.plugin

import android.util.Log
import com.lagradost.cloudstream3.TvType
import com.nuvio.tv.core.plugin.cloudstream.toNuvioType
import com.nuvio.tv.core.plugin.cloudstream.tvTypeFromString
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.nuvio.tv.core.plugin.cloudstream.ExternalExtensionRunner
import com.nuvio.tv.core.plugin.cloudstream.ExternalRepoParser
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.domain.model.ExternalPluginEntry
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginManifest
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RemotePluginInfo
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.ScraperManifestInfo
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PluginManager"
private const val MAX_CONCURRENT_SCRAPERS = 5
private const val MAX_RESULT_ITEMS = 150
private const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024L
private const val MANIFEST_SUFFIX = "/manifest.json"

@Singleton
class PluginManager @Inject constructor(
    private val dataStore: PluginDataStore,
    private val runtime: PluginRuntime,
    private val pluginSyncService: com.nuvio.tv.core.sync.PluginSyncService,
    private val authManager: com.nuvio.tv.core.auth.AuthManager,
    private val externalRepoParser: ExternalRepoParser,
    private val externalExtensionLoader: ExternalExtensionLoader,
    private val externalExtensionRunner: ExternalExtensionRunner
) {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    
    private val manifestAdapter = moshi.adapter(PluginManifest::class.java)
    
    private val httpClient = OkHttpClient.Builder()
        .dns(com.nuvio.tv.core.network.IPv4FirstDns())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(((b.toInt() shr 4) and 0xF).toString(16))
            sb.append((b.toInt() and 0xF).toString(16))
        }
        return sb.toString()
    }

    /**
     * Normalize custom protocol schemes to https://.
     * External repos often use schemes like "cloudstreamrepo://" or "stremio://".
     */
    private fun sanitizeScheme(url: String): String {
        val trimmed = url.trim()
        // Replace any non-http(s) scheme with https://
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = trimmed.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") {
                return "https://${trimmed.substring(schemeEnd + 3)}"
            }
        }
        return trimmed
    }

    /**
     * Check if the input looks like a short code rather than a URL.
     * Short codes are alphanumeric strings without slashes, dots (other than in a domain),
     * or protocol schemes — e.g. "cspr", "0094", "megarepo".
     */
    private fun isShortCode(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        // Has a scheme → not a short code
        if (trimmed.contains("://")) return false
        // Has path separators or dots → likely a URL or domain
        if (trimmed.contains("/") || trimmed.contains(".")) return false
        // Only alphanumeric + hyphens + underscores → short code
        return trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /**
     * Resolve a short code by following the redirect from cutt.ly/{code}.
     * Returns the resolved URL or null if resolution fails.
     */
    private fun resolveShortCode(code: String): String? {
        return try {
            // Use a client that does NOT follow redirects so we can read the Location header
            val noRedirectClient = httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val request = Request.Builder()
                .url("https://cutt.ly/$code")
                .header("User-Agent", "NuvioTV/1.0")
                .build()

            noRedirectClient.newCall(request).execute().use { response ->
                if (response.code in 301..302) {
                    val location = response.header("Location")
                    if (!location.isNullOrBlank()) {
                        Log.d(TAG, "Short code '$code' resolved to: $location")
                        return sanitizeScheme(location)
                    }
                }
                // Some shorteners return 200 with a meta refresh or JS redirect
                // Try following redirects as fallback
                Log.d(TAG, "Short code '$code' returned ${response.code}, trying with redirects")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve short code '$code': ${e.message}")
            null
        } ?: try {
            // Fallback: follow redirects and see where we end up
            val request = Request.Builder()
                .url("https://cutt.ly/$code")
                .header("User-Agent", "NuvioTV/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (finalUrl != "https://cutt.ly/$code" && response.isSuccessful) {
                    Log.d(TAG, "Short code '$code' resolved via redirect chain to: $finalUrl")
                    sanitizeScheme(finalUrl)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback resolve for short code '$code' failed: ${e.message}")
            null
        }
    }

    private fun canonicalizeManifestUrl(url: String): String {
        val trimmed = sanitizeScheme(url).trimEnd('/')
        return if (trimmed.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed$MANIFEST_SUFFIX"
        }
    }

    /**
     * Canonicalize a URL for deduplication. For NuvioTV-style URLs (that don't end in .json),
     * appends /manifest.json. For URLs already ending in .json (external repos), keeps them as-is.
     */
    private fun canonicalizeRepoUrl(url: String): String {
        val trimmed = sanitizeScheme(url).trimEnd('/')
        // If URL already ends with a .json file, it's likely an external repo URL — keep as-is
        if (trimmed.substringAfterLast("/").endsWith(".json", ignoreCase = true)) {
            return trimmed
        }
        // Otherwise canonicalize as NuvioTV manifest
        return canonicalizeManifestUrl(trimmed)
    }

    private fun normalizeUrl(url: String): String = canonicalizeRepoUrl(url).lowercase()
    
    // Single-flight map to prevent duplicate scraper executions
    private val inFlightScrapers = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<LocalScraperResult>>>()
    
    // Semaphore to limit concurrent scrapers
    private val scraperSemaphore = Semaphore(MAX_CONCURRENT_SCRAPERS)
    
    // Flow of all repositories
    val repositories: Flow<List<PluginRepository>> = dataStore.repositories
    
    // Flow of all scrapers
    val scrapers: Flow<List<ScraperInfo>> = dataStore.scrapers
    
    // Flow of plugins enabled state
    val pluginsEnabled: Flow<Boolean> = dataStore.pluginsEnabled
    
    private val syncScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    var isSyncingFromRemote = false

    /** Prevents concurrent reconciliation from StartupSyncService and AccountViewModel */
    private val reconcileMutex = Mutex()

    @Volatile
    private var pendingPushAfterSync = false

    /**
     * Call after setting isSyncingFromRemote = false to push any changes
     * that were made during reconciliation (e.g. repo removals).
     */
    fun flushPendingSync() {
        if (pendingPushAfterSync) {
            pendingPushAfterSync = false
            Log.d(TAG, "flushPendingSync: firing deferred push")
            triggerRemoteSync()
        }
    }

    private var syncJob: kotlinx.coroutines.Job? = null

    private fun triggerRemoteSync() {
        if (isSyncingFromRemote) {
            Log.d(TAG, "triggerRemoteSync: skipped (syncing from remote), will push after sync")
            pendingPushAfterSync = true
            return
        }
        if (!authManager.isAuthenticated) {
            Log.d(TAG, "triggerRemoteSync: skipped (not authenticated, state=${authManager.authState.value})")
            return
        }
        Log.d(TAG, "triggerRemoteSync: scheduling push in 500ms")
        syncJob?.cancel()
        syncJob = syncScope.launch {
            kotlinx.coroutines.delay(500)
            val result = pluginSyncService.pushToRemote()
            Log.d(TAG, "triggerRemoteSync: push result=${result.isSuccess} ${result.exceptionOrNull()?.message ?: ""}")
        }
    }

    // Combined flow of enabled scrapers
    val enabledScrapers: Flow<List<ScraperInfo>> = combine(
        scrapers,
        pluginsEnabled
    ) { scraperList, enabled ->
        if (enabled) scraperList.filter { it.enabled } else emptyList()
    }
    
    /**
     * Add a new repository from manifest URL.
     * Auto-detects format: tries NuvioTV manifest first, then external repo format.
     */
    suspend fun addRepository(manifestUrl: String): Result<PluginRepository> = withContext(Dispatchers.IO) {
        try {
            // Resolve short codes (e.g. "cspr", "0094") via cutt.ly redirect
            val resolvedUrl = if (isShortCode(manifestUrl)) {
                Log.d(TAG, "Input looks like a short code: '$manifestUrl'")
                resolveShortCode(manifestUrl.trim())
                    ?: return@withContext Result.failure(
                        Exception("Failed to resolve short code: $manifestUrl")
                    )
            } else {
                sanitizeScheme(manifestUrl).trimEnd('/')
            }

            val sanitizedUrl = resolvedUrl.trimEnd('/')
            val filename = sanitizedUrl.substringAfterLast("/")
            val isExplicitJsonFile = filename.endsWith(".json", ignoreCase = true)
                    && !filename.equals("manifest.json", ignoreCase = true)

            // If the URL points to a specific .json file (not manifest.json),
            // try external format first to avoid a wasted 404 on the NuvioTV path.
            if (isExplicitJsonFile) {
                Log.d(TAG, "URL ends in .json — trying external format first: $sanitizedUrl")
                val externalResult = externalRepoParser.tryParse(sanitizedUrl)
                if (externalResult != null) {
                    return@withContext addExternalRepository(sanitizedUrl, externalResult)
                }
            }

            // Try NuvioTV format (with canonicalized /manifest.json URL)
            val canonicalManifestUrl = canonicalizeManifestUrl(sanitizedUrl)
            Log.d(TAG, "Trying NuvioTV manifest: $canonicalManifestUrl")

            val manifest = fetchManifest(canonicalManifestUrl)
            if (manifest != null) {
                return@withContext addNuvioRepository(canonicalManifestUrl, manifest)
            }

            // If we haven't tried external format yet, try it now
            if (!isExplicitJsonFile) {
                Log.d(TAG, "NuvioTV manifest not found, trying external format: $sanitizedUrl")
                val externalResult = externalRepoParser.tryParse(sanitizedUrl)
                if (externalResult != null) {
                    return@withContext addExternalRepository(sanitizedUrl, externalResult)
                }
            }

            Result.failure(Exception("Failed to parse repository: unrecognized format"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add repository: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Add a repository with a type hint from Supabase sync.
     * Skips wrong detection paths when the type is already known,
     * making reconciliation faster and more resilient to network issues.
     */
    private suspend fun addRepositoryWithTypeHint(
        manifestUrl: String,
        typeHint: RepositoryType?
    ): Result<PluginRepository> = withContext(Dispatchers.IO) {
        try {
            val sanitizedUrl = sanitizeScheme(manifestUrl).trimEnd('/')

            when (typeHint) {
                RepositoryType.EXTERNAL_DEX -> {
                    Log.d(TAG, "addRepositoryWithTypeHint: EXTERNAL_DEX hint, trying external format: $sanitizedUrl")
                    val externalResult = externalRepoParser.tryParse(sanitizedUrl)
                    if (externalResult != null) {
                        return@withContext addExternalRepository(sanitizedUrl, externalResult)
                    }
                    // Hint was wrong or parse failed — fall through to auto-detect
                    Log.w(TAG, "addRepositoryWithTypeHint: EXTERNAL_DEX hint failed, falling back to auto-detect")
                }
                RepositoryType.NUVIO_JS -> {
                    Log.d(TAG, "addRepositoryWithTypeHint: NUVIO_JS hint, trying manifest: $sanitizedUrl")
                    val canonicalManifestUrl = canonicalizeManifestUrl(sanitizedUrl)
                    val manifest = fetchManifest(canonicalManifestUrl)
                    if (manifest != null) {
                        return@withContext addNuvioRepository(canonicalManifestUrl, manifest)
                    }
                    Log.w(TAG, "addRepositoryWithTypeHint: NUVIO_JS hint failed, falling back to auto-detect")
                }
                null -> { /* No hint — use auto-detect */ }
            }

            // Fall back to full auto-detection
            addRepository(sanitizedUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add repository with hint: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun addNuvioRepository(
        canonicalManifestUrl: String,
        manifest: PluginManifest
    ): Result<PluginRepository> {
        val repo = PluginRepository(
            id = UUID.randomUUID().toString(),
            name = manifest.name,
            url = canonicalManifestUrl,
            enabled = true,
            lastUpdated = System.currentTimeMillis(),
            scraperCount = manifest.scrapers.size,
            type = RepositoryType.NUVIO_JS
        )

        dataStore.addRepository(repo)
        downloadJsScrapers(repo.id, canonicalManifestUrl, manifest.scrapers)

        Log.d(TAG, "NuvioTV repository added: ${repo.name} with ${manifest.scrapers.size} scrapers")
        triggerRemoteSync()
        return Result.success(repo)
    }

    private suspend fun addExternalRepository(
        repoUrl: String,
        parseResult: com.nuvio.tv.core.plugin.cloudstream.ExternalRepoParseResult
    ): Result<PluginRepository> {
        // Prevent duplicate repos by URL
        val existingRepo = dataStore.repositories.first()
            .find { normalizeUrl(it.url) == normalizeUrl(repoUrl) }
        if (existingRepo != null) {
            Log.d(TAG, "External repository already exists: ${existingRepo.name} (${existingRepo.url})")
            return Result.success(existingRepo)
        }

        val repo = PluginRepository(
            id = UUID.randomUUID().toString(),
            name = parseResult.name,
            url = repoUrl,
            description = parseResult.description,
            enabled = true,
            lastUpdated = System.currentTimeMillis(),
            scraperCount = parseResult.plugins.size,
            type = RepositoryType.EXTERNAL_DEX
        )

        dataStore.addRepository(repo)
        downloadDexExtensions(repo.id, parseResult.plugins)

        Log.d(TAG, "External repository added: ${repo.name} with ${parseResult.plugins.size} extensions")
        triggerRemoteSync()
        return Result.success(repo)
    }
    
    /**
     * Remove a repository and its scrapers
     */
    suspend fun removeRepository(repoId: String) {
        val scraperList = dataStore.scrapers.first()
        val repo = dataStore.repositories.first().find { it.id == repoId }

        // Remove all scrapers from this repo
        scraperList.filter { it.repositoryId == repoId }.forEach { scraper ->
            if (scraper.type == RepositoryType.EXTERNAL_DEX || repo?.type == RepositoryType.EXTERNAL_DEX) {
                externalExtensionLoader.deleteExtension(scraper.id)
            } else {
                dataStore.deleteScraperCode(scraper.id)
            }
        }

        // Remove scrapers from list
        val updatedScrapers = scraperList.filter { it.repositoryId != repoId }
        dataStore.saveScrapers(updatedScrapers)

        // Remove repository
        dataStore.removeRepository(repoId)

        // Push synchronously when user-initiated (not during reconciliation)
        // to prevent the next sync pull from re-adding the removed repo
        if (!isSyncingFromRemote && authManager.isAuthenticated) {
            Log.d(TAG, "removeRepository: pushing removal to remote synchronously")
            pluginSyncService.pushToRemote()
        } else if (isSyncingFromRemote) {
            pendingPushAfterSync = true
        }
    }

    
    /**
     * Reconcile local plugin repos with the remote list from Supabase.
     * @param remotePlugins list of remote plugin info (URL + optional type hint)
     * @param removeMissingLocal if true, remove local repos not in the remote list
     */
    suspend fun reconcileWithRemoteRepoUrls(
        remotePlugins: List<RemotePluginInfo>,
        removeMissingLocal: Boolean = true
    ) = reconcileMutex.withLock {
        val normalizedRemote = remotePlugins
            .map { it.copy(url = canonicalizeRepoUrl(it.url)) }
            .filter { it.url.isNotEmpty() }
            .distinctBy { normalizeUrl(it.url) }
        val remoteUrlSet = normalizedRemote.map { normalizeUrl(it.url) }.toSet()

        val initialLocalRepos = dataStore.repositories.first()
        val initialLocalByNormalizedUrl = initialLocalRepos.associateBy { normalizeUrl(it.url) }
        val shouldRemoveMissingLocal = if (removeMissingLocal && normalizedRemote.isEmpty() && initialLocalRepos.isNotEmpty()) {
            Log.w(
                TAG,
                "reconcileWithRemoteRepoUrls: remote list empty while local has ${initialLocalRepos.size} repos; preserving local plugins"
            )
            false
        } else {
            removeMissingLocal
        }

        if (shouldRemoveMissingLocal) {
            initialLocalRepos
                .filter { normalizeUrl(it.url) !in remoteUrlSet }
                .forEach { repo ->
                    Log.d(TAG, "reconcile: removing local repo not in remote: ${repo.name} (${repo.url})")
                    removeRepository(repo.id)
                }
        }

        normalizedRemote.forEach { remotePlugin ->
            if (initialLocalByNormalizedUrl[normalizeUrl(remotePlugin.url)] == null) {
                val typeHint = remotePlugin.repoType?.let {
                    try { RepositoryType.valueOf(it) } catch (_: Exception) { null }
                }
                val result = addRepositoryWithTypeHint(remotePlugin.url, typeHint)
                if (result.isFailure) {
                    Log.e(TAG, "reconcile: failed to add repo ${remotePlugin.url}: ${result.exceptionOrNull()?.message}")
                }
            }
        }

        val currentRepos = dataStore.repositories.first()
        val currentByNormalizedUrl = currentRepos.associateBy { normalizeUrl(it.url) }
        val remoteOrderedRepos = normalizedRemote
            .mapNotNull { currentByNormalizedUrl[normalizeUrl(it.url)] }
        val extras = currentRepos
            .filter { normalizeUrl(it.url) !in remoteUrlSet }

        val reordered = if (shouldRemoveMissingLocal) remoteOrderedRepos else remoteOrderedRepos + extras
        if (reordered.map { it.id } != currentRepos.map { it.id }) {
            dataStore.saveRepositories(reordered)
        }
    }

    /** Convenience overload for plain URL lists (no type hints) */
    @JvmName("reconcileWithRemoteRepoUrlStrings")
    suspend fun reconcileWithRemoteRepoUrls(
        remoteUrls: List<String>,
        removeMissingLocal: Boolean = true
    ) {
        reconcileWithRemoteRepoUrls(
            remotePlugins = remoteUrls.map { RemotePluginInfo(url = it) },
            removeMissingLocal = removeMissingLocal
        )
    }
    
    /**
     * Refresh a repository - re-download manifest and scrapers
     */
    suspend fun refreshRepository(repoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val repo = dataStore.repositories.first().find { it.id == repoId }
                ?: return@withContext Result.failure(Exception("Repository not found"))

            if (repo.type == RepositoryType.EXTERNAL_DEX) {
                return@withContext refreshExternalRepository(repo)
            }

            val manifest = fetchManifest(repo.url)
                ?: return@withContext Result.failure(Exception("Failed to fetch manifest"))

            // Update repository
            val updatedRepo = repo.copy(
                name = manifest.name,
                lastUpdated = System.currentTimeMillis(),
                scraperCount = manifest.scrapers.size
            )
            dataStore.updateRepository(updatedRepo)

            // Re-download scrapers
            downloadJsScrapers(repo.id, repo.url, manifest.scrapers)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh repository: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun refreshExternalRepository(repo: PluginRepository): Result<Unit> {
        val parseResult = externalRepoParser.tryParse(repo.url)
            ?: return Result.failure(Exception("Failed to parse external repository"))

        // Evict stale class loaders for old scrapers
        val oldScrapers = dataStore.scrapers.first().filter { it.repositoryId == repo.id }
        oldScrapers.forEach { externalExtensionLoader.evictCache(it.id) }

        val updatedRepo = repo.copy(
            name = parseResult.name,
            lastUpdated = System.currentTimeMillis(),
            scraperCount = parseResult.plugins.size
        )
        dataStore.updateRepository(updatedRepo)

        downloadDexExtensions(repo.id, parseResult.plugins)

        return Result.success(Unit)
    }
    
    /**
     * Toggle scraper enabled state
     */
    suspend fun toggleScraper(scraperId: String, enabled: Boolean) {
        val scraperList = dataStore.scrapers.first()
        val updatedScrapers = scraperList.map { scraper ->
            if (scraper.id == scraperId) scraper.copy(enabled = enabled) else scraper
        }
        dataStore.saveScrapers(updatedScrapers)
    }

    /**
     * Toggle all scrapers belonging to a repository
     */
    suspend fun toggleAllScrapersForRepo(repoId: String, enabled: Boolean) {
        val scraperList = dataStore.scrapers.first()
        val updatedScrapers = scraperList.map { scraper ->
            if (scraper.repositoryId == repoId) scraper.copy(enabled = enabled) else scraper
        }
        dataStore.saveScrapers(updatedScrapers)
    }

    /**
     * Toggle plugins globally enabled
     */
    suspend fun setPluginsEnabled(enabled: Boolean) {
        dataStore.setPluginsEnabled(enabled)
    }
    
    /**
     * Execute all enabled scrapers for a given media
     */
    suspend fun executeScrapers(
        tmdbId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null
    ): List<LocalScraperResult> = coroutineScope {
        if (!dataStore.pluginsEnabled.first()) {
            return@coroutineScope emptyList()
        }
        
        val enabledScraperList = enabledScrapers.first()
            .filter { it.supportsType(mediaType) }
        
        if (enabledScraperList.isEmpty()) {
            return@coroutineScope emptyList()
        }
        
        Log.d(TAG, "Executing ${enabledScraperList.size} scrapers for $mediaType:$tmdbId")

        // Preload all extractors from EXTERNAL_DEX repos before any scraper runs
        val dexScraperIds = enabledScraperList
            .filter { it.type == RepositoryType.EXTERNAL_DEX }
            .map { it.id }
        if (dexScraperIds.isNotEmpty()) {
            // Also load ALL dex scrapers from the same repos (not just enabled ones)
            // since extractors can live in any .cs3 file
            val allDexIds = dataStore.scrapers.first()
                .filter { it.type == RepositoryType.EXTERNAL_DEX }
                .map { it.id }
            externalExtensionLoader.ensureExtractorsLoaded(allDexIds)
        }

        val results = enabledScraperList.map { scraper ->
            async {
                executeScraperWithSingleFlight(scraper, tmdbId, mediaType, season, episode)
            }
        }.awaitAll()
        
        results.flatten()
            .distinctBy { it.url }
            .take(MAX_RESULT_ITEMS)
    }
    
    /**
     * Execute all enabled scrapers and emit results as each scraper completes.
     * Returns a Flow that emits (scraperName, results) pairs.
     */
    fun executeScrapersStreaming(
        tmdbId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null
    ): Flow<Pair<String, List<LocalScraperResult>>> = channelFlow {
        val enabledList = enabledScrapers.first()
            .filter { it.supportsType(mediaType) }
        
        if (enabledList.isEmpty() || !dataStore.pluginsEnabled.first()) {
            return@channelFlow
        }
        
        Log.d(TAG, "Streaming execution of ${enabledList.size} scrapers for $mediaType:$tmdbId")

        // Preload all extractors from EXTERNAL_DEX repos before any scraper runs
        val dexScraperIds = enabledList.filter { it.type == RepositoryType.EXTERNAL_DEX }.map { it.id }
        if (dexScraperIds.isNotEmpty()) {
            val allDexIds = dataStore.scrapers.first()
                .filter { it.type == RepositoryType.EXTERNAL_DEX }
                .map { it.id }
            externalExtensionLoader.ensureExtractorsLoaded(allDexIds)
        }

        // Launch all scrapers concurrently within the channelFlow scope
        enabledList.forEach { scraper ->
            launch {
                try {
                    val results = executeScraperWithSingleFlight(scraper, tmdbId, mediaType, season, episode)
                    if (results.isNotEmpty()) {
                        send(scraper.name to results)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Scraper ${scraper.name} failed in streaming: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Execute a single scraper with single-flight deduplication
     */
    private suspend fun executeScraperWithSingleFlight(
        scraper: ScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        val cacheKey = "${scraper.id}:$tmdbId:$mediaType:$season:$episode"
        
        // Check if already in flight
        val existing = inFlightScrapers[cacheKey]
        if (existing != null) {
            return try {
                existing.await()
            } catch (e: Exception) {
                emptyList()
            }
        }
        
        // Create new deferred
        return coroutineScope {
            val deferred = async {
                scraperSemaphore.withPermit {
                    executeScraper(scraper, tmdbId, mediaType, season, episode)
                }
            }
            
            inFlightScrapers[cacheKey] = deferred
            
            try {
                deferred.await()
            } catch (e: Exception) {
                Log.e(TAG, "Scraper ${scraper.name} failed: ${e.message}")
                emptyList()
            } finally {
                inFlightScrapers.remove(cacheKey)
            }
        }
    }
    
    /**
     * Execute a single scraper, dispatching by type.
     */
    suspend fun executeScraper(
        scraper: ScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        return when (scraper.type) {
            RepositoryType.EXTERNAL_DEX -> executeExternalDexScraper(scraper, tmdbId, mediaType, season, episode)
            RepositoryType.NUVIO_JS -> executeJsScraper(scraper, tmdbId, mediaType, season, episode)
        }
    }

    private suspend fun executeJsScraper(
        scraper: ScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        return try {
            val code = dataStore.getScraperCode(scraper.id)
            if (code.isNullOrBlank()) {
                Log.w(TAG, "No code found for scraper: ${scraper.name}")
                return emptyList()
            }

            // Debug: confirm which exact JS code is running on-device.
            try {
                val sha = sha256Hex(code)
                val bytes = code.toByteArray(Charsets.UTF_8).size
                val hasHrefliLogs = code.contains("[UHDMovies][Hrefli]", ignoreCase = false) ||
                    code.contains("[Hrefli]", ignoreCase = false)
                Log.d(
                    TAG,
                    "Scraper code loaded: ${scraper.name}(${scraper.id}) bytes=$bytes sha256=${sha.take(12)} hrefliLogs=$hasHrefliLogs"
                )
            } catch (_: Exception) {
                // ignore
            }

            val settings = dataStore.getScraperSettings(scraper.id)
            
            Log.d(TAG, "Executing scraper: ${scraper.name}")
            val results = withContext(Dispatchers.IO + NonCancellable) {
                runtime.executePlugin(
                    code = code,
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    season = season,
                    episode = episode,
                    scraperId = scraper.id,
                    scraperSettings = settings
                )
            }
            
            Log.d(TAG, "Scraper ${scraper.name} returned ${results.size} results")
            results.map { it.copy(provider = scraper.name) }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute scraper ${scraper.name}: ${e.message}", e)
            emptyList()
        }
    }

    private suspend fun executeExternalDexScraper(
        scraper: ScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ): List<LocalScraperResult> {
        return try {
            Log.d(TAG, "Executing DEX scraper: ${scraper.name}")
            val results = externalExtensionRunner.execute(scraper.id, tmdbId, mediaType, season, episode)
            Log.d(TAG, "DEX scraper ${scraper.name} returned ${results.size} results")
            results.map { it.copy(provider = scraper.name) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute DEX scraper ${scraper.name}: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Test a scraper with sample data, returning results along with diagnostic steps.
     */
    suspend fun testScraper(scraperId: String): Result<Pair<List<LocalScraperResult>, TestDiagnostics>> {
        val diagnostics = TestDiagnostics()
        val scraper = dataStore.scrapers.first().find { it.id == scraperId }
        if (scraper == null) {
            diagnostics.addStep("Scraper '$scraperId' not found in datastore")
            return Result.failure(Exception("Scraper not found"))
        }

        diagnostics.addStep("Scraper: ${scraper.name} (type=${scraper.type})")

        // Use a popular movie for testing (The Matrix - 603)
        val testTmdbId = "603"
        val testMediaType = if (scraper.supportsType("movie")) "movie" else "series"
        diagnostics.addStep("Test: TMDB $testTmdbId ($testMediaType)")

        // Preload extractors from ALL .cs3 files in the same repo(s)
        if (scraper.type == RepositoryType.EXTERNAL_DEX) {
            val allDexIds = dataStore.scrapers.first()
                .filter { it.type == RepositoryType.EXTERNAL_DEX }
                .map { it.id }
            externalExtensionLoader.ensureExtractorsLoaded(allDexIds, diagnostics)
        }

        val testSeason = if (testMediaType == "movie") null else 1
        val testEpisode = if (testMediaType == "movie") null else 1

        return try {
            val results = when (scraper.type) {
                RepositoryType.EXTERNAL_DEX -> {
                    externalExtensionRunner.executeWithDiagnostics(
                        scraper.id, testTmdbId, testMediaType, testSeason, testEpisode, diagnostics
                    )
                }
                RepositoryType.NUVIO_JS -> {
                    diagnostics.addStep("Executing JS scraper...")
                    executeScraper(scraper, testTmdbId, testMediaType, testSeason, testEpisode)
                }
            }
            diagnostics.addStep("Result: ${results.size} streams")
            Result.success(results to diagnostics)
        } catch (e: Exception) {
            diagnostics.addStep("Exception: ${e.javaClass.simpleName}: ${e.message}")
            Result.success(emptyList<LocalScraperResult>() to diagnostics)
        }
    }
    
    private suspend fun fetchManifest(url: String): PluginManifest? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NuvioTV/1.0")
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch manifest: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                manifestAdapter.fromJson(body)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching manifest: ${e.message}", e)
            null
        }
    }
    
    private suspend fun downloadJsScrapers(
        repoId: String,
        manifestUrl: String,
        scraperInfos: List<ScraperManifestInfo>
    ) = withContext(Dispatchers.IO) {
        val baseUrl = manifestUrl.substringBeforeLast("/")
        val existingScrapers = dataStore.scrapers.first().toMutableList()
        
        scraperInfos.forEach { info ->
            try {
                val codeUrl = if (info.filename.startsWith("http")) {
                    info.filename
                } else {
                    "$baseUrl/${info.filename}"
                }
                
                // Check response size before downloading
                val headRequest = Request.Builder()
                    .url(codeUrl)
                    .head()
                    .build()
                
                val contentLength = httpClient.newCall(headRequest).execute().use { headResponse ->
                    headResponse.header("Content-Length")?.toLongOrNull() ?: 0
                }
                
                if (contentLength > MAX_RESPONSE_SIZE) {
                    Log.w(TAG, "Scraper ${info.name} too large: $contentLength bytes")
                    return@forEach
                }
                
                // Download code
                val codeRequest = Request.Builder()
                    .url(codeUrl)
                    .header("User-Agent", "NuvioTV/1.0")
                    .build()
                
                val code = httpClient.newCall(codeRequest).execute().use { codeResponse ->
                    if (!codeResponse.isSuccessful) {
                        Log.e(TAG, "Failed to download scraper ${info.name}: ${codeResponse.code}")
                        return@forEach
                    }

                    codeResponse.body?.string() ?: return@forEach
                }

                try {
                    val sha = sha256Hex(code)
                    val hasHrefliLogs = code.contains("[UHDMovies][Hrefli]", ignoreCase = false) ||
                        code.contains("[Hrefli]", ignoreCase = false)
                    Log.d(
                        TAG,
                        "Downloaded scraper code: ${info.name}(${info.id}) bytes=${code.toByteArray(Charsets.UTF_8).size} sha256=${sha.take(12)} hrefliLogs=$hasHrefliLogs url=$codeUrl"
                    )
                } catch (_: Exception) {
                    // ignore
                }
                
                // Create scraper info
                val scraperId = "$repoId:${info.id}"
                val existingScraper = existingScrapers.firstOrNull { it.id == scraperId }
                val defaultEnabled = info.enabled &&
                    !PluginSafety.isVideoEasyScraper(
                        scraperId = info.id,
                        scraperName = info.name,
                        filename = info.filename
                    )
                val scraper = ScraperInfo(
                    id = scraperId,
                    repositoryId = repoId,
                    name = info.name,
                    description = info.description ?: "",
                    version = info.version,
                    filename = info.filename,
                    supportedTypes = info.supportedTypes,
                    enabled = existingScraper?.enabled ?: defaultEnabled,
                    manifestEnabled = info.enabled,
                    logo = info.logo,
                    contentLanguage = info.contentLanguage ?: emptyList(),
                    formats = info.formats
                )
                
                // Save code
                dataStore.saveScraperCode(scraperId, code)
                
                // Update scraper list
                existingScrapers.removeAll { it.id == scraperId }
                existingScrapers.add(scraper)
                
                Log.d(TAG, "Downloaded scraper: ${info.name}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading scraper ${info.name}: ${e.message}", e)
            }
        }
        
        dataStore.saveScrapers(existingScrapers)
    }

    /**
     * Download .cs3 DEX extensions in parallel and register them as scrapers.
     * Uses a semaphore to limit concurrent downloads and avoid overwhelming
     * the network. Scrapers are saved incrementally in batches.
     */
    private suspend fun downloadDexExtensions(
        repoId: String,
        plugins: List<ExternalPluginEntry>
    ) = withContext(Dispatchers.IO) {
        val existingScrapers = dataStore.scrapers.first().toMutableList()
        val downloadSemaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
        val newScrapers = java.util.Collections.synchronizedList(mutableListOf<ScraperInfo>())

        // Download all extensions in parallel with limited concurrency
        val jobs = plugins.map { plugin ->
            async {
                downloadSemaphore.withPermit {
                    try {
                        val scraperId = "$repoId:${plugin.internalName}"

                        val file = externalExtensionLoader.downloadExtension(scraperId, plugin.url)
                        if (file == null) {
                            Log.e(TAG, "Failed to download extension: ${plugin.name}")
                            return@withPermit
                        }

                        val supportedTypes = plugin.tvTypes
                            ?.mapNotNull { tvTypeFromString(it) }
                            ?.map { it.toNuvioType() }
                            ?.distinct()
                            ?.ifEmpty { listOf("movie", "tv") }
                            ?: listOf("movie", "tv")

                        val scraper = ScraperInfo(
                            id = scraperId,
                            repositoryId = repoId,
                            name = plugin.name,
                            description = plugin.description ?: "",
                            version = plugin.version.toString(),
                            filename = plugin.url,
                            supportedTypes = supportedTypes,
                            enabled = true,
                            manifestEnabled = plugin.status == 1,
                            logo = plugin.iconUrl,
                            contentLanguage = emptyList(),
                            formats = null,
                            type = RepositoryType.EXTERNAL_DEX
                        )

                        newScrapers.add(scraper)
                        Log.d(TAG, "Downloaded DEX extension: ${plugin.name} (${file.length()} bytes)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error downloading extension ${plugin.name}: ${e.message}", e)
                    }
                }
            }
        }

        jobs.awaitAll()

        // Merge new scrapers into existing list
        val newScraperIds = newScrapers.map { it.id }.toSet()
        existingScrapers.removeAll { it.id in newScraperIds }
        existingScrapers.addAll(newScrapers)
        dataStore.saveScrapers(existingScrapers)

        Log.d(TAG, "Downloaded ${newScrapers.size}/${plugins.size} extensions for repo $repoId")
    }

    companion object {
        private const val MAX_PARALLEL_DOWNLOADS = 10
    }
}
