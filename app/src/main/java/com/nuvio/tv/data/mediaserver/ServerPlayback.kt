package com.nuvio.tv.data.mediaserver

import android.os.SystemClock
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class ServerPlayback internal constructor(
    private val repository: ServerRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long
) {
    @Inject
    constructor(repository: ServerRepository) : this(
        repository = repository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = SystemClock::elapsedRealtime
    )

    private val lock = Any()
    private val active = mutableMapOf<String, ActivePlayback>()

    suspend fun prepare(target: ServerPlaybackTarget): ServerPlaybackSession {
        val (provider, session, playback) = repository.call(target.item.connectionId) { provider, session ->
            Triple(provider, session, provider.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities())))
        }
        val orphans = synchronized(lock) {
            val unstarted = active.filterValues { !it.started }.keys.toList()
            active[playback.url] = ActivePlayback(provider, session, playback)
            unstarted.mapNotNull(active::remove)
        }
        orphans.forEach { it.stop() }
        return playback
    }

    fun canFallback(url: String?): Boolean =
        url?.let { synchronized(lock) { active[it] } }?.playback?.playMethod == ServerPlayMethod.DIRECT_PLAY

    suspend fun fallback(url: String?): ServerPlaybackSession? {
        val failed = url?.let { synchronized(lock) { active[it] } } ?: return null
        if (failed.playback.playMethod != ServerPlayMethod.DIRECT_PLAY) return null
        return restart(url, failed, audioStreamIndex = null, subtitleStreamIndex = null)
    }

    suspend fun switchAudio(url: String?, audioStreamIndex: Int): ServerPlaybackSession? {
        val current = url?.let { synchronized(lock) { active[it] } } ?: return null
        if (current.playback.playMethod == ServerPlayMethod.DIRECT_PLAY) return null
        return restart(url, current, audioStreamIndex, current.playback.burnInSubtitles.selectedIndex())
    }

    suspend fun switchSubtitle(url: String?, subtitleStreamIndex: Int?): ServerPlaybackSession? {
        val current = url?.let { synchronized(lock) { active[it] } } ?: return null
        if (current.playback.playMethod == ServerPlayMethod.DIRECT_PLAY) return null
        return restart(url, current, current.playback.audioTracks.selectedIndex(), subtitleStreamIndex)
    }

    fun audioTracks(url: String?): List<ServerTrack> {
        val playback = session(url) ?: return emptyList()
        if (playback.playMethod == ServerPlayMethod.DIRECT_PLAY || playback.audioTracks.size < 2) return emptyList()
        return playback.audioTracks
    }

    fun burnInSubtitles(url: String?): List<ServerTrack> {
        val playback = session(url) ?: return emptyList()
        if (playback.playMethod == ServerPlayMethod.DIRECT_PLAY) return emptyList()
        return playback.burnInSubtitles
    }

    fun session(url: String?): ServerPlaybackSession? = url?.let { synchronized(lock) { active[it] } }?.playback

    suspend fun resumeState(url: String?): ServerUserState? {
        val current = url?.let { synchronized(lock) { active[it] } } ?: return null
        return current.provider.details(current.session, current.playback.target.item.itemId).userStates.firstOrNull()
    }

    fun isServerSource(url: String?): Boolean = url != null && synchronized(lock) { url in active }

    fun onPlaybackSnapshot(url: String?, positionMs: Long, isPlaying: Boolean, isLoading: Boolean, isEnded: Boolean) {
        val playback = url?.let { synchronized(lock) { active[it] } } ?: return
        if (isEnded) {
            playback.lastPositionMs = positionMs
            stop(url)
            return
        }
        playback.onSnapshot(positionMs, isPlaying, isLoading)
    }

    fun stop(url: String?) {
        val playback = url?.let { synchronized(lock) { active.remove(it) } } ?: return
        playback.stop()
    }

    private suspend fun restart(
        url: String,
        current: ActivePlayback,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?
    ): ServerPlaybackSession? {
        val request = ServerPlaybackRequest(
            target = current.playback.target,
            capabilities = ServerPlayerCapabilities(allowDirectPlay = false),
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex
        )
        val playback = try {
            current.provider.preparePlayback(current.session, request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Playback restart for ${current.label} failed: ${error.serverFailure()} (${error.message})")
            return null
        }
        stop(url)
        synchronized(lock) { active[playback.url] = ActivePlayback(current.provider, current.session, playback) }
        return playback
    }

    private fun List<ServerTrack>.selectedIndex(): Int? = firstOrNull { it.selected }?.index

    private inner class ActivePlayback(
        val provider: ServerProvider,
        val session: ServerSession,
        val playback: ServerPlaybackSession
    ) {
        val label = "${provider.id} item ${playback.target.item.itemId}"
        private val events = Channel<ServerPlaybackEvent>(Channel.UNLIMITED)
        var started = false
            private set
        var lastPositionMs = 0L
        private var paused = false
        private var lastReportPositionMs = 0L
        private var lastReportAtMs = 0L

        init {
            Log.i(TAG, "Prepared $label as ${playback.playMethod} ${playback.transcodeReasons}")
            scope.launch {
                for (event in events) {
                    try {
                        val sent = withTimeoutOrNull(REPORT_TIMEOUT_MS) { provider.report(session, playback, event) }
                        when {
                            sent == null -> Log.w(TAG, "Playback report ${event.type} for $label timed out")
                            event.type != ServerPlaybackEventType.PROGRESS -> {
                                Log.i(TAG, "Playback report ${event.type} for $label sent at ${event.positionMs}ms")
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Playback report ${event.type} for $label failed: ${error.serverFailure()} (${error.message})")
                    }
                }
            }
        }

        fun onSnapshot(positionMs: Long, isPlaying: Boolean, isLoading: Boolean) = synchronized(lock) {
            lastPositionMs = positionMs
            if (!started) {
                if (isPlaying && !isLoading) {
                    started = true
                    send(ServerPlaybackEventType.START, positionMs, isPaused = false)
                }
                return@synchronized
            }
            if (isLoading) return@synchronized
            val now = clock()
            val elapsed = now - lastReportAtMs
            val expected = lastReportPositionMs + if (paused) 0L else elapsed
            when {
                paused == isPlaying -> {
                    paused = !isPlaying
                    send(if (paused) ServerPlaybackEventType.PAUSE else ServerPlaybackEventType.RESUME, positionMs, paused)
                }
                abs(positionMs - expected) > SEEK_THRESHOLD_MS ||
                    (isPlaying && elapsed >= PROGRESS_INTERVAL_MS) -> {
                    send(ServerPlaybackEventType.PROGRESS, positionMs, paused)
                }
            }
        }

        fun stop() = synchronized(lock) {
            send(ServerPlaybackEventType.STOP, lastPositionMs, isPaused = true)
            events.close()
        }

        private fun send(type: ServerPlaybackEventType, positionMs: Long, isPaused: Boolean) {
            lastReportAtMs = clock()
            lastReportPositionMs = positionMs
            events.trySend(ServerPlaybackEvent(type, positionMs, isPaused))
        }
    }

    private companion object {
        const val TAG = "ServerPlayback"
        const val PROGRESS_INTERVAL_MS = 10_000L
        const val SEEK_THRESHOLD_MS = 5_000L
        const val REPORT_TIMEOUT_MS = 10_000L
    }
}
