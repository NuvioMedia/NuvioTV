package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val DIRECT_DEBRID_PREPARER_TAG = "DirectDebridPreparer"

@Singleton
class DirectDebridStreamPreparer @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val resolver: DirectDebridResolver
) {
    private val budgetMutex = Mutex()
    private val minuteStarts = ArrayDeque<Long>()
    private val hourStarts = ArrayDeque<Long>()

    suspend fun prepare(
        streams: List<Stream>,
        season: Int?,
        episode: Int?,
        playerSettings: PlayerSettings,
        installedAddonNames: Set<String>,
        onPrepared: (original: Stream, prepared: Stream) -> Unit
    ) {
        val settings = dataStore.settings.first()
        val limit = settings.instantPlaybackPreparationLimit
        if (!settings.canResolvePlayableLinks || limit <= 0) return

        val candidates = prioritizeCandidates(
            streams = streams,
            limit = limit,
            playerSettings = playerSettings,
            installedAddonNames = installedAddonNames
        )
        for (stream in candidates) {
            if (!resolver.shouldResolveToPlayableStream(stream)) continue
            resolver.cachedPlayableStream(stream, season, episode)?.let { cached ->
                onPrepared(stream, cached)
                continue
            }

            if (!consumeBackgroundBudget()) {
                Log.d(DIRECT_DEBRID_PREPARER_TAG, "Skipping instant playback preparation; local Torbox budget reached")
                return
            }

            try {
                when (val result = resolver.resolveToPlayableStream(stream, season, episode)) {
                    is DirectDebridPlayableResult.Success -> {
                        if (result.stream.getStreamUrl() != null) {
                            onPrepared(stream, result.stream)
                        }
                    }
                    else -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.d(DIRECT_DEBRID_PREPARER_TAG, "Instant playback preparation failed", error)
            }
        }
    }

    internal fun prioritizeCandidates(
        streams: List<Stream>,
        limit: Int,
        playerSettings: PlayerSettings,
        installedAddonNames: Set<String>
    ): List<Stream> {
        if (limit <= 0) return emptyList()
        val candidates = streams
            .filter { (it.isDirectDebrid() || it.isCachedLocalDebridTorrent()) && it.getStreamUrl() == null }
            .distinctBy { it.preparationKey() }
        if (candidates.isEmpty()) return emptyList()

        val prioritized = mutableListOf<Stream>()
        val autoPlaySelection = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = playerSettings.streamAutoPlayMode,
            regexPattern = playerSettings.streamAutoPlayRegex,
            source = playerSettings.streamAutoPlaySource,
            installedAddonNames = installedAddonNames,
            selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
            selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins
        )
        if (autoPlaySelection?.let { it.isDirectDebrid() || it.isCachedLocalDebridTorrent() } == true) {
            candidates.firstOrNull { it.preparationKey() == autoPlaySelection.preparationKey() }
                ?.let(prioritized::add)
        }

        if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
            val regex = runCatching {
                Regex(playerSettings.streamAutoPlayRegex.trim(), RegexOption.IGNORE_CASE)
            }.getOrNull()
            if (regex != null) {
                candidates
                    .filter { candidate ->
                        prioritized.none { it.preparationKey() == candidate.preparationKey() } &&
                            regex.containsMatchIn(candidate.searchableText())
                    }
                    .forEach(prioritized::add)
            }
        }

        candidates
            .filter { candidate -> prioritized.none { it.preparationKey() == candidate.preparationKey() } }
            .forEach(prioritized::add)

        return prioritized.take(limit)
    }

    fun replacePreparedStream(
        groups: List<AddonStreams>,
        original: Stream,
        prepared: Stream
    ): List<AddonStreams> {
        val key = original.preparationKey()
        return groups.map { group ->
            var changed = false
            val updatedStreams = group.streams.map { stream ->
                if (stream.preparationKey() == key) {
                    changed = true
                    prepared.copy(
                        addonName = stream.addonName,
                        addonLogo = stream.addonLogo
                    )
                } else {
                    stream
                }
            }
            if (changed) group.copy(streams = updatedStreams) else group
        }
    }

    private suspend fun consumeBackgroundBudget(): Boolean {
        val now = System.currentTimeMillis()
        return budgetMutex.withLock {
            minuteStarts.removeOlderThan(now - BACKGROUND_PREPARES_PER_MINUTE_WINDOW_MS)
            hourStarts.removeOlderThan(now - BACKGROUND_PREPARES_PER_HOUR_WINDOW_MS)
            if (
                minuteStarts.size >= MAX_BACKGROUND_PREPARES_PER_MINUTE ||
                hourStarts.size >= MAX_BACKGROUND_PREPARES_PER_HOUR
            ) {
                false
            } else {
                minuteStarts.addLast(now)
                hourStarts.addLast(now)
                true
            }
        }
    }
}

private const val MAX_BACKGROUND_PREPARES_PER_MINUTE = 6
private const val MAX_BACKGROUND_PREPARES_PER_HOUR = 30
private const val BACKGROUND_PREPARES_PER_MINUTE_WINDOW_MS = 60L * 1000L
private const val BACKGROUND_PREPARES_PER_HOUR_WINDOW_MS = 60L * 60L * 1000L

private fun ArrayDeque<Long>.removeOlderThan(cutoffMs: Long) {
    while (firstOrNull()?.let { it < cutoffMs } == true) {
        removeFirst()
    }
}

private fun Stream.preparationKey(): String {
    val resolve = clientResolve
    if (resolve != null) {
        return listOf(
            resolve.service.orEmpty().lowercase(),
            resolve.infoHash.orEmpty().lowercase(),
            resolve.fileIdx?.toString().orEmpty(),
            resolve.filename.orEmpty().lowercase(),
            resolve.torrentName.orEmpty().lowercase(),
            resolve.magnetUri.orEmpty().lowercase()
        ).joinToString("|")
    }

    return listOf(
        addonName.lowercase(),
        infoHash.orEmpty().lowercase(),
        torrentMagnetUri().orEmpty().lowercase(),
        fileIdx?.toString().orEmpty(),
        getStreamUrl().orEmpty().lowercase(),
        name.orEmpty().lowercase(),
        title.orEmpty().lowercase()
    ).joinToString("|")
}

private fun Stream.searchableText(): String =
    buildString {
        append(addonName).append(' ')
        append(name.orEmpty()).append(' ')
        append(title.orEmpty()).append(' ')
        append(description.orEmpty()).append(' ')
        append(getStreamUrl().orEmpty())
    }

private fun Stream.isCachedLocalDebridTorrent(): Boolean =
    needsLocalDebridResolve() && debridCacheStatus?.state == StreamDebridCacheState.CACHED
