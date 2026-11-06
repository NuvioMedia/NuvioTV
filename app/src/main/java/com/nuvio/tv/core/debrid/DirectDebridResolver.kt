package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.player.EpisodeMismatchDetector
import com.nuvio.tv.core.player.FilenameEpisodeMatcher
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val torboxResolver: TorboxDirectDebridResolver,
    private val realDebridResolver: RealDebridDirectDebridResolver,
    private val premiumizeResolver: PremiumizeDirectDebridResolver,
    private val localDebridService: LocalDebridService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val resolvedCache = mutableMapOf<String, CachedDirectDebridResolve>()
    private val inFlightResolves = mutableMapOf<String, Deferred<DirectDebridResolveResult>>()

    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return DirectDebridResolveResult.Stale
        }
        val cacheKey = stream.directDebridResolveCacheKey(season, episode)
        if (cacheKey == null) {
            return resolveUncached(stream, season, episode)
        }
        getCachedResult(cacheKey)?.let {
            return it
        }

        var ownsResolve = false
        val newResolve = scope.async(start = CoroutineStart.LAZY) {
            resolveUncached(stream, season, episode)
        }
        val activeResolve = mutex.withLock {
            getCachedResultLocked(cacheKey)?.let { cached ->
                return@withLock null to cached
            }
            val existing = inFlightResolves[cacheKey]
            if (existing != null) {
                existing to null
            } else {
                inFlightResolves[cacheKey] = newResolve
                ownsResolve = true
                newResolve to null
            }
        }
        activeResolve.second?.let {
            newResolve.cancel()
            return it
        }
        val deferred = activeResolve.first ?: return DirectDebridResolveResult.Error
        if (!ownsResolve) newResolve.cancel()
        if (ownsResolve) deferred.start()

        return try {
            val result = deferred.await()
            if (ownsResolve && result is DirectDebridResolveResult.Success) {
                mutex.withLock {
                    resolvedCache[cacheKey] = CachedDirectDebridResolve(
                        result = result,
                        cachedAtMs = System.currentTimeMillis()
                    )
                }
            }
            result
        } finally {
            if (ownsResolve) {
                mutex.withLock {
                    if (inFlightResolves[cacheKey] === deferred) {
                        inFlightResolves.remove(cacheKey)
                    }
                }
            }
        }
    }

    suspend fun cachedPlayableStream(stream: Stream, season: Int?, episode: Int?): Stream? {
        if (!shouldResolveToPlayableStream(stream)) return null
        val cacheKey = stream.directDebridResolveCacheKey(season, episode) ?: return null
        return getCachedResult(cacheKey)?.let { result -> stream.withResolvedDebridUrl(result) }
    }

    suspend fun resolveToPlayableStream(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridPlayableResult {
        if (!shouldResolveToPlayableStream(stream)) {
            return DirectDebridPlayableResult.Success(stream)
        }
        return when (val result = resolve(stream, season, episode)) {
            is DirectDebridResolveResult.Success -> DirectDebridPlayableResult.Success(stream.withResolvedDebridUrl(result))
            DirectDebridResolveResult.MissingApiKey -> DirectDebridPlayableResult.MissingApiKey
            DirectDebridResolveResult.NotCached -> DirectDebridPlayableResult.NotCached
            DirectDebridResolveResult.Stale -> DirectDebridPlayableResult.Stale
            DirectDebridResolveResult.Error -> DirectDebridPlayableResult.Error
        }
    }

    suspend fun shouldResolveToPlayableStream(stream: Stream): Boolean {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return false
        if (stream.needsLocalDebridResolve()) {
            return localTorrentResolveCredential(settings) != null
        }
        if (!stream.isDirectDebrid() || stream.getStreamUrl() != null) return false
        val providerId = DebridProviders.byId(stream.clientResolve?.service)?.id ?: return false
        return providerId == settings.activeResolverProviderId &&
            settings.apiKeyFor(providerId).isNotBlank()
    }

    private suspend fun getCachedResult(cacheKey: String): DirectDebridResolveResult.Success? =
        mutex.withLock { getCachedResultLocked(cacheKey) }

    private fun getCachedResultLocked(cacheKey: String): DirectDebridResolveResult.Success? {
        val cached = resolvedCache[cacheKey] ?: return null
        val age = System.currentTimeMillis() - cached.cachedAtMs
        return if (age in 0..DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS) {
            cached.result
        } else {
            resolvedCache.remove(cacheKey)
            null
        }
    }

    private suspend fun resolveUncached(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        if (stream.needsLocalDebridResolve()) {
            return resolveLocalTorrentStream(stream, season, episode)
        }
        return when (DebridProviders.byId(stream.clientResolve?.service)?.id) {
            DebridProviders.TORBOX_ID -> torboxResolver.resolve(stream, season, episode)
            DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(stream, season, episode)
            DebridProviders.REAL_DEBRID_ID -> realDebridResolver.resolve(stream, season, episode)
            else -> DirectDebridResolveResult.Error
        }
    }

    private suspend fun Stream.directDebridResolveCacheKey(season: Int?, episode: Int?): String? {
        if (needsLocalDebridResolve()) {
            val settings = dataStore.settings.first()
            val account = localTorrentResolveCredential(settings) ?: return null
            val apiKey = account.apiKey.trim().takeIf { it.isNotBlank() } ?: return null
            val identity = getEffectiveInfoHash() ?: torrentMagnetUri() ?: behaviorHints?.filename ?: return null
            return listOf(
                account.provider.id,
                apiKey.stableFingerprint(),
                identity.trim().lowercase(),
                getEffectiveFileIdx()?.toString().orEmpty(),
                behaviorHints?.filename.orEmpty().trim().lowercase(),
                season?.toString().orEmpty(),
                episode?.toString().orEmpty()
            ).joinToString("|")
        }
        val resolve = clientResolve ?: return null
        val providerId = DebridProviders.byId(resolve.service)?.id ?: return null
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks || providerId != settings.activeResolverProviderId) return null
        val apiKey = settings.apiKeyFor(providerId).trim().takeIf { it.isNotBlank() } ?: return null
        val identity = resolve.infoHash
            ?: resolve.magnetUri
            ?: resolve.torrentName
            ?: resolve.filename
            ?: return null

        return listOf(
            providerId,
            apiKey.stableFingerprint(),
            identity.trim().lowercase(),
            resolve.fileIdx?.toString().orEmpty(),
            (resolve.filename ?: behaviorHints?.filename).orEmpty().trim().lowercase(),
            (season ?: resolve.season)?.toString().orEmpty(),
            (episode ?: resolve.episode)?.toString().orEmpty()
        ).joinToString("|")
    }

    private suspend fun resolveLocalTorrentStream(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val settings = dataStore.settings.first()
        val account = localTorrentResolveCredential(settings) ?: return DirectDebridResolveResult.MissingApiKey
        val hash = stream.getEffectiveInfoHash()?.trim()?.lowercase()
        if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) {
            return DirectDebridResolveResult.NotCached
        }
        if (
            !hash.isNullOrBlank() &&
            stream.debridCacheStatus?.state != StreamDebridCacheState.CACHED &&
            account.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck)
        ) {
            when (localDebridService.isCached(account, hash)) {
                false -> return DirectDebridResolveResult.NotCached
                true, null -> Unit
            }
        }

        val magnet = DebridMagnetBuilder.fromStream(stream)
            ?: return DirectDebridResolveResult.Stale
        val resolveStream = stream.copy(
            clientResolve = StreamClientResolve(
                type = "torrent",
                infoHash = stream.getEffectiveInfoHash(),
                fileIdx = stream.getEffectiveFileIdx(),
                magnetUri = magnet,
                sources = stream.sources,
                torrentName = null,
                filename = stream.behaviorHints?.filename,
                mediaType = null,
                mediaId = null,
                mediaOnlyId = null,
                title = stream.title ?: stream.name,
                season = season,
                episode = episode,
                service = account.provider.id,
                serviceIndex = null,
                serviceExtension = null,
                isCached = stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED,
                stream = null
            )
        )

        return when (account.provider.id) {
            DebridProviders.TORBOX_ID -> torboxResolver.resolve(resolveStream, season, episode)
            DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(resolveStream, season, episode)
            else -> DirectDebridResolveResult.Error
        }
    }

    private fun localTorrentResolveCredential(
        settings: com.nuvio.tv.domain.model.DebridSettings
    ): DebridServiceCredential? =
        settings.activeResolverCredential
            ?.takeIf { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentResolve) }

    suspend fun hasSavedDebridKey(): Boolean {
        val settings = dataStore.settings.first()
        return savedMitigationProviderIds(settings, preferredHint = null).isNotEmpty()
    }

    suspend fun mitigateMismatchedEpisodeStream(
        infoHash: String?,
        filenameHint: String?,
        streamUrl: String?,
        bingeGroup: String?,
        sources: List<String>? = null,
        title: String? = null,
        addonName: String = "Debrid",
        season: Int,
        episode: Int
    ): DirectDebridMitigationResult {
        val cleanHash = (infoHash ?: extractEmbeddedInfoHashFrom(streamUrl, bingeGroup))
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return DirectDebridMitigationResult.NotApplicable

        val settings = dataStore.settings.first()
        val providerIds = savedMitigationProviderIds(settings, inferProviderHint(streamUrl, bingeGroup))
        if (providerIds.isEmpty()) return DirectDebridMitigationResult.MissingApiKey

        // Only keep filenameHint if it explicitly names the requested episode without mismatch.
        val validatedFilename = filenameHint?.takeIf { hint ->
            FilenameEpisodeMatcher.extractSeasonEpisode(hint)?.second == episode &&
                EpisodeMismatchDetector.detectMismatch(season, episode, streamUrl, listOf(hint)) == null
        }

        val baseStream = Stream(
            name = title,
            title = title,
            description = null,
            url = null,
            ytId = null,
            infoHash = cleanHash,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = validatedFilename?.let { fn ->
                StreamBehaviorHints(
                    notWebReady = null,
                    bingeGroup = bingeGroup,
                    countryWhitelist = null,
                    proxyHeaders = null,
                    filename = fn
                )
            },
            addonName = addonName,
            addonLogo = null,
            sources = sources
        )
        val magnet = DebridMagnetBuilder.fromStream(baseStream)

        for (providerId in providerIds) {
            val resolveStream = baseStream.copy(
                clientResolve = StreamClientResolve(
                    type = "debrid",
                    infoHash = cleanHash,
                    fileIdx = null,
                    magnetUri = magnet,
                    sources = sources,
                    torrentName = null,
                    filename = validatedFilename,
                    mediaType = "series",
                    mediaId = null,
                    mediaOnlyId = null,
                    title = title,
                    season = season,
                    episode = episode,
                    service = providerId,
                    serviceIndex = null,
                    serviceExtension = null,
                    isCached = true,
                    stream = null
                )
            )

            val resolved = when (providerId) {
                DebridProviders.TORBOX_ID -> torboxResolver.resolve(resolveStream, season, episode)
                DebridProviders.REAL_DEBRID_ID -> realDebridResolver.resolve(resolveStream, season, episode)
                DebridProviders.PREMIUMIZE_ID -> premiumizeResolver.resolve(resolveStream, season, episode)
                else -> DirectDebridResolveResult.Error
            }

            if (resolved is DirectDebridResolveResult.Success) {
                val urlLeaf = resolved.url
                    .substringBefore('#')
                    .substringBefore('?')
                    .substringAfterLast('/')
                    .takeIf { '.' in it }
                val postCheck = EpisodeMismatchDetector.detectMismatch(
                    expectedSeason = season,
                    expectedEpisode = episode,
                    streamUrl = resolved.url,
                    candidateLabels = listOf(resolved.filename, urlLeaf)
                )
                if (postCheck == null) {
                    return DirectDebridMitigationResult.Mitigated(
                        result = resolved,
                        providerName = DebridProviders.displayName(providerId)
                    )
                }
            }
        }

        return DirectDebridMitigationResult.Unresolved
    }

    private fun savedMitigationProviderIds(
        settings: DebridSettings,
        preferredHint: String?
    ): List<String> {
        val candidates = listOfNotNull(
            preferredHint?.let { DebridProviders.byId(it)?.id },
            DebridProviders.byId(settings.preferredResolverProviderId)?.id,
            DebridProviders.TORBOX_ID,
            DebridProviders.REAL_DEBRID_ID,
            DebridProviders.PREMIUMIZE_ID
        ).distinct()
        return candidates.filter { id -> settings.apiKeyFor(id).trim().isNotBlank() }
    }

    private fun inferProviderHint(streamUrl: String?, bingeGroup: String?): String? {
        val bg = bingeGroup?.lowercase().orEmpty()
        when {
            "torbox" in bg -> return DebridProviders.TORBOX_ID
            "realdebrid" in bg || "real-debrid" in bg -> return DebridProviders.REAL_DEBRID_ID
            "premiumize" in bg -> return DebridProviders.PREMIUMIZE_ID
        }
        val path = streamUrl?.substringBefore('?')?.lowercase().orEmpty()
        if ("/resolve/" in path) {
            for (segment in path.substringAfter("/resolve/").split('/').take(2)) {
                when (segment) {
                    "0", "torbox", "tb" -> return DebridProviders.TORBOX_ID
                    "1", "realdebrid", "rd" -> return DebridProviders.REAL_DEBRID_ID
                    "2", "premiumize", "pm" -> return DebridProviders.PREMIUMIZE_ID
                }
            }
        }
        return null
    }
}

