@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.reshaped.livetv.LIVE_TV_UNGROUPED
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvCatchupLinks
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvReplay
import com.nuvio.tv.reshaped.livetv.LiveTvReplayWindow
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences
import com.nuvio.tv.reshaped.livetv.LiveTvUiState
import com.nuvio.tv.ui.screens.player.PlayerEvent
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.PlayerUiState
import com.nuvio.tv.ui.screens.player.onEvent
import com.nuvio.tv.ui.screens.player.switchToSourceStream
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live TV inside Nuvio's player: CH+/CH- (and ▲▼ while the controls are hidden) switch channel,
 * ◀ opens the channel list and ◀ again its categories (▶ there lists a channel's programmes), ▶ the player's controls, and a banner shows what is on after each switch. Everything is
 * inert unless the player is playing a Live TV channel.
 */
@Stable
internal class LiveTvPlayerState(
    private val controller: PlayerRuntimeController,
    private val containerFocusRequester: FocusRequester,
    private val scope: CoroutineScope,
) {
    var panelOpen by mutableStateOf(false)
        private set
    /** The categories column beside the channel list (◀ from the list). */
    var foldersOpen by mutableStateOf(false)
        private set
    /** The category the panel lists, or null for the list being zapped. */
    var panelFolderKey by mutableStateOf<String?>(null)
        private set
    /** The channels the panel lists. */
    var panelChannels by mutableStateOf<List<LiveTvChannel>>(emptyList())
        private set
    private var folderJob: Job? = null
    /** The list entry of the channel playing now (a Stalker link differs from its list URL). */
    var currentListUrl by mutableStateOf<String?>(null)
        private set
    /** Bumped on each switch so the banner shows again. */
    var bannerKey by mutableIntStateOf(0)
        private set

    private var switchJob: Job? = null

    /** The channel focused in the panel, which ▶ lists the programmes of. */
    internal var panelFocusedUrl: String? = null
    /** The channel whose programmes the panel lists (▶ from the channel list), or null. */
    var programmesChannel by mutableStateOf<LiveTvChannel?>(null)
        private set
    /** The channel the list comes back to from its programmes, instead of the one playing. */
    internal var panelReturnUrl: String? = null
        private set

    /** Whether the day column beside the programmes has the focus (▶ from the programmes). */
    var programmeDaysFocused by mutableStateOf(false)

    private fun openProgrammes() {
        val url = panelFocusedUrl ?: currentListUrl
        programmeDaysFocused = false
        programmesChannel = panelChannels.firstOrNull { it.streamUrl == url } ?: return
    }

    /** Back to the channel list, on the channel whose programmes were shown. */
    internal fun closeProgrammes() {
        val channel = programmesChannel ?: return
        panelReturnUrl = channel.streamUrl
        programmeDaysFocused = false
        programmesChannel = null
    }

    /** OK on a programme: an ended one plays again where the provider keeps it; anything else plays live. */
    internal fun playProgramme(channel: LiveTvChannel, programme: LiveTvProgramme) {
        val now = LiveTvClock.nowEpochMs()
        if (programme.stopEpochMs <= now && LiveTvCatchupLinks.isPlayable(channel.catchup, programme, now)) {
            playCatchup(channel, programme)
        } else {
            pickFromPanel(channel)
        }
    }

    /** The Now/Next card OK shows over the picture; OK again opens the controls. Never pauses. */
    var infoOpen by mutableStateOf(false)
        private set
    private var infoJob: Job? = null
    /** The release of an OK press Live TV acted on, which must not reach the controls it opened. */
    private var swallowOkRelease = false
    /** OK is down on the bare picture: a short press acts on release, a held one starts the programme over. */
    private var okHeld = false

    private fun showInfo() {
        infoOpen = true
        infoJob?.cancel()
        infoJob = scope.launch {
            delay(INFO_MS)
            infoOpen = false
        }
    }

    internal fun hideInfo() {
        infoJob?.cancel()
        infoJob = null
        infoOpen = false
    }

    /** The player, for the details the Now/Next card shows. */
    internal val player: PlayerRuntimeController get() = controller

    /** Whether the player is showing a Live TV channel (read on each key; a memory lookup). */
    fun isActive(): Boolean = LiveTvPlaybackRegistry.isLiveTv(controller.currentStreamUrl)

    internal fun syncCurrent() {
        if (currentListUrl == null && isActive()) {
            currentListUrl = LiveTvPlaybackRegistry.listUrlFor(controller.currentStreamUrl)
            bannerKey++
        }
    }

    /** The list zapping moves through: the one the channel was picked from, else every shown channel. */
    internal fun zapList(): List<LiveTvChannel> = zapTarget().first

    /** The list zapping moves through and the category it is (null for a search). */
    private fun zapTarget(): Pair<List<LiveTvChannel>, String?> {
        val picked = LiveTvRepository.zapList
        if (picked.any { it.streamUrl == currentListUrl }) return picked to LiveTvRepository.zapFolderKey
        // Worked out off the main thread whenever the list or what is hidden changes.
        return LiveTvRepository.uiState.value.shownChannels to FILTER_ALL
    }

    /** The category the panel's list is, so the categories open on it; null for a search. */
    var zappedFolderKey by mutableStateOf<String?>(null)
        private set

    /** Called first by the player's key handler; true when the key was Live TV's. */
    fun onPreviewKey(event: KeyEvent, uiState: PlayerUiState): Boolean {
        if (!isActive()) return false
        val nuvioOverlayOpen = uiState.showEpisodesPanel || uiState.showSourcesPanel ||
            uiState.showAudioOverlay || uiState.showSubtitleOverlay || uiState.showSubtitleStylePanel ||
            uiState.showSpeedDialog || uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog ||
            uiState.showMoreDialog || uiState.showStreamInfoOverlay
        val down = event.action == KeyEvent.ACTION_DOWN
        if (scrub != null) return onScrubKey(event, down, uiState)
        if (panelOpen && programmesChannel != null) {
            return when (event.keyCode) {
                // Back to the channels, on the channel whose programmes these are.
                // From the days, back to the programmes first.
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (!down) {
                        if (programmeDaysFocused) programmeDaysFocused = false else closeProgrammes()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (down && event.repeatCount == 0) {
                        if (programmeDaysFocused) programmeDaysFocused = false else closeProgrammes()
                    }
                    true
                }
                // ▶ to the day column, when the programmes span more than one day.
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down && event.repeatCount == 0) programmeDaysFocused = true
                    true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> true
                else -> false // the list handles the rest
            }
        }
        if (panelOpen && foldersOpen) {
            return when (event.keyCode) {
                // Back to the channels, which show the category last focused.
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!down) foldersOpen = false
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> true
                else -> false // the column handles the rest
            }
        }
        if (panelOpen) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (!down) closePanel()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down && event.repeatCount == 0) openProgrammes()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (down && event.repeatCount == 0) foldersOpen = true
                    true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> true
                else -> false // the list handles the rest
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (nuvioOverlayOpen && uiState.error == null) return false
                if (down) zap(if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) -1 else 1)
                return true
            }
        }
        if (event.keyCode in OK_KEYS && !down && swallowOkRelease) {
            swallowOkRelease = false
            return true
        }
        if (event.keyCode !in OK_KEYS) okHeld = false
        // A channel that failed: ▲▼ still zap away from it (most remotes have no CH+/CH-). ◀▶ stay
        // the error screen's, which moves between its buttons.
        if (uiState.error != null && !nuvioOverlayOpen && !isCatchup()) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) zap(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    true
                }
                else -> false
            }
        }
        if (isCatchup()) {
            // A past programme seeks and pauses like a film: only ▲▼ (back to live channels) and
            // Back on the bare picture (back to this channel live) stay Live TV's.
            if (uiState.showControls || nuvioOverlayOpen || uiState.error != null) return false
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) zap(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    true
                }
                // A replay the player cannot seek in (TS, most providers' kind) moves by asking for
                // another replay instead; one that seeks (HLS) seeks like a film.
                in SCRUB_KEYS -> {
                    // ◀ at the very start of one that seeks (a programme started over, which
                    // began early) reaches back past its start with the rewind bar.
                    if (replaySeeks() && !(event.keyCode in BACK_SCRUB_KEYS && atReplayStart())) return false
                    val channel = currentChannel() ?: return false
                    if (down && event.repeatCount == 0) openScrub(channel, if (event.keyCode in BACK_SCRUB_KEYS) -1 else 1)
                    channel.catchup != null
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (uiState.showPauseOverlay) return false
                    // A channel gone since (removed, or its source edited) leaves Back to the player.
                    val channel = currentChannel() ?: return false
                    if (!down) switchTo(channel)
                    true
                }
                else -> false
            }
        }
        // ▲▼◀ and OK are Live TV's only on the bare picture: never over controls, panels or errors.
        if (uiState.showControls || nuvioOverlayOpen || uiState.error != null || uiState.showPauseOverlay) {
            if (infoOpen) hideInfo()
            return false
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // A short press acts on release (info, then the controls); held, the programme on
                // now plays from its start where the provider keeps it. The player never sees OK
                // (which pauses).
                when {
                    down && event.repeatCount == 0 -> okHeld = true
                    down -> if (okHeld && startOverFromHold()) {
                        okHeld = false
                        swallowOkRelease = true
                    }
                    okHeld -> {
                        okHeld = false
                        // Some remotes send no repeats for a held OK, only a late release.
                        val held = event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
                        when {
                            held && startOverFromHold() -> Unit
                            infoOpen -> {
                                hideInfo()
                                controller.onEvent(PlayerEvent.OnToggleControls)
                            }
                            else -> showInfo()
                        }
                    }
                }
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (!infoOpen) return false
                if (!down) hideInfo()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) zap(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // With the info card up, ◀ rewinds a channel the provider keeps (catch-up).
                val rewind = infoOpen && currentChannel()?.catchup != null
                if (down && event.repeatCount == 0) {
                    val channel = currentChannel()
                    if (rewind && channel != null) openScrub(channel, -1) else openPanel()
                }
                true // also swallows the release, which would commit a seek
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val channel = currentChannel()?.takeIf { it.catchup != null } ?: return false
                if (down && event.repeatCount == 0) openScrub(channel, -1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // A live channel has nothing to seek to: ▶ opens the controls (audio, subtitles).
                if (down && event.repeatCount == 0) {
                    hideInfo()
                    controller.onEvent(PlayerEvent.OnToggleControls)
                }
                true
            }
            else -> false
        }
    }

    /** Where the playing channel was in the list last zapped, so a zap skips searching for it. */
    private var zapIndexHint = -1

    private fun zap(step: Int) {
        hideInfo()
        val url = currentListUrl
        val picked = LiveTvRepository.zapList
        val pickedHint = zapIndexHint.takeIf { it in picked.indices && picked[it].streamUrl == url }
        val list = if (pickedHint != null || picked.any { it.streamUrl == url }) picked else LiveTvRepository.uiState.value.shownChannels
        if (list.isEmpty()) return
        val index = zapIndexHint.takeIf { it in list.indices && list[it].streamUrl == url } ?: list.indexOfFirst { it.streamUrl == url }
        val next = if (index < 0) 0 else Math.floorMod(index + step, list.size)
        zapIndexHint = next
        switchTo(list[next])
    }

    /**
     * Gives the key focus back to the player once a failed channel's error screen is gone (its
     * focused button left with it), so the next ▲▼ or OK is not lost and Back stays Live TV's.
     */
    internal suspend fun refocusPlayer() {
        repeat(5) {
            withFrameNanos { }
            if (panelOpen || controller._uiState.value.showControls) return
            if (runCatching { containerFocusRequester.requestFocus() }.isSuccess) return
        }
    }

    private fun openPanel() {
        hideInfo()
        panelFocusedUrl = null
        panelReturnUrl = null
        programmesChannel = null
        folderJob?.cancel()
        panelFolderKey = null
        val (channels, folderKey) = zapTarget()
        panelChannels = channels
        zappedFolderKey = folderKey
        foldersOpen = false
        panelOpen = true
    }

    /** Shows a category's channels in the panel; zapping follows once one of them is picked. */
    internal fun showFolder(key: String) {
        if (key == panelFolderKey) return
        panelFolderKey = key
        panelReturnUrl = null
        folderJob?.cancel()
        folderJob = scope.launch {
            val state = LiveTvRepository.uiState.value
            panelChannels = withContext(Dispatchers.Default) {
                filterChannels(state.channels, state.favoriteUrls, state.hiddenGroups, state.hiddenChannelKeys, key, customLists = state.customLists)
            }
        }
    }

    /** A channel picked from the panel: zapping then stays in the list it was picked from. */
    internal fun pickFromPanel(channel: LiveTvChannel) {
        panelFolderKey?.let { LiveTvRepository.setZapList(panelChannels, it) }
        switchTo(channel)
    }

    /** The rewind bar, while it shows (see [LiveTvScrub]). */
    var scrub by mutableStateOf<LiveTvScrub?>(null)
        private set
    private var scrubJob: Job? = null
    /** OK went down while the rewind bar showed: its release plays the time picked. */
    private var scrubOkDown = false

    /** Whether the replay playing can be sought in by the player itself (HLS with a length). */
    private fun replaySeeks(): Boolean =
        controller._exoPlayer?.let { it.isCurrentMediaItemSeekable && it.duration != androidx.media3.common.C.TIME_UNSET } == true

    /** Whether the replay playing is within a few seconds of its own start: the player's bar can go no further back. */
    private fun atReplayStart(): Boolean =
        controller._exoPlayer?.currentPosition?.let { it < REPLAY_START_MARGIN_MS } == true

    /** Where the picture is in the channel's time: now when live, else the replay's position. */
    private fun playheadMs(now: Long): Long {
        val window = LiveTvPlaybackRegistry.replayWindow(controller.currentStreamUrl) ?: return now
        return (window.startMs + controller.playbackTimeline.value.currentPosition.coerceAtLeast(0L)).coerceAtMost(now)
    }

    /** The earliest time [channel]'s provider keeps, with a minute to spare. */
    private fun earliestMs(channel: LiveTvChannel, now: Long): Long =
        now - (channel.catchup?.days ?: 0) * 24L * 60 * 60 * 1000 + 60_000L

    /** Shows the rewind bar on where the picture is, moved one [direction] step. */
    private fun openScrub(channel: LiveTvChannel, direction: Int) {
        if (channel.catchup == null) return
        // Its older days, for stepping back past what the guide keeps in memory.
        LiveTvRepository.requestHistory(channel)
        hideInfo()
        val now = LiveTvClock.nowEpochMs()
        val at = playheadMs(now)
        val from = LiveTvScrubSteps.rangeStart(LiveTvRepository.schedule(channel.guideKey), at, earliestMs(channel, now))
        scrub = LiveTvScrub(channel, from.coerceAtMost(at), at)
        stepScrub(direction, 0)
    }

    /**
     * Moves the picked time; ◀ at the bar's start reaches back to the show before. A held ◀ stops
     * at the start of a show (it would race on through the show before): a fresh press goes on.
     */
    private fun stepScrub(direction: Int, repeatCount: Int) {
        val bar = scrub ?: return
        val now = LiveTvClock.nowEpochMs()
        val schedule = LiveTvRepository.schedule(bar.channel.guideKey)
        if (direction < 0 && bar.targetMs <= bar.fromMs && repeatCount == 0) {
            val earlier = LiveTvScrubSteps.rangeStart(schedule, bar.fromMs - 1, earliestMs(bar.channel, now))
            if (earlier < bar.fromMs) bar.fromMs = earlier
        }
        var target = bar.targetMs + direction * LiveTvScrubSteps.stepMs(repeatCount)
        if (direction < 0 && repeatCount > 0) target = LiveTvScrubSteps.heldBackTo(schedule, bar.targetMs, target)
        bar.targetMs = target.coerceIn(bar.fromMs, now)
        // A key held down again keeps the bar up.
        scrubJob?.cancel()
        scrubJob = null
    }

    /** As the player's own bar does after ◀▶ are let go: plays from the time picked once the keys rest. */
    private fun settleScrub() {
        scrubJob?.cancel()
        scrubJob = scope.launch {
            delay(SCRUB_SETTLE_MS)
            commitScrub()
        }
    }

    private fun closeScrub() {
        scrubJob?.cancel()
        scrubJob = null
        scrubOkDown = false
        scrub = null
    }

    /** Plays from the picked time: a replay from there, or the channel live when that is now. */
    private fun commitScrub() {
        val bar = scrub ?: return
        closeScrub()
        val channel = bar.channel
        val now = LiveTvClock.nowEpochMs()
        if (now - bar.targetMs < LiveTvScrubSteps.LIVE_MARGIN_MS) {
            if (isCatchup()) switchTo(channel)
            return
        }
        val start = bar.targetMs
        switchJob?.cancel()
        switchJob = scope.launch {
            val replay = LiveTvRepository.replayChannel(channel, start)
            if (replay == null) {
                android.widget.Toast.makeText(controller.context, R.string.live_tv_catchup_failed, android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            currentListUrl = channel.streamUrl
            val programme = LiveTvRepository.schedule(channel.guideKey).firstOrNull { start >= it.startEpochMs && start < it.stopEpochMs }
            replayTitleProgramme = programme
            controller._uiState.update { it.copy(title = programme?.title ?: channel.name, logo = LiveTvRepository.uiState.value.logoFor(channel)) }
            playReplay(channel, replay)
        }
    }

    /** Keys while the rewind bar shows: ◀▶ move (played from once let go), OK plays at once, Back leaves it. */
    private fun onScrubKey(event: KeyEvent, down: Boolean, uiState: PlayerUiState): Boolean {
        if (panelOpen || uiState.showControls) {
            closeScrub()
            return false
        }
        when (event.keyCode) {
            in SCRUB_KEYS -> if (down) stepScrub(if (event.keyCode in BACK_SCRUB_KEYS) -1 else 1, event.repeatCount) else settleScrub()
            in OK_KEYS -> if (down && event.repeatCount == 0) {
                scrubOkDown = true
            } else if (!down && scrubOkDown) {
                commitScrub()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (down) commitScrub()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) closeScrub()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                // Leaves the bar; the key then zaps as usual.
                closeScrub()
                return onPreviewKey(event, uiState)
            }
        }
        return true
    }

    /** Whether the player shows a past programme (catch-up) rather than the live channel. */
    internal fun isCatchup(): Boolean = LiveTvPlaybackRegistry.isCatchup(controller.currentStreamUrl)

    /** The list entry of the channel playing (live or catch-up). */
    private fun currentChannel(): LiveTvChannel? =
        currentListUrl?.let { url -> LiveTvRepository.uiState.value.channels.firstOrNull { it.streamUrl == url } }

    /** Starts the programme on now over, when the channel playing can; whether it did. */
    private fun startOverFromHold(): Boolean {
        val channel = currentChannel() ?: return false
        val programme = startOverProgramme(channel) ?: return false
        playCatchup(channel, programme)
        return true
    }

    /** What is on now on [channel], when its provider can play it from the start; null otherwise. */
    internal fun startOverProgramme(channel: LiveTvChannel): LiveTvProgramme? {
        val now = LiveTvClock.nowEpochMs()
        val programme = LiveTvRepository.uiState.value.currentProgrammes[channel.guideKey] ?: return null
        return programme.takeIf { it.startEpochMs < now && now < it.stopEpochMs && LiveTvCatchupLinks.isPlayable(channel.catchup, it, now) }
    }

    /**
     * Plays [channel]'s [programme] again from its start (past, or on now: start over); it stays
     * the channel ▲▼ zap from, and plays on past the programme's end (see [followReplay]).
     */
    internal fun playCatchup(channel: LiveTvChannel, programme: LiveTvProgramme) {
        closePanel()
        hideInfo()
        switchJob?.cancel()
        switchJob = scope.launch {
            val replay = LiveTvRepository.catchupChannel(channel, programme)
            if (replay == null) {
                // What plays stays as it was.
                android.widget.Toast.makeText(controller.context, R.string.live_tv_catchup_failed, android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            currentListUrl = channel.streamUrl
            bannerKey++
            replayTitleProgramme = programme
            controller._uiState.update { it.copy(title = programme.title, logo = LiveTvRepository.uiState.value.logoFor(channel)) }
            playReplay(channel, replay)
        }
    }

    private fun playReplay(channel: LiveTvChannel, replay: LiveTvReplay) {
        val url = PlayerMediaSourceFactory.normalizePlaybackRequest(replay.playback.streamUrl, replay.playback.headers).url
        LiveTvPlaybackRegistry.register(url, listUrl = channel.streamUrl, catchup = true, window = replay.window)
        replayFallback = replay.fallback?.let { url to it }
        controller.switchToSourceStream(channel.toStream(replay.playback))
    }

    /** The replay playing (its player URL) and the other link to play should the provider fail it. */
    private var replayFallback: Pair<String, LiveTvReplay>? = null

    /** Plays the other link of the replay at [url] when the provider failed it; whether it did. */
    internal fun playReplayFallback(url: String): Boolean {
        val (failed, fallback) = replayFallback ?: return false
        if (failed != url) return false
        val channel = currentChannel() ?: return false
        replayFallback = null
        switchJob?.cancel()
        playReplay(channel, fallback)
        return true
    }

    /** The programme the title shows while a replay plays. */
    private var replayTitleProgramme: LiveTvProgramme? = null
    /** The replay whose end has been acted on, so it is acted on once. */
    private var continuedWindow: LiveTvReplayWindow? = null

    /**
     * Follows a replay as it plays ([positionMs] into it, [durationMs] long when known): the title
     * becomes the programme it has got to, and near its end the next part plays, or the channel
     * live once the replay has caught up. One map lookup when nothing is replayed.
     */
    internal fun followReplay(positionMs: Long, durationMs: Long) {
        val window = LiveTvPlaybackRegistry.replayWindow(controller.currentStreamUrl) ?: return
        val channel = currentChannel() ?: return
        val at = window.startMs + positionMs
        val shown = replayTitleProgramme
        if (shown == null || at < shown.startEpochMs || at >= shown.stopEpochMs) {
            LiveTvRepository.schedule(channel.guideKey).firstOrNull { at >= it.startEpochMs && at < it.stopEpochMs }?.let { programme ->
                replayTitleProgramme = programme
                if (controller._uiState.value.title != programme.title) controller._uiState.update { it.copy(title = programme.title) }
            }
        }
        // A replay's length is known for HLS; a TS replay is as long as was asked for. One that
        // names no end runs on to live by itself, and is only followed should it stop.
        if (!window.bounded) return
        val length = if (durationMs > 0L) durationMs else window.endMs - window.startMs
        if (positionMs > 0L && positionMs >= length - REPLAY_END_MARGIN_MS) continueReplay(window)
    }

    /** At the end of [window]: the next part of the channel, or the channel live once caught up. */
    internal fun continueReplay(window: LiveTvReplayWindow) {
        if (continuedWindow === window) return
        continuedWindow = window
        val channel = currentChannel() ?: return
        if (LiveTvClock.nowEpochMs() - window.endMs < REPLAY_CAUGHT_UP_MS) {
            replayTitleProgramme = null
            switchTo(channel)
            return
        }
        switchJob?.cancel()
        switchJob = scope.launch {
            // Gone from the provider since (or no longer kept): the channel live.
            val replay = LiveTvRepository.replayChannel(channel, window.endMs) ?: return@launch switchTo(channel)
            playReplay(channel, replay)
        }
    }

    internal fun switchTo(channel: LiveTvChannel) {
        if (channel.streamUrl == currentListUrl && !isCatchup()) {
            closePanel()
            return
        }
        currentListUrl = channel.streamUrl
        bannerKey++
        replayTitleProgramme = null
        closePanel()
        // The loading screen and pause screen show the player's logo: the new channel's, from the first press.
        controller._uiState.update { it.copy(title = channel.name, logo = LiveTvRepository.uiState.value.logoFor(channel)) }
        // Quick presses land on the last channel only.
        switchJob?.cancel()
        switchJob = scope.launch {
            delay(ZAP_SETTLE_MS)
            val playback = LiveTvRepository.prepareForPlayback(channel)
            LiveTvPlaybackRegistry.register(
                PlayerMediaSourceFactory.normalizePlaybackRequest(playback.streamUrl, playback.headers).url,
                listUrl = channel.streamUrl,
            )
            controller.switchToSourceStream(channel.toStream(playback))
        }
    }

    /** Switches the display to the playing channel's frame rate (see [LiveTvFrameRateMatch]). */
    internal suspend fun matchDisplay(fps: Float, raw: Float) = controller.matchDisplayToLiveTrack(fps, raw)

    internal fun closePanel() {
        if (!panelOpen) return
        // The programmes stay as they are while the panel slides away; opening it clears them.
        panelOpen = false
        foldersOpen = false
        runCatching { containerFocusRequester.requestFocus() }
    }

    private fun LiveTvChannel.toStream(playback: LiveTvChannel) = Stream(
        name = name,
        title = name,
        description = group.takeIf(String::isNotBlank),
        url = playback.streamUrl,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = ProxyHeaders(request = playback.headers, response = null),
        ),
        addonName = LIVE_TV_ADDON_NAME,
        addonLogo = null,
    )

    private companion object {
        const val ZAP_SETTLE_MS = 350L
        /** How near its end a replay hands over to what follows, before the player stops at its end. */
        const val REPLAY_END_MARGIN_MS = 1_500L
        /** A replay this near to now goes live rather than asking for a few seconds more. */
        const val REPLAY_CAUGHT_UP_MS = 90_000L
        const val INFO_MS = 6_000L
        /** After ◀▶ are let go, the rewind bar waits this long for another press before playing from there. */
        const val SCRUB_SETTLE_MS = 1_200L
        const val REPLAY_START_MARGIN_MS = 5_000L
        val SCRUB_KEYS = intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        val BACK_SCRUB_KEYS = intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND)
        val OK_KEYS = intArrayOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}

@Composable
internal fun rememberLiveTvPlayer(controller: PlayerRuntimeController, containerFocusRequester: FocusRequester): LiveTvPlayerState {
    val scope = rememberCoroutineScope()
    return remember(controller) { LiveTvPlayerState(controller, containerFocusRequester, scope) }
}

/** The banner and channel panel, drawn over the player. */
@Composable
internal fun LiveTvPlayerOverlay(state: LiveTvPlayerState, uiState: PlayerUiState) {
    if (!state.isActive()) return
    Box(modifier = Modifier.fillMaxSize().zIndex(3f)) {
        LiveTvPlayerOverlayContent(state, uiState)
    }
}

@Composable
private fun BoxScope.LiveTvPlayerOverlayContent(state: LiveTvPlayerState, uiState: PlayerUiState) {
    LaunchedEffect(Unit) { state.syncCurrent() }
    // Live TV lets go of its channels while unseen (the app in the background for a while):
    // back on a channel, they load again so zapping and the channel list work.
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(lifecycleOwner) {
        LiveTvClock.followDeviceHourFormat(context)
        LiveTvRepository.reloadIfReleased()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) LiveTvRepository.reloadIfReleased() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LiveTvFrameRateMatch(state, uiState)
    LiveTvReplayFollow(state)
    // Only after an error screen: elsewhere the player's own focus handling stands.
    val hasError = uiState.error != null
    var hadError by remember { mutableStateOf(false) }
    LaunchedEffect(hasError) {
        if (hasError) {
            hadError = true
        } else if (hadError) {
            hadError = false
            state.refocusPlayer()
        }
    }
    val liveState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(liveState.isLoaded, liveState.isLoading, liveState.hasSource) {
        if (!liveState.isLoaded && !liveState.isLoading && !liveState.hasSource) LiveTvRepository.reloadIfReleased()
    }
    val clock = rememberLiveTvMinuteClock()
    // Numbered within the list being zapped (a category keeps its own 1, 2, 3...).
    val zapList = remember(state.currentListUrl, liveState.shownChannels) { state.zapList() }
    val currentIndex = remember(state.currentListUrl, zapList) { zapList.indexOfFirst { it.streamUrl == state.currentListUrl } }
    val current = zapList.getOrNull(currentIndex)

    var bannerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.bannerKey) {
        if (state.bannerKey == 0) return@LaunchedEffect
        bannerVisible = true
        delay(BANNER_MS)
        bannerVisible = false
    }
    AnimatedVisibility(
        visible = bannerVisible && current != null && !uiState.showControls && !state.panelOpen && !state.infoOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopStart).zIndex(3f),
    ) {
        current?.let { channel ->
            LiveTvBanner(
                channel = channel,
                logo = liveState.logoFor(channel),
                programme = liveState.currentProgrammes[channel.guideKey],
                number = currentIndex + 1,
                clock = clock,
            )
        }
    }

    val details by rememberLiveTvStreamDetails(state.player, state.infoOpen)
    AnimatedVisibility(
        visible = state.infoOpen && current != null && !uiState.showControls && !state.panelOpen,
        enter = slideInVertically { it / 3 } + fadeIn(),
        exit = slideOutVertically { it / 3 } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).zIndex(3f),
    ) {
        current?.let { channel ->
            LiveTvInfoCard(
                channel = channel,
                logo = liveState.logoFor(channel),
                details = details,
                now = liveState.currentProgrammes[channel.guideKey],
                number = currentIndex + 1,
                clock = clock,
                startOver = remember(channel, liveState.currentProgrammes, clock.value / 60_000L) { state.startOverProgramme(channel) != null },
                rewind = channel.catchup != null,
            )
        }
    }

    AnimatedVisibility(
        visible = state.scrub != null && !uiState.showControls && !state.panelOpen,
        // As the player's own seek bar comes and goes.
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier = Modifier.align(Alignment.BottomCenter).zIndex(3f),
    ) {
        // The last bar stays drawn through the exit animation, after it has closed.
        val shown = remember { arrayOfNulls<LiveTvScrub>(1) }
        state.scrub?.let { shown[0] = it }
        shown[0]?.let { bar -> LiveTvScrubBar(bar) }
    }

    AnimatedVisibility(
        visible = state.panelOpen,
        enter = slideInHorizontally { -it } + fadeIn(),
        exit = slideOutHorizontally { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.CenterStart).zIndex(3f),
    ) {
        LiveTvChannelPanel(state, liveState.currentProgrammes, clock)
    }

}

