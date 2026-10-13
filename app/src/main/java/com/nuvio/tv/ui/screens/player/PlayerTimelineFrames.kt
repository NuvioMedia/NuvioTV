package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import com.google.common.util.concurrent.ListenableFuture
import com.nuvio.tv.core.player.timeline.TelumiaFrameExtractor
import com.nuvio.tv.core.storage.MediaCacheCategory
import com.nuvio.tv.core.storage.tvMediaCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** Opaque lifetime identity. Never serialize URLs, headers or credentials into state/cache keys. */
internal class TimelineFrameSession(val owner: Any, val url: String, headers: Map<String, String>, val durationMs: Long) {
    val headers = headers.toMap()
    override fun toString() = "TimelineFrameSession"
}
internal data class TimelineFrameImage(val requestedMs: Long, val decodedMs: Long, val bitmap: Bitmap)
/** In-memory request only. Credential-bearing inputs never enter UI diagnostics or cache keys. */
internal data class TimelinePreviewInput(
    val owner: com.nuvio.tv.core.player.metadata.SceneBookmarkScope,
    val url: String,
    val headers: Map<String, String>,
    val durationMs: Long,
    val positionMs: Long,
) {
    override fun toString() = "TimelinePreviewInput"
}
internal sealed interface TimelineFrameState {
    data object Idle : TimelineFrameState
    data class Loading(val positionMs: Long) : TimelineFrameState
    data class Ready(val image: TimelineFrameImage, val neighbors: List<TimelineFrameImage> = emptyList(),
        val provisional: Boolean = false) : TimelineFrameState
    data class Unavailable(val positionMs: Long) : TimelineFrameState
}