private val EMBEDDED_BTIH_HEX_REGEX = Regex("""(?<![0-9a-fA-F])([0-9a-fA-F]{40})(?![0-9a-fA-F])""")

internal fun extractEmbeddedInfoHashFrom(url: String?, bingeGroup: String?): String? {
    val path = url?.substringBefore('?')?.substringBefore('#')
    if (!path.isNullOrBlank()) {
        EMBEDDED_BTIH_HEX_REGEX.find(path)?.groupValues?.getOrNull(1)?.let { return it.lowercase() }
    }
    if (!bingeGroup.isNullOrBlank()) {
        EMBEDDED_BTIH_HEX_REGEX.find(bingeGroup)?.groupValues?.getOrNull(1)?.let { return it.lowercase() }
    }
    return null
}

sealed class DirectDebridMitigationResult {
    data class Mitigated(
        val result: DirectDebridResolveResult.Success,
        val providerName: String
    ) : DirectDebridMitigationResult()
    data object NotApplicable : DirectDebridMitigationResult()
    data object MissingApiKey : DirectDebridMitigationResult()
    data object Unresolved : DirectDebridMitigationResult()
}

private const val DIRECT_DEBRID_RESOLVE_CACHE_TTL_MS = 15L * 60L * 1000L

private data class CachedDirectDebridResolve(
    val result: DirectDebridResolveResult.Success,
    val cachedAtMs: Long
)