/** Near solid, so the banner and info card read clearly over any picture. */
internal val LiveTvCardBackground = Color(0xF0121214)

@Composable
private fun LiveTvBanner(channel: LiveTvChannel, logo: String?, programme: LiveTvProgramme?, number: Int, clock: State<Long>) {
    Row(
        modifier = Modifier
            .padding(start = 48.dp, top = 40.dp)
            .widthIn(max = 640.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(LiveTvCardBackground)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number > 0) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(end = 16.dp),
            )
        }
        LiveTvLogo(url = logo, name = channel.name, width = 88.dp, height = 54.dp)
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (programme != null) {
                Text(
                    text = programme.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveTvProgressBar(
                        programme = programme,
                        clock = clock,
                        fill = Color.White,
                        track = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.width(220.dp),
                    )
                    Text(
                        text = "${LiveTvClock.formatSpan(programme)}  ·  ${liveTvTimeLeft(programme, clock)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.live_tv_player_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The Now/Next card OK shows: the channel with its picture and sound (resolution, frame rate,
 * codecs), what is on with how far it has got, and what follows.
 */
@Composable
private fun LiveTvInfoCard(
    channel: LiveTvChannel,
    logo: String?,
    details: LiveTvStreamDetails,
    now: LiveTvProgramme?,
    number: Int,
    clock: State<Long>,
    startOver: Boolean,
    rewind: Boolean,
) {
    // Read once per minute tick: the kept guide is a map lookup.
    val next = remember(channel.guideKey, now, clock.value / 60_000L) { LiveTvRepository.nextProgramme(channel.guideKey) }
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .padding(start = 48.dp, end = 48.dp, bottom = 36.dp)
            .widthIn(max = 880.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(LiveTvCardBackground)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveTvLogo(url = logo, name = channel.name, width = 104.dp, height = 64.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (number > 0) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.68f),
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                details.labels.forEach { label ->
                    LiveTvDetailChip(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (now == null) {
                Text(
                    text = stringResource(R.string.live_tv_info_no_guide),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.live_tv_info_now).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                        modifier = Modifier
                            .clip(LiveTvPillShape)
                            .background(Color.White.copy(alpha = 0.92f))
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                    Text(
                        text = now.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveTvProgressBar(
                        programme = now,
                        clock = clock,
                        fill = Color.White,
                        track = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.width(240.dp),
                    )
                    Text(
                        text = "${LiveTvClock.formatSpan(now)}  ·  ${liveTvTimeLeft(now, clock)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                next?.let {
                    Text(
                        text = stringResource(R.string.live_tv_info_next, LiveTvClock.formatClock(it.startEpochMs)) + "  " + it.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Text(
                text = stringResource(
                    when {
                        rewind && startOver -> R.string.live_tv_info_hint_rewind_start_over
                        rewind -> R.string.live_tv_info_hint_rewind
                        startOver -> R.string.live_tv_info_hint_start_over
                        else -> R.string.live_tv_info_hint
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** A quiet outlined tag: "1080p", "50 fps". */
@Composable
private fun LiveTvDetailChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.82f),
        maxLines = 1,
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.24f), LiveTvPillShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun LiveTvChannelPanel(state: LiveTvPlayerState, programmes: Map<String, LiveTvProgramme>, clock: State<Long>) {
    val liveState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.92f),
                    0.85f to Color.Black.copy(alpha = 0.82f),
                    1f to Color.Black.copy(alpha = 0f),
                ),
            ),
    ) {
        AnimatedVisibility(
            visible = state.foldersOpen,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
        ) {
            LiveTvFolderColumn(state, liveState)
        }
        // ▶ on a channel: its programmes in place of the channels, ◀ back.
        AnimatedContent(
            targetState = state.programmesChannel,
            contentKey = { it?.streamUrl },
            transitionSpec = {
                val step = if (targetState != null) 1 else -1
                (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { step * it / 10 }) togetherWith fadeOut(tween(120))
            },
            label = "panel",
        ) { channel ->
            if (channel == null) {
                LiveTvChannelColumn(state, programmes, liveState, clock)
            } else {
                LiveTvProgrammeColumn(state, channel, liveState, clock)
            }
        }
    }
}

/** The categories: focusing one lists its channels beside it (after a short rest, so passing over is cheap). */
@Composable
private fun LiveTvFolderColumn(state: LiveTvPlayerState, liveState: LiveTvUiState) {
    val allLabel = stringResource(R.string.live_tv_all_channels)
    val favoritesLabel = stringResource(R.string.live_tv_favorites)
    val uncategorisedLabel = liveTvGroupLabel(LIVE_TV_UNGROUPED)
    val showAll by LiveTvPreferences.showAll.collectAsStateWithLifecycle()
    val showFavorites by LiveTvPreferences.showFavorites.collectAsStateWithLifecycle()
    val folders = remember(liveState.sources, liveState.groups, liveState.hiddenGroups, liveState.groupNames, liveState.customLists, allLabel, favoritesLabel, uncategorisedLabel, showAll, showFavorites) {
        buildList {
            if (showAll) add(FILTER_ALL to allLabel)
            if (showFavorites) add(FILTER_FAVORITES to favoritesLabel)
            liveState.customLists.forEach { add(FILTER_LIST_PREFIX + it.id to it.name) }
            if (liveState.sources.size > 1) liveState.sources.forEach { add(FILTER_SOURCE_PREFIX + it.id to it.label) }
            liveState.visibleGroups.forEach {
                add(it to (liveTvGroupName(it, liveState.groupNames) ?: if (it == LIVE_TV_UNGROUPED) uncategorisedLabel else it))
            }
        }
    }
    // The panel opens on the zapped list's category (All channels for a search or one gone since).
    val zappedFolder = state.zappedFolderKey?.takeIf { key -> folders.any { it.first == key } } ?: FILTER_ALL
    val startKey = state.panelFolderKey ?: zappedFolder
    val startIndex = folders.indexOfFirst { it.first == startKey }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { startFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    Column(modifier = Modifier.fillMaxHeight().width(250.dp).padding(start = 32.dp, top = 32.dp, end = 8.dp)) {
        Text(
            text = stringResource(R.string.live_tv_categories),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            itemsIndexed(folders, key = { _, folder -> folder.first }) { index, (key, label) ->
                FolderRow(
                    label = label,
                    selected = key == (state.panelFolderKey ?: startKey),
                    onFocused = { state.showFolder(key) },
                    onClick = { state.showFolder(key) },
                    modifier = if (index == startIndex) Modifier.focusRequester(startFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    label: String,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused && !selected) {
            delay(250) // passing over a category does not re-filter
            onFocused()
        }
    }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent,
            focusedContainerColor = Color.White,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) Color.Black else if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LiveTvChannelColumn(
    state: LiveTvPlayerState,
    programmes: Map<String, LiveTvProgramme>,
    liveState: LiveTvUiState,
    clock: State<Long>,
) {
    val channels = state.panelChannels
    // Back from a channel's programmes: on that channel; otherwise on the one playing.
    val startIndex = remember(channels) {
        val back = state.panelReturnUrl?.let { url -> channels.indexOfFirst { it.streamUrl == url } } ?: -1
        (if (back >= 0) back else channels.indexOfFirst { it.streamUrl == state.currentListUrl }).coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    // A new category starts at its top (or at the channel playing, when it has it).
    LaunchedEffect(channels) {
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == startIndex }) {
            listState.scrollToItem((startIndex - 3).coerceAtLeast(0))
        }
    }
    val currentFocus = remember { FocusRequester() }
    // On opening, and when the categories close, focus goes to the channel playing (or the first).
    LaunchedEffect(state.foldersOpen) {
        if (state.foldersOpen) return@LaunchedEffect
        // The row must be composed before it can take focus.
        repeat(10) {
            if (runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    val folderKey = state.panelFolderKey
    val folderLabel = when {
        folderKey == null -> null
        folderKey == FILTER_ALL -> stringResource(R.string.live_tv_all_channels)
        folderKey == FILTER_FAVORITES -> stringResource(R.string.live_tv_favorites)
        folderKey.startsWith(FILTER_LIST_PREFIX) -> liveState.customLists.firstOrNull { FILTER_LIST_PREFIX + it.id == folderKey }?.name
        folderKey.startsWith(FILTER_SOURCE_PREFIX) ->
            liveState.sources.firstOrNull { FILTER_SOURCE_PREFIX + it.id == folderKey }?.label
        // One source's category: its name, as the Live TV screen shows it under the source.
        folderKey.startsWith(FILTER_SOURCE_GROUP_PREFIX) -> (filterFor(folderKey) as LiveTvFilter.SourceGroup).let { group ->
            val source = liveState.sources.firstOrNull { it.id == group.id }?.label
            listOfNotNull(liveTvGroupLabel(group.name, liveState.groupNames), source).joinToString("  ·  ")
        }
        else -> folderKey
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(460.dp)
            .padding(start = if (state.foldersOpen) 8.dp else 32.dp, end = 40.dp, top = 32.dp),
    ) {
        Text(
            text = folderLabel ?: stringResource(R.string.live_tv_player_channels),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.live_tv_player_categories_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = if (state.foldersOpen) 0f else 0.45f),
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.id }, contentType = { _, _ -> "channel" }) { index, channel ->
                PanelRow(
                    channel = channel,
                    logo = liveState.logoFor(channel),
                    programme = programmes[channel.guideKey],
                    playing = channel.streamUrl == state.currentListUrl,
                    clock = clock,
                    onClick = { state.pickFromPanel(channel) },
                    onFocused = { state.panelFocusedUrl = channel.streamUrl },
                    modifier = if (index == startIndex) Modifier.focusRequester(currentFocus) else Modifier,
                )
            }
            if (channels.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(
                            if (folderKey == FILTER_FAVORITES) R.string.live_tv_no_favorites else R.string.live_tv_no_channels_found,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelRow(
    channel: LiveTvChannel,
    logo: String?,
    programme: LiveTvProgramme?,
    playing: Boolean,
    clock: State<Long>,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        onLongClick = { LiveTvRepository.toggleFavorite(channel) },
        modifier = modifier.fillMaxWidth().onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveTvLogo(url = logo, name = channel.name, width = 60.dp, height = 38.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (focused) Color.Black else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                programme?.let {
                    Text(
                        text = it.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(modifier = Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiveTvProgressBar(
                            programme = it,
                            clock = clock,
                            fill = if (focused) Color.Black else Color.White,
                            track = if (focused) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.15f),
                            modifier = Modifier.width(120.dp),
                        )
                        Text(
                            text = liveTvTimeLeft(it, clock),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (focused) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.5f),
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            if (playing) {
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (focused) Color.Black else Color(0xFFE50914)),
                )
            }
        }
    }
}

private const val BANNER_MS = 4_000L