/** One bounded, conflated extraction lane. It has no reference to the main ExoPlayer or seek action. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlayerTimelineFrames(
    context: Context,
    private val sourceFactory: PlayerMediaSourceFactory,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val mutable = MutableStateFlow<TimelineFrameState>(TimelineFrameState.Idle)
    val state = mutable.asStateFlow()
    private var session: TimelineFrameSession? = null
    private var extractor: TelumiaFrameExtractor? = null
    private var httpLifetime: TimelineCallCancellation? = null
    private var job: Job? = null
    private var bindingJob: Job? = null
    private var heldSeekJob: Job? = null
    private var latestPreviewInput: TimelinePreviewInput? = null
    private var filmstrip = false
    private var revision = 0L
    private var closed = false
    private val requests = Channel<Request>(Channel.CONFLATED)
    private data class Request(val session: TimelineFrameSession, val position: Long, val strip: Boolean, val revision: Long)
    private val cache = LinkedHashMap<Long, TimelineFrameImage>(16, .75f, true)
    private var cacheBytes = 0L
    // Bitmap RAM, separate from durable user data and bounded by the existing category policy.
    private val quota by lazy { tvMediaCache(context).activeBudget.let {
        (it.quota(MediaCacheCategory.THUMBNAILS) + it.quota(MediaCacheCategory.FILMSTRIP)).coerceAtMost(4L * 1024 * 1024)
    } }

    /** The existing player supplies ownership and pending seek, never a second seek action. */
    fun bind(inputs: Flow<TimelinePreviewInput?>) {
        checkMain()
        check(!closed)
        bindingJob?.cancel()
        bindingJob = scope.launch(Dispatchers.Main.immediate) {
            inputs.distinctUntilChanged().collect { input ->
                if (input == null) { hide(); return@collect }
                val current = session?.takeIf {
                    it.owner == input.owner && it.url == input.url &&
                        it.headers == input.headers && it.durationMs == input.durationMs
                } ?: TimelineFrameSession(input.owner, input.url, input.headers, input.durationMs)
                val beginning = latestPreviewInput == null || session !== current
                if (beginning) filmstrip = false
                request(current, input.positionMs, filmstrip)
                latestPreviewInput = input
                if (beginning) {
                    heldSeekJob?.cancel()
                    heldSeekJob = scope.launch(Dispatchers.Main.immediate) {
                        // Position repeats do not restart this hold timer.
                        delay(650)
                        latestPreviewInput?.takeIf { session === current }?.let { latest ->
                            filmstrip = true
                            request(current, latest.positionMs, filmstrip = true)
                        }
                    }
                }
            }
        }
    }

    fun isCurrent(input: TimelinePreviewInput?) = input != null && latestPreviewInput == input &&
        session?.let { it.owner == input.owner && it.url == input.url && it.headers == input.headers } == true

    fun request(current: TimelineFrameSession, positionMs: Long, filmstrip: Boolean = false) {
        checkMain()
        if (closed || current.durationMs <= 0 || positionMs !in 0 until current.durationMs) { hide(); return }
        if (session !== current) {
            hide()
            session = current
            job = scope.launch(Dispatchers.Main.immediate) {
                for (incoming in requests) {
                    var request = incoming
                    if (request.session !== session) continue
                    // Repeated D-pad events replace waiting work; decoding itself is bounded.
                    delay(120)
                    while (true) { request = requests.tryReceive().getOrNull() ?: break }
                    if (!current(request)) continue
                    try {
                        val center = frame(request, request.position)
                        if (!sameSession(request)) continue
                        if (!current(request)) {
                            // Continuous remote repeats may outrun decoding. Publish only a real,
                            // labelled sample from this live session; the final position still wins.
                            if (latestPreviewInput != null) mutable.value = TimelineFrameState.Ready(center,
                                if (request.strip) nearbyCached(center) else emptyList(), provisional = true)
                            continue
                        }
                        mutable.value = TimelineFrameState.Ready(center,
                            if (request.strip) nearbyCached(center) else emptyList())
                        if (request.strip) {
                            val neighbors = nearbyCached(center).toMutableList()
                            for (offset in listOf(-3000L, -1500L, 1500L, 3000L)) {
                                val position = request.position + offset
                                if (!current(request)) break
                                if (position !in 0 until request.session.durationMs) continue
                                val candidate = try { frame(request, position) }
                                catch (cancelled: CancellationException) {
                                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw cancelled
                                    discardExtractor(); break
                                } catch (_: Exception) { discardExtractor(); break }
                                if (candidate.decodedMs != center.decodedMs && neighbors.none { it.decodedMs == candidate.decodedMs }) neighbors += candidate
                            }
                            if (current(request)) mutable.value = TimelineFrameState.Ready(center,
                                neighbors.distinctBy { it.decodedMs }.sortedBy { abs(it.decodedMs - center.decodedMs) }
                                    .take(4).sortedBy { it.decodedMs })
                        }
                    } catch (cancelled: CancellationException) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw cancelled
                        discardExtractor()
                        if (current(request)) mutable.value = TimelineFrameState.Unavailable(request.position)
                    } catch (_: Exception) {
                        discardExtractor()
                        if (current(request)) mutable.value = TimelineFrameState.Unavailable(request.position)
                    }
                }
            }
        }
        val token = ++revision
        val ready = mutable.value as? TimelineFrameState.Ready
        if (ready?.image?.requestedMs != positionMs) mutable.value = TimelineFrameState.Loading(positionMs)
        requests.trySend(Request(current, positionMs, filmstrip, token))
    }

    private fun current(request: Request) = !closed && session === request.session && revision == request.revision
    private fun sameSession(request: Request) = !closed && session === request.session
    private fun nearbyCached(center: TimelineFrameImage) = cache.values
        .filter { it.decodedMs != center.decodedMs && abs(it.decodedMs - center.decodedMs) <= 60000 }
        .distinctBy { it.decodedMs }.sortedBy { abs(it.decodedMs - center.decodedMs) }.take(4)
        .sortedBy { it.decodedMs }
    private suspend fun frame(request: Request, position: Long): TimelineFrameImage {
        val bucket = position / 250
        cache[bucket]?.takeIf { abs(it.decodedMs - position) <= 1250 }?.let { return it.copy(requestedMs = position) }
        val reader = extractor ?: TimelineCallCancellation().let { lifetime ->
            httpLifetime = lifetime
            TelumiaFrameExtractor(context, TelumiaFrameExtractor.Configuration.Builder().build(),
                sourceFactory.timelineMediaSourceFactory(request.session.url, request.session.headers, lifetime))
        }.also {
            extractor = it
            it.setMediaItem(MediaItem.fromUri(request.session.url), listOf(
                Presentation.createForWidthAndHeight(320, 180, Presentation.LAYOUT_SCALE_TO_FIT)))
        }
        val decoded = withTimeout(6000) { reader.getFrame(position).awaitFrame() }
        check(decoded.presentationTimeMs in 0 until request.session.durationMs && abs(decoded.presentationTimeMs - position) <= 1250 &&
            decoded.bitmap.width in 1..320 && decoded.bitmap.height in 1..180) { "Unavailable preview frame" }
        val result = TimelineFrameImage(position, decoded.presentationTimeMs, decoded.bitmap)
        if (sameSession(request) && result.bitmap.allocationByteCount <= quota) {
            cache.remove(bucket)?.let { cacheBytes -= it.bitmap.allocationByteCount }
            cache[bucket] = result; cacheBytes += result.bitmap.allocationByteCount
            val entries = cache.entries.iterator()
            while (cacheBytes > quota && entries.hasNext()) { cacheBytes -= entries.next().value.bitmap.allocationByteCount; entries.remove() }
        }
        return result
    }

    /** Losing focus, changing owner/source or hiding the player clears every pending preview. */
    fun hide() {
        checkMain()
        ++revision; session = null
        heldSeekJob?.cancel(); heldSeekJob = null
        latestPreviewInput = null; filmstrip = false
        job?.cancel(); job = null
        while (requests.tryReceive().isSuccess) Unit
        discardExtractor()
        cache.clear(); cacheBytes = 0
        mutable.value = TimelineFrameState.Idle
    }
    fun release() { bindingJob?.cancel(); bindingJob = null; hide(); closed = true; requests.close() }
    private fun discardExtractor() {
        // A call stays active while its body is read, even after OkHttp's dispatcher is idle.
        // Cancel only this preview's calls before Media3 waits for its loader to release.
        httpLifetime?.cancel(); httpLifetime = null
        extractor?.release(); extractor = null
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Preview application thread required" }
}

private suspend fun ListenableFuture<TelumiaFrameExtractor.Frame>.awaitFrame(): TelumiaFrameExtractor.Frame =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel(false) }
        addListener({
            if (continuation.isActive) {
                try { continuation.resume(get()) }
                catch (_: Exception) { continuation.resumeWithException(IllegalStateException("Unavailable preview frame")) }
            }
        }, Executor { it.run() })
    }
