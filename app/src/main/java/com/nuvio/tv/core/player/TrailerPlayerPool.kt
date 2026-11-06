package com.nuvio.tv.core.player

import android.content.Context
import android.text.Layout
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import com.nuvio.tv.data.trailer.TrailerSubtitleTrack
import com.nuvio.tv.data.trailer.pickTrailerSubtitle
import com.nuvio.tv.ui.screens.player.SdhFilteringTextOutput
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application-scoped singleton that holds a single ExoPlayer instance dedicated to
 * trailer/preview playback on the home screen.
 *
 * Creating and tearing down ExoPlayer for every poster focus is extremely expensive
 * (codec init, hardware decoder allocation). This pool keeps one instance alive and
 * reuses it across focus changes. The player is stopped and cleared between uses but
 * never released until the process dies or [release] is explicitly called.
 *
 * When the full-screen player needs hardware decoders, call [yield] to free
 * codec resources without destroying the instance. Call [reclaim] when returning to
 * the home screen to lazily rebuild if needed.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Singleton
class TrailerPlayerPool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val youTubeExtractor: InAppYouTubeExtractor
) {
    companion object {
        private const val TAG = "TrailerPlayerPool"

        // A trailer plays on top of the home UI and its image caches, so it gets a small byte cap
        // rather than Media3's default (~144 MB for video + audio). Low-RAM sticks get the smallest.
        private const val LOW_RAM_TRAILER_BUFFER_MB = 48
        private const val TRAILER_BUFFER_MB = 100
    }

    private var _player: ExoPlayer? = null
    private val yielded = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _isTrailerPlaying = MutableStateFlow(false)
    val isTrailerPlaying: StateFlow<Boolean> = _isTrailerPlaying.asStateFlow()

    @Volatile
    private var cachedForceNative: Boolean = false

    @Volatile
    private var cachedPlayerSettings: PlayerSettings? = null

    @Volatile
    private var cachedAllow4k: Boolean = TrailerVideoPolicy.default4kTrailers(context)

    // Read by the text renderer on every cue, refreshed by [subtitleFor] just before
    // playback starts, so toggling the setting takes effect on the next trailer.
    @Volatile
    private var sdhFilterEnabled: Boolean = false

    // Height cap currently applied to the player's track selection; reset when the player is rebuilt.
    private var appliedMaxHeight: Int? = null

    // Kept current so a player rebuilt after [yield] picks up buffer changes made since launch.
    init {
        settingsScope.launch {
            runCatching {
                trailerSettingsDataStore.settings.collect { cachedAllow4k = it.allow4k }
            }
        }
        settingsScope.launch {
            runCatching {
                playerSettingsDataStore.nuvioPerformanceModeEnabled.collect { cachedForceNative = it }
            }
        }
        settingsScope.launch {
            runCatching {
                playerSettingsDataStore.playerSettings.collect { cachedPlayerSettings = it }
            }
        }
    }

    /**
     * Resolves the subtitle track to sideload for [streamUrl], or null when trailer
     * subtitles are off, the trailer has no captions, or none of them match.
     */
    suspend fun subtitleFor(streamUrl: String): TrailerSubtitleTrack? {
        val settings = trailerSettingsDataStore.settings.first()
        sdhFilterEnabled = settings.subtitlesEnabled && settings.subtitlesSdhFilterEnabled
        if (!settings.subtitlesEnabled) return null
        val preferred = playerSettingsDataStore.playerSettings.first().subtitleStyle.preferredLanguage
        return pickTrailerSubtitle(youTubeExtractor.captionTracks(streamUrl), preferred)
    }

    /**
     * Returns the shared trailer ExoPlayer, creating it lazily if needed.
     * Returns null only if [release] was called (process shutdown).
     */
    fun acquire(): ExoPlayer? {
        if (released.get()) return null
        if (yielded.get()) {
            // Reclaim was not called yet but someone wants the player — rebuild.
            reclaim()
        }
        val player = _player ?: createPlayer().also {
            _player = it
            appliedMaxHeight = null
        }
        applyVideoSizeCap(player)
        return player
    }

    // Caps HLS variants; adaptive YouTube streams are already capped by the extractor.
    private fun applyVideoSizeCap(player: ExoPlayer) {
        val maxHeight = TrailerVideoPolicy.maxTrailerVideoHeight(cachedAllow4k)
        if (appliedMaxHeight == maxHeight) return
        val maxWidth = if (maxHeight == Int.MAX_VALUE) Int.MAX_VALUE else maxHeight * 16 / 9
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(maxWidth, maxHeight)
            .build()
        appliedMaxHeight = maxHeight
    }

    /**
     * Stops playback and clears media but keeps the instance alive for reuse.
     * Call this when the trailer is no longer visible (poster lost focus, screen change).
     */
    fun stop() {
        _player?.let { player ->
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
            }
        }
        _isTrailerPlaying.value = false
    }

    /**
     * Releases codec resources so the detail-screen player can claim hardware decoders.
     * The ExoPlayer instance is released here; [reclaim] will create a fresh one.
     */
    fun yield() {
        if (yielded.compareAndSet(false, true)) {
            Log.d(TAG, "Yielding trailer player for detail playback")
            _isTrailerPlaying.value = false
            _player?.let { player ->
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                runCatching { player.release() }
            }
            _player = null
        }
    }

    /**
     * Re-creates the player after a [yield]. Safe to call multiple times.
     */
    fun reclaim() {
        if (released.get()) return
        if (yielded.compareAndSet(true, false)) {
            Log.d(TAG, "Reclaiming trailer player")
            // Player will be lazily created on next acquire()
        }
    }

    /**
     * Permanently releases the player. Called on process death / Application.onTerminate.
     */
    fun release() {
        if (released.compareAndSet(false, true)) {
            _isTrailerPlaying.value = false
            _player?.let { player ->
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                runCatching { player.release() }
            }
            _player = null
        }
    }

    private fun createPlayer(): ExoPlayer {
        val forceNative = cachedForceNative
        val targetBufferMb = trailerTargetBufferMb(cachedPlayerSettings)
        Log.d(
            TAG,
            "Creating shared trailer ExoPlayer instance with forceNativeAllocation = $forceNative, " +
                "targetBufferMb = $targetBufferMb"
        )
        val loadControlBuilder = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 5_000,
                /* bufferForPlaybackAfterRebufferMs = */ 10_000
            )
            .setTargetBufferBytes(targetBufferMb * 1024 * 1024)
            // The byte cap must hold even before minBufferMs is reached, or a high-bitrate
            // trailer keeps allocating until the process is killed for low memory.
            .setPrioritizeTimeOverSizeThresholds(false)
        if (forceNative) {
            val allocator = DefaultAllocator(
                /* trimOnReset = */ true,
                /* individualAllocationSize = */ 65536,
                /* initialAllocationCount = */ 0,
                /* forceNativeAllocation = */ true
            )
            loadControlBuilder.setAllocator(allocator)
        }
        val loadControl = loadControlBuilder.build()
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSizeSd()
                    .clearVideoSizeConstraints()
                    .setForceHighestSupportedBitrate(true)
                    .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
            )
        }
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>
            ) {
                super.buildTextRenderers(
                    context,
                    SdhFilteringTextOutput(CenteredTextOutput(output)) { sdhFilterEnabled },
                    outputLooper,
                    extensionRendererMode,
                    out
                )
            }
        }
        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(
                DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(50_000_000L) // 50 Mbps – force highest HLS variant from start
                    .build()
            )
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isTrailerPlaying.value = isPlaying
                    }
                })
            }
    }

    /** Trailer cap for this device tier, lowered further when the user set a smaller Target Buffer Size. */
    private fun trailerTargetBufferMb(settings: PlayerSettings?): Int {
        val tierCapMb = if (MemoryBudget.isLowRamTier) LOW_RAM_TRAILER_BUFFER_MB else TRAILER_BUFFER_MB
        val userTargetMb = settings
            ?.takeIf { it.bufferEngineEnabled && !it.bufferBudgetManaged }
            ?.let { MemoryBudget.effectiveBufferMb(it.bufferSettings.targetBufferSizeMb) }
            ?: return tierCapMb
        return userTargetMb.coerceAtMost(tierCapMb)
    }
}

/**
 * YouTube caption tracks carry their own placement (commonly `align:start
 * position:0%`), which drops trailer subtitles into a corner. Clearing the cue
 * geometry lets SubtitleView fall back to its standard centred bottom position.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class CenteredTextOutput(private val delegate: TextOutput) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        delegate.onCues(CueGroup(cueGroup.cues.map(::center), cueGroup.presentationTimeUs))
    }

    @Suppress("DEPRECATION")
    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        delegate.onCues(cues.map(::center))
    }

    private fun center(cue: Cue): Cue = cue.buildUpon()
        .setPosition(Cue.DIMEN_UNSET)
        .setPositionAnchor(Cue.TYPE_UNSET)
        .setLine(Cue.DIMEN_UNSET, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.TYPE_UNSET)
        .setSize(Cue.DIMEN_UNSET)
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .build()
}
