package com.nuvio.tv.core.torrent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEventType
import com.nuvio.engine.NuvioStream
import com.nuvio.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class TorrentService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val torrentSettings: TorrentSettings
) {
    companion object {
        private const val TAG = "TorrentService"
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val STATS_INTERVAL_MS = 250L
    }

    private data class EngineConfigurationKey(
        val uploadEnabled: Boolean,
        val torrentProfile: TorrentProfile,
        val diskCacheCapacityBytes: Long
    )

    private data class DetachedStream(
        val engine: NuvioEngine?,
        val streamId: String?
    )

    private val _state = MutableStateFlow<TorrentState>(TorrentState.Idle)
    val state: StateFlow<TorrentState> = _state.asStateFlow()
    private val _cacheState = MutableStateFlow(TorrentCacheState())
    val cacheState: StateFlow<TorrentCacheState> = _cacheState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val startMutex = Mutex()
    private var statsJob: Job? = null
    private var cleanupJob: Job? = null
    private var engineEventsJob: Job? = null
    private var streamGeneration = 0L
    @Volatile
    private var currentTorrentId: String? = null
    @Volatile
    private var currentStreamId: String? = null
    @Volatile
    private var engine: NuvioEngine? = null
    private var engineConfigurationKey: EngineConfigurationKey? = null
    private val knownTorrentIds = mutableSetOf<String>()

    suspend fun startStream(
        infoHash: String,
        fileIdx: Int?,
        filename: String? = null,
        trackers: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        startMutex.withLock { startStreamLocked(infoHash, fileIdx, filename, trackers) }
    }

    suspend fun clearCache(): TorrentCacheClearResult = withContext(Dispatchers.IO) {
        startMutex.withLock {
            check(_state.value !is TorrentState.Streaming && _state.value !is TorrentState.Connecting) {
                "Torrent cache cannot be cleared during active playback"
            }
            _cacheState.value = _cacheState.value.copy(isClearing = true)
            try {
                val activeEngine = ensureEngine()
                val before = activeEngine.stats.value
                activeEngine.reclaimDiskCache(0L)
                delay(SAMPLE_INTERVAL_MS + 100L)
                val after = activeEngine.stats.value
                updateCacheState(after.diskCacheUsedBytes, after.diskCacheProtectedBytes)
                TorrentCacheClearResult(
                    reclaimedBytes = (after.diskCacheReclaimedBytes - before.diskCacheReclaimedBytes)
                        .coerceAtLeast(0L),
                    remainingBytes = after.diskCacheUsedBytes,
                    protectedBytes = after.diskCacheProtectedBytes
                )
            } finally {
                _cacheState.value = _cacheState.value.copy(isClearing = false)
            }
        }
    }

    fun stopStream() {
        scheduleStop(shutdownEngine = false)
    }

    fun shutdown() {
        scheduleStop(shutdownEngine = true)
    }

    private suspend fun startStreamLocked(
        infoHash: String,
        fileIdx: Int?,
        filename: String?,
        trackers: List<String>
    ): String {
        val startedAtMs = SystemClock.elapsedRealtime()
        val phase = AtomicReference("stop_previous")
        Log.i(
            TorrentDiagnosticTag,
            "start phase=accepted hash=${diagnosticId(infoHash)} fileIndex=${fileIdx ?: -1} " +
                "filenameHint=${!filename.isNullOrBlank()} requestTrackers=${trackers.size}"
        )
        stopStreamNow(shutdownEngine = false)
        val generation = beginStreamGeneration()

        var activeEngine: NuvioEngine? = null
        var preparedStream: NuvioStream? = null
        var attached = false
        var startupStatsJob: Job? = null
        return try {
            phase.set("ensure_engine")
            val magnetUri = buildMagnetUri(infoHash, (DefaultTorrentTrackers + trackers).distinct())
            val resolvedEngine = ensureEngine()
            activeEngine = resolvedEngine
            val payloadDownloadBaseline = resolvedEngine.stats.value.totalPayloadDownloadBytes
            ensureCurrentGeneration(generation)
            startupStatsJob = startStartupStatsPolling(resolvedEngine, generation, phase)

            phase.set("add_magnet")
            val canonicalHash = canonicalInfoHash(infoHash)
            val reusedTorrent = canonicalHash in knownTorrentIds
            val torrentId = if (reusedTorrent) {
                canonicalHash
            } else {
                resolvedEngine.addMagnet(magnetUri).also { knownTorrentIds += it }
            }
            ensureCurrentGeneration(generation)
            logPhase(startedAtMs, phase.get(), "torrent=${diagnosticId(torrentId)} cached=$reusedTorrent")

            phase.set("prepare_stream")
            val stream = resolvedEngine.prepareStream(
                torrentId = torrentId,
                fileIndex = fileIdx,
                filenameHint = filename
            )
            preparedStream = stream
            logPhase(
                startedAtMs,
                phase.get(),
                "stream=${diagnosticId(stream.id)} selectedFile=${stream.fileIndex} fileBytes=${stream.fileSize}"
            )
            currentCoroutineContext().ensureActive()

            phase.set("attach_route")
            if (!attachStreamIfCurrent(generation, torrentId, stream.id)) {
                withContext(NonCancellable) { stopPreparedStream(resolvedEngine, stream.id) }
                preparedStream = null
                throw CancellationException("Torrent stream start was cancelled")
            }
            attached = true

            startStatsPolling(resolvedEngine, stream, generation, payloadDownloadBaseline)
            val initial = resolvedEngine.stats.value
            val published = publishStreamingIfCurrent(
                generation = generation,
                state = TorrentState.Streaming(
                    localUrl = stream.url,
                    downloadSpeed = initial.downloadRateBytesPerSecond,
                    uploadSpeed = initial.uploadRateBytesPerSecond,
                    peers = initial.connectedPeers,
                    seeds = initial.connectedSeeds,
                    bufferProgress = 0f,
                    totalProgress = 0f,
                    downloadedBytes = (initial.totalPayloadDownloadBytes - payloadDownloadBaseline)
                        .coerceAtLeast(0L)
                )
            )
            if (!published) {
                throw CancellationException("Torrent stream start was cancelled")
            }
            logPhase(startedAtMs, "route_ready", "generation=$generation")
            stream.url
        } catch (cancellation: CancellationException) {
            Log.w(TorrentDiagnosticTag, "start phase=${phase.get()} cancelled elapsedMs=${elapsedSince(startedAtMs)}")
            withContext(NonCancellable) {
                cleanupFailedStart(generation, activeEngine, preparedStream, attached, TorrentState.Idle)
            }
            throw cancellation
        } catch (error: Exception) {
            Log.e(
                TorrentDiagnosticTag,
                "start phase=${phase.get()} failed elapsedMs=${elapsedSince(startedAtMs)} " +
                    "error=${diagnosticMessage(error.message)}",
                error
            )
            val terminalState = TorrentState.Error(error.message ?: unknownErrorMessage())
            withContext(NonCancellable) {
                cleanupFailedStart(generation, activeEngine, preparedStream, attached, terminalState)
            }
            throw error
        } finally {
            startupStatsJob?.cancel()
        }
    }

    private fun scheduleStop(shutdownEngine: Boolean) {
        val detached = detachActiveStream()
        val previousCleanup = cleanupJob
        cleanupJob = scope.launch {
            previousCleanup?.join()
            cleanupDetachedStream(detached, shutdownEngine)
        }
    }

    private suspend fun stopStreamNow(shutdownEngine: Boolean) {
        cleanupJob?.join()
        cleanupDetachedStream(detachActiveStream(), shutdownEngine)
    }

    private fun detachActiveStream(): DetachedStream {
        val detached: Pair<DetachedStream, Job?> = synchronized(lifecycleLock) {
            streamGeneration += 1
            val value = DetachedStream(engine = engine, streamId = currentStreamId)
            val job = statsJob
            currentTorrentId = null
            currentStreamId = null
            statsJob = null
            _state.value = TorrentState.Idle
            value to job
        }
        detached.second?.cancel()
        return detached.first
    }

    private fun detachGenerationIfCurrent(
        generation: Long,
        terminalState: TorrentState
    ): DetachedStream? {
        val detached: Pair<DetachedStream, Job?>? = synchronized(lifecycleLock) {
            if (streamGeneration != generation) return@synchronized null
            streamGeneration += 1
            val value = DetachedStream(engine = engine, streamId = currentStreamId)
            val job = statsJob
            currentTorrentId = null
            currentStreamId = null
            statsJob = null
            _state.value = terminalState
            value to job
        }
        detached?.second?.cancel()
        return detached?.first
    }

    private suspend fun cleanupDetachedStream(detached: DetachedStream, shutdownEngine: Boolean) {
        detached.streamId?.let { streamId -> stopPreparedStream(detached.engine, streamId) }
        if (shutdownEngine) {
            closeEngine(detached.engine)
        }
    }

    private suspend fun cleanupFailedStart(
        generation: Long,
        activeEngine: NuvioEngine?,
        preparedStream: NuvioStream?,
        attached: Boolean,
        terminalState: TorrentState
    ) {
        if (attached) {
            detachGenerationIfCurrent(generation, terminalState)?.let { detached ->
                cleanupDetachedStream(detached, shutdownEngine = false)
            }
        } else {
            preparedStream?.let { stream -> stopPreparedStream(activeEngine, stream.id) }
            detachGenerationIfCurrent(generation, terminalState)
        }
    }

    private suspend fun stopPreparedStream(activeEngine: NuvioEngine?, streamId: String) {
        try {
            activeEngine?.stopStream(streamId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Error stopping Nuvio Engine stream route", error)
        }
    }

    private suspend fun ensureEngine(): NuvioEngine {
        val settings = torrentSettings.settings.first()
        val configurationKey = EngineConfigurationKey(
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
            diskCacheCapacityBytes = settings.cacheSize.bytes
        )
        engine?.takeIf { engineConfigurationKey == configurationKey }?.let { return it }

        closeEngine(engine)
        currentCoroutineContext().ensureActive()
        val stateDirectory = File(context.noBackupFilesDir, "nuvio-engine/state")
        val cacheDirectory = File(context.cacheDir, "nuvio-engine")
        check(stateDirectory.mkdirs() || stateDirectory.isDirectory) {
            "Could not create the Nuvio Engine state directory"
        }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Could not create the Nuvio Engine cache directory"
        }
        migrateNestedPayloadDirectory(cacheDirectory)
        return NuvioEngine.create(
            buildTorrentEngineConfig(
                stateDirectory = stateDirectory,
                cacheDirectory = cacheDirectory,
                uploadEnabled = configurationKey.uploadEnabled,
                torrentProfile = configurationKey.torrentProfile,
                diskCacheCapacityBytes = configurationKey.diskCacheCapacityBytes
            )
        ).also { created ->
            engine = created
            engineConfigurationKey = configurationKey
            observeEngineEvents(created)
            Log.i(TAG, "Using Nuvio Engine ${NuvioEngine.version} (${NuvioEngine.protocolBackendVersion})")
        }
    }

    private suspend fun closeEngine(target: NuvioEngine?) {
        if (target == null) return
        if (engine === target) {
            engineEventsJob?.cancel()
            engineEventsJob = null
            engine = null
            engineConfigurationKey = null
            knownTorrentIds.clear()
        }
        withContext(NonCancellable) {
            try {
                target.shutdown()
            } catch (error: Exception) {
                Log.w(TAG, "Error shutting down Nuvio Engine", error)
            }
        }
    }

    private fun observeEngineEvents(activeEngine: NuvioEngine) {
        engineEventsJob?.cancel()
        engineEventsJob = scope.launch {
            launch {
                activeEngine.stats.collect { stats ->
                    if (engine === activeEngine) {
                        updateCacheState(stats.diskCacheUsedBytes, stats.diskCacheProtectedBytes)
                    }
                }
            }
            activeEngine.events.collect { event ->
                Log.i(
                    TorrentDiagnosticTag,
                    "event type=${event.type} requestId=${event.requestId} torrent=${diagnosticId(event.torrentId)} " +
                        "stream=${diagnosticId(event.streamId)} message=${diagnosticMessage(event.message)}"
                )
                if (engine !== activeEngine) return@collect
                when (event.type) {
                    NuvioEventType.TorrentError -> synchronized(lifecycleLock) {
                        if (engine !== activeEngine) return@synchronized
                        val error = unexpectedTorrentError(
                            requestId = event.requestId,
                            eventTorrentId = event.torrentId,
                            currentTorrentId = currentTorrentId,
                            message = event.message,
                            fallbackMessage = unknownErrorMessage()
                        ) ?: return@synchronized
                        streamGeneration += 1
                        statsJob?.cancel()
                        statsJob = null
                        _state.value = error
                    }
                    NuvioEventType.StreamStopped -> synchronized(lifecycleLock) {
                        if (engine !== activeEngine) return@synchronized
                        val error = unexpectedStreamStopError(
                            requestId = event.requestId,
                            eventStreamId = event.streamId,
                            currentStreamId = currentStreamId,
                            message = event.message,
                            fallbackMessage = unknownErrorMessage()
                        ) ?: return@synchronized
                        streamGeneration += 1
                        currentTorrentId = null
                        currentStreamId = null
                        statsJob?.cancel()
                        statsJob = null
                        _state.value = error
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startStatsPolling(
        activeEngine: NuvioEngine,
        stream: NuvioStream,
        generation: Long,
        payloadDownloadBaseline: Long
    ) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var nextSampleAtMs = 0L
            while (isActive) {
                if (!isCurrentGeneration(generation)) return@launch
                if (_state.value is TorrentState.Streaming) {
                    val route = try {
                        activeEngine.currentStreamStats(stream.id)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        Log.w(TAG, "Error sampling Nuvio Engine stream progress", error)
                        null
                    }
                    val aggregate = activeEngine.stats.value
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs >= nextSampleAtMs) {
                        nextSampleAtMs = nowMs + SAMPLE_INTERVAL_MS
                        Log.i(
                            TorrentDiagnosticTag,
                            "sample stream=${diagnosticId(stream.id)} ${aggregate.diagnosticSummary()} " +
                                route.diagnosticSummary()
                        )
                    }
                    updateStreamingIfCurrent(generation) { latest ->
                        latest.copy(
                            downloadSpeed = aggregate.downloadRateBytesPerSecond,
                            uploadSpeed = aggregate.uploadRateBytesPerSecond,
                            peers = aggregate.connectedPeers,
                            seeds = aggregate.connectedSeeds,
                            bufferProgress = route?.bufferProgress ?: latest.bufferProgress,
                            totalProgress = route?.fileProgress ?: latest.totalProgress,
                            downloadedBytes = (aggregate.totalPayloadDownloadBytes - payloadDownloadBaseline)
                                .coerceAtLeast(0L),
                            verifiedBytes = route?.verifiedFileBytes ?: latest.verifiedBytes,
                            deliveredBytes = route?.deliveredBytes ?: latest.deliveredBytes
                        )
                    }
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun startStartupStatsPolling(
        activeEngine: NuvioEngine,
        generation: Long,
        phase: AtomicReference<String>
    ): Job = scope.launch {
        while (isActive) {
            val aggregate = activeEngine.stats.value
            updateConnectingIfCurrent(
                generation = generation,
                state = TorrentState.Connecting(
                    phase = phase.get(),
                    downloadSpeed = aggregate.downloadRateBytesPerSecond,
                    uploadSpeed = aggregate.uploadRateBytesPerSecond,
                    peers = aggregate.connectedPeers,
                    seeds = aggregate.connectedSeeds
                )
            )
            Log.i(TorrentDiagnosticTag, "startupSample phase=${phase.get()} ${aggregate.diagnosticSummary()}")
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    private fun updateCacheState(usedBytes: Long, protectedBytes: Long) {
        _cacheState.value = _cacheState.value.copy(
            usedBytes = usedBytes,
            protectedBytes = protectedBytes,
            hasMeasurement = true
        )
    }

    private fun beginStreamGeneration(): Long = synchronized(lifecycleLock) {
        streamGeneration += 1
        _state.value = TorrentState.Connecting()
        streamGeneration
    }

    private fun updateConnectingIfCurrent(generation: Long, state: TorrentState.Connecting) =
        synchronized(lifecycleLock) {
            if (streamGeneration == generation && _state.value is TorrentState.Connecting) {
                _state.value = state
            }
        }

    private fun attachStreamIfCurrent(
        generation: Long,
        torrentId: String,
        streamId: String
    ): Boolean = synchronized(lifecycleLock) {
        if (streamGeneration != generation) return@synchronized false
        currentTorrentId = torrentId
        currentStreamId = streamId
        true
    }

    private fun publishStreamingIfCurrent(
        generation: Long,
        state: TorrentState.Streaming
    ): Boolean = synchronized(lifecycleLock) {
        if (streamGeneration != generation || currentStreamId == null) return@synchronized false
        _state.value = state
        true
    }

    private fun updateStreamingIfCurrent(
        generation: Long,
        update: (TorrentState.Streaming) -> TorrentState.Streaming
    ) = synchronized(lifecycleLock) {
        if (streamGeneration != generation) return@synchronized
        val current = _state.value as? TorrentState.Streaming ?: return@synchronized
        _state.value = update(current)
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lifecycleLock) { streamGeneration == generation }

    private fun ensureCurrentGeneration(generation: Long) {
        if (!isCurrentGeneration(generation)) {
            throw CancellationException("Torrent stream start was cancelled")
        }
    }

    private fun unknownErrorMessage(): String = context.getString(R.string.error_unknown)

    private fun logPhase(startedAtMs: Long, phase: String, detail: String) {
        Log.i(TorrentDiagnosticTag, "start phase=$phase complete elapsedMs=${elapsedSince(startedAtMs)} $detail")
    }

    private fun elapsedSince(startedAtMs: Long): Long =
        (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
}
