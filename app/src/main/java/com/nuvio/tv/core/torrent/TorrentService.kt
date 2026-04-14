package com.nuvio.tv.core.torrent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "webm", "ts", "m4v", "mov", "wmv", "flv")

@Singleton
class TorrentService @Inject constructor(
    private val binary: TorrServerBinary,
    private val api: TorrServerApi
) {
    companion object {
        private const val TAG = "TorrentService"
        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TorrentState>(TorrentState.Idle)
    val state: StateFlow<TorrentState> = _state.asStateFlow()

    private var statsJob: Job? = null
    private var currentHash: String? = null

    /**
     * Starts streaming a torrent. Returns the local HTTP URL for ExoPlayer.
     */
    suspend fun startStream(
        infoHash: String,
        fileIdx: Int?,
        filename: String? = null,
        trackers: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        stopStream()
        _state.value = TorrentState.Connecting

        // Ensure binary is running
        binary.start()

        val magnetLink = buildMagnetUri(infoHash, trackers)
        Log.d(TAG, "Starting stream: $magnetLink")

        // Add torrent
        val hash = api.addTorrent(magnetLink)
            ?: throw TorrentException("Failed to add torrent")
        currentHash = hash

        // Resolve file index
        val resolvedIdx = resolveFileIndex(hash, fileIdx, filename)

        // Get stream URL — TorrServer handles all buffering/piece management
        val streamUrl = api.getStreamUrl(magnetLink, resolvedIdx)
        Log.d(TAG, "Stream URL: $streamUrl")

        // Start stats polling
        startStatsPolling(hash)

        _state.value = TorrentState.Streaming(
            localUrl = streamUrl,
            downloadSpeed = 0,
            uploadSpeed = 0,
            peers = 0,
            seeds = 0,
            bufferProgress = 0f,
            totalProgress = 0f
        )

        streamUrl
    }

    fun stopStream() {
        statsJob?.cancel()
        statsJob = null

        currentHash?.let { hash ->
            try {
                runBlocking(Dispatchers.IO) {
                    api.dropTorrent(hash)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error dropping torrent", e)
            }
        }
        currentHash = null
        _state.value = TorrentState.Idle
    }

    fun shutdown() {
        stopStream()
        binary.stop()
    }

    private fun buildMagnetUri(infoHash: String, extraTrackers: List<String>): String {
        val trackers = (DEFAULT_TRACKERS + extraTrackers).distinct()
        val trackerParams = trackers.joinToString("") { "&tr=$it" }
        return "magnet:?xt=urn:btih:$infoHash$trackerParams"
    }

    private suspend fun resolveFileIndex(hash: String, requestedIdx: Int?, filename: String?): Int {
        // Poll for metadata — magnet links may not have it immediately
        val deadline = System.currentTimeMillis() + 15_000L
        var files: List<TorrServerFile> = emptyList()

        while (System.currentTimeMillis() < deadline) {
            files = api.getTorrentStats(hash)?.files ?: emptyList()
            if (files.isNotEmpty()) break
            Log.d(TAG, "Waiting for torrent metadata...")
            delay(1_000L)
        }

        if (files.isEmpty()) {
            Log.w(TAG, "No files after metadata timeout, guessing index ${requestedIdx?.plus(1) ?: 1}")
            return requestedIdx?.plus(1) ?: 1
        }

        Log.d(TAG, "Torrent has ${files.size} files")

        // Strategy 1: Match by filename (most reliable for season packs)
        if (!filename.isNullOrBlank()) {
            val name = filename.trim()
            // Exact basename match
            val exact = files.firstOrNull { f ->
                f.path.substringAfterLast('/').equals(name, ignoreCase = true)
            }
            if (exact != null) {
                Log.d(TAG, "File resolved by exact filename match: ${exact.path} -> id=${exact.id}")
                return exact.id
            }
            // Contains match (addon filename may be substring of full path)
            val contains = files.firstOrNull { f ->
                f.path.contains(name, ignoreCase = true)
            }
            if (contains != null) {
                Log.d(TAG, "File resolved by filename contains match: ${contains.path} -> id=${contains.id}")
                return contains.id
            }
        }

        // Strategy 2: Match by ID offset (requestedIdx + 1)
        if (requestedIdx != null) {
            val tsIdx = requestedIdx + 1
            if (files.any { it.id == tsIdx }) {
                Log.d(TAG, "File resolved by ID offset: id=$tsIdx")
                return tsIdx
            }
        }

        // Strategy 3: Positional index (handles TorrServer alphabetical sort mismatch)
        if (requestedIdx != null && requestedIdx in files.indices) {
            val positionalFile = files[requestedIdx]
            Log.d(TAG, "File resolved by positional index: [$requestedIdx] -> ${positionalFile.path} (id=${positionalFile.id})")
            return positionalFile.id
        }

        // Strategy 4: Fallback to largest video file
        val videoFile = files
            .filter { f ->
                val ext = f.path.substringAfterLast('.', "").lowercase()
                ext in VIDEO_EXTENSIONS
            }
            .maxByOrNull { it.length }

        val result = videoFile?.id ?: files.maxByOrNull { it.length }?.id ?: 1
        Log.d(TAG, "File resolved by largest video fallback: id=$result")
        return result
    }

    private fun startStatsPolling(hash: String) {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                try {
                    val stats = api.getTorrentStats(hash)
                    val currentState = _state.value
                    if (stats != null && currentState is TorrentState.Streaming) {
                        _state.value = currentState.copy(
                            downloadSpeed = stats.downloadSpeed,
                            uploadSpeed = stats.uploadSpeed,
                            peers = stats.peers,
                            seeds = stats.seeds,
                            preloadedBytes = stats.preloadedBytes
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Stats polling error", e)
                }
                delay(1000)
            }
        }
    }
}
