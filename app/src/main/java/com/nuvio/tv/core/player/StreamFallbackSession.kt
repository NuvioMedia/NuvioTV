package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A finite Usenet-only snapshot of the displayed order. Never wraps or retries a failed source. */
internal class StreamFallbackSession(
    selected: Stream,
    orderedStreams: List<Stream>,
    private val maxAttempts: Int = 5,
    private val isEnabled: () -> Boolean = { false }
) {
    private val selectedIsUsenet = selected.isUsenet()
    private var playbackUrl: String? = null
    val enabled: Boolean get() = selectedIsUsenet && isEnabled()
    private val attempted = mutableSetOf<StreamFallbackKey>()
    private val remaining: ArrayDeque<Stream>
    var attempts: Int = 0
        private set
    var current: Stream = selected
        private set
    val canAdvance: Boolean
        get() = enabled && attempts < maxAttempts.coerceIn(0, 10) &&
            remaining.any { it.canAutoFallback() && it.fallbackKey() !in attempted }

    init {
        val key = selected.fallbackKey()
        val index = orderedStreams.indexOf(selected).takeIf { it >= 0 }
            ?: orderedStreams.indexOfFirst { it.fallbackKey() == key }
        remaining = ArrayDeque(orderedStreams.drop(if (index < 0) 0 else index + 1))
        attempted += key
    }

    fun next(): Stream? {
        if (!enabled) return null
        while (attempts < maxAttempts.coerceIn(0, 10) && remaining.isNotEmpty()) {
            val candidate = remaining.removeFirst()
            if (!candidate.canAutoFallback() || !attempted.add(candidate.fallbackKey())) continue
            attempts++
            current = candidate
            return candidate
        }
        return null
    }

    fun resolved(stream: Stream) {
        attempted += stream.fallbackKey()
        playbackUrl = stream.getStreamUrl()
    }

    /** A normal torrent/HTTP failure must never reuse an earlier Usenet queue. */
    fun ownsPlayback(url: String): Boolean =
        selectedIsUsenet && url.isNotBlank() && url == playbackUrl

    fun canFallbackFrom(url: String): Boolean =
        ownsPlayback(url) && canAdvance

    /** Cancellation is navigation/user intent, never a reason to try another source. */
    suspend fun resolveNext(resolve: suspend (Stream, Int) -> Stream): Stream? {
        while (true) {
            coroutineContext.ensureActive()
            val candidate = next() ?: return null
            try {
                val result = resolve(candidate, attempts)
                coroutineContext.ensureActive()
                if (!enabled) return null
                resolved(result)
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Continue with the next distinct candidate within the attempt budget.
            }
        }
    }
}

private fun Stream.canAutoFallback(): Boolean =
    isUsenet() && !isExternal() && !isYouTube()

/** Presentation (addon name, badges, title) must not turn a duplicate into a new attempt. */
private data class StreamFallbackKey(
    val source: String,
    val fileIndex: Int? = null,
    val selector: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val servers: List<String> = emptyList(),
    val provider: String? = null
)

private fun Stream.fallbackKey(): StreamFallbackKey = when {
    !nzbUrl.isNullOrBlank() -> StreamFallbackKey(
        "nzb:$nzbUrl", getEffectiveFileIdx(), fileMustInclude,
        headers = behaviorHints?.proxyHeaders?.request.orEmpty(),
        servers = servers.orEmpty().sorted()
    )
    !getStreamUrl().isNullOrBlank() -> StreamFallbackKey(
        getStreamUrl().orEmpty(), headers = behaviorHints?.proxyHeaders?.request.orEmpty()
    )
    else -> StreamFallbackKey(
        "torrent:${getEffectiveInfoHash()?.lowercase() ?: torrentMagnetUri().orEmpty()}",
        getEffectiveFileIdx(), clientResolve?.filename ?: behaviorHints?.filename,
        provider = clientResolve?.service
    )
}

/** Small, one-shot navigation handoff, scoped to both content and profile. */
internal object StreamFallbackHandoff {
    private data class Key(val contentKey: String, val profileId: Int?, val url: String?)
    private val sessions = LinkedHashMap<Key, StreamFallbackSession>()

    @Synchronized
    fun put(contentKey: String, profileId: Int?, url: String?, session: StreamFallbackSession) {
        sessions[Key(contentKey, profileId, url)] = session
        while (sessions.size > 8) sessions.remove(sessions.keys.first())
    }

    @Synchronized
    fun take(contentKey: String, profileId: Int?, url: String?): StreamFallbackSession? =
        sessions.remove(Key(contentKey, profileId, url))
}