sealed class DirectDebridPlayableResult {
    data class Success(val stream: Stream) : DirectDebridPlayableResult()
    data object MissingApiKey : DirectDebridPlayableResult()
    data object NotCached : DirectDebridPlayableResult()
    data object Stale : DirectDebridPlayableResult()
    data object Error : DirectDebridPlayableResult()
}

private fun String.stableFingerprint(): String {
    val hash = fold(1125899906842597L) { acc, char -> (acc * 31L) + char.code }
    return hash.toULong().toString(16)
}

private fun Stream.withResolvedDebridUrl(result: DirectDebridResolveResult.Success): Stream =
    copy(
        url = result.url,
        externalUrl = null,
        behaviorHints = behaviorHints.mergeResolvedDebridHints(result)
    )

private fun StreamBehaviorHints?.mergeResolvedDebridHints(
    result: DirectDebridResolveResult.Success
): StreamBehaviorHints {
    val current = this
    if (current != null) {
        return current.copy(
            filename = result.filename ?: current.filename,
            videoSize = result.videoSize ?: current.videoSize
        )
    }
    return StreamBehaviorHints(
        notWebReady = null,
        bingeGroup = null,
        countryWhitelist = null,
        proxyHeaders = null,
        filename = result.filename,
        videoSize = result.videoSize
    )
}
