package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.core.usenet.UsenetPreparationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** A finite Usenet-only snapshot of the displayed order. Never wraps or retries a failed source. */
internal class StreamFallbackSession(
    selected: Stream,
    orderedStreams: List<Stream>,
    private val maxAttempts: Int = 5,
    private val nanoTime: () -> Long = System::nanoTime,
    private val isEnabled: () -> Boolean = { false },
    /** Releases that already failed for good with these providers; trying them again only costs time. */
    private val isUnplayable: (Stream) -> Boolean = { false }
) {
    private val selectedIsUsenet = selected.isUsenet()
    private var playbackUrl: String? = null
    val enabled: Boolean get() = selectedIsUsenet && isEnabled()
    private val attempted = mutableSetOf<StreamFallbackKey>()
    private val remaining: ArrayDeque<Stream>
    private var preparationSpentMs = 0L
    private var engineFailed = false
    private val failedProviders = mutableSetOf<List<String>>()
    var lastFailure: String? = null
        private set
    val failureMessage: String?
        get() = if (preparationSpentMs >= 120_000L) {
            "Usenet preparation time budget exhausted" + (lastFailure?.let { ": $it" } ?: "")
        } else lastFailure
    var attempts: Int = 0
        private set
    var current: Stream = selected
        private set
    val canAdvance: Boolean
        get() = enabled && !engineFailed && preparationSpentMs < 120_000L && attempts < maxAttempts.coerceIn(0, 50) &&
            remaining.any { eligible(it) }

    private fun eligible(stream: Stream) = stream.canAutoFallback() &&
        stream.fallbackKey() !in attempted && stream.servers.orEmpty().sorted() !in failedProviders &&
        !isUnplayable(stream)

    init {
        val key = selected.fallbackKey()
        val index = orderedStreams.indexOf(selected).takeIf { it >= 0 }
            ?: orderedStreams.indexOfFirst { it.fallbackKey() == key }
        remaining = ArrayDeque(orderedStreams.drop(if (index < 0) 0 else index + 1))
        attempted += key
    }

    fun next(): Stream? {
        if (!canAdvance) return null
        while (attempts < maxAttempts.coerceIn(0, 50) && remaining.isNotEmpty()) {
            val candidate = remaining.removeFirst()
            if (!eligible(candidate)) continue
            attempted.add(candidate.fallbackKey())
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

    private fun recordFailure(stream: Stream, error: Exception) {
        lastFailure = error.message ?: "Usenet preparation failed"
        when ((error as? UsenetPreparationException)?.scope) {
            UsenetPreparationException.Scope.ENGINE -> engineFailed = true
            UsenetPreparationException.Scope.PROVIDER -> failedProviders += stream.servers.orEmpty().sorted()
            else -> Unit
        }
    }

    private data class Prepared<T>(val value: T)

    /** Cumulative preparation time excludes time spent watching. A replacement
     * gets at most 30s; initial selection gets 60s when fallback is enabled.
     * Disabling fallback retains the original 120s single-source allowance. */
    suspend fun <T> prepareCandidate(stream: Stream, resolve: suspend () -> T): T {
        val allowance = minOf(120_000L - preparationSpentMs,
            if (!enabled) 120_000L else if (attempts == 0) 60_000L else 30_000L)
        if (allowance <= 0L) error("Usenet preparation time budget exhausted")
        val start = nanoTime()
        fun elapsed() = (nanoTime() - start) / 1_000_000L
        val label = "attempt=$attempts source=\"${stream.name}\" release=\"${stream.title?.lineSequence()?.firstOrNull()}\""
        logPreparation("start $label allowanceMs=$allowance spentMs=$preparationSpentMs")
        try {
            val prepared = withTimeoutOrNull(allowance) { Prepared(resolve()) }
                ?: throw UsenetPreparationException("Usenet source preparation timed out")
            logPreparation("ready $label ms=${elapsed()}")
            return prepared.value
        } catch (cancelled: CancellationException) {
            logPreparation("cancelled $label ms=${elapsed()}")
            throw cancelled
        } catch (error: Exception) {
            val failure = (error as? UsenetPreparationException)?.failure
            logPreparation("failed $label ms=${elapsed()} kind=${failure ?: "-"} error=${error.javaClass.simpleName}: " +
                redacted(error.message.orEmpty()))
            recordFailure(stream, error)
            throw error
        } finally {
            preparationSpentMs += ((nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
        }
    }

    /** Cancellation is navigation/user intent, never a reason to try another source. */
    suspend fun resolveNext(resolve: suspend (Stream, Int) -> Stream): Stream? {
        while (true) {
            coroutineContext.ensureActive()
            val candidate = next() ?: return null
            try {
                val result = prepareCandidate(candidate) { resolve(candidate, attempts) }
                coroutineContext.ensureActive()
                if (!enabled) return null
                resolved(result)
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure(candidate, error)
                // Continue with the next distinct candidate within the attempt budget.
            }
        }
    }
}

/** One line per Usenet source preparation, so slow or failing indexers and releases show in logcat. */
private fun logPreparation(message: String) {
    runCatching { android.util.Log.i("UsenetPrepare", message) }
}

/** Engine and network messages can carry URLs, whose queries hold API keys: keep only the host. */
internal fun redacted(message: String): String =
    message.replace(Regex("""(https?|nntps?)://(?:[^@\s/"]*@)?([^/\s"?]+)[^\s"]*"""), "$1://$2/…").take(240)

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
