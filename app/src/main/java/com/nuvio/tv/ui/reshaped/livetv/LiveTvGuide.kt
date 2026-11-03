@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvArchive
import com.nuvio.tv.reshaped.livetv.LiveTvCatchupLinks
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val MINUTE_WIDTH = 6.dp
private val ROW_HEIGHT = 56.dp
private val CHANNEL_COLUMN = 220.dp
private val CELL = Color(0xFF1A1A1D)
private val CELL_PAST = Color(0xFF141416)
private val CELL_NOW = Color(0xFF26262A)
private val CHANNEL_SELECTED = Color(0xFF2C2C31)
private val NOW_LINE = Color.White.copy(alpha = 0.55f)
/** The space between two cells of a row. */
private val CELL_GAP = 4.dp

/**
 * The programme guide: channels down, time across, the kept past hours to the next ones.
 * Remote handling is its own (not one focus target per programme), so thousands of channels stay
 * light: only the rows in view are composed, and only the programmes near the time in view.
 * ▲▼ move between channels at the same time, ◀▶ between programmes (and, where the guide has
 * nothing, half hours), CH+/CH- by a page, OK plays the channel, Back closes.
 *
 * On the Live TV screen the guide is embedded beside the categories: ◀ from what is on now (or
 * from the oldest programme that can be played again) calls [onExitLeft], ▲ on the first channel
 * [onExitUp], and a long OK [onLongPress].
 */
@Stable
internal class LiveTvGuideState(
    channels: List<LiveTvChannel>,
    startIndex: Int,
    private val onPlay: (LiveTvChannel) -> Unit,
    private val onClose: () -> Unit,
    /** A past programme of a channel with catch-up was picked. */
    private val onCatchup: (LiveTvChannel, LiveTvProgramme) -> Unit = { channel, _ -> onPlay(channel) },
    private val onExitLeft: (() -> Unit)? = null,
    private val onExitUp: (() -> Unit)? = null,
    private val onLongPress: ((LiveTvChannel) -> Unit)? = null,
    /** How much of the past the timeline shows when it opens. */
    private val leadMs: Long = GUIDE_SLOT,
) {
    var channels by mutableStateOf(channels)
        private set
    var row by mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0)))
        private set
    /** The time the selection follows between channels; always inside the selected block. */
    var anchorMs by mutableLongStateOf(LiveTvClock.nowEpochMs())
        private set
    /** The left edge of the timeline in view, on a half hour. */
    var viewStartMs by mutableLongStateOf(floorSlot(LiveTvClock.nowEpochMs() - leadMs))
        private set
    /** How much time fits in view; set once laid out. */
    var viewSpanMs = 3 * 60 * GUIDE_MINUTE

    val channel: LiveTvChannel? get() = channels.getOrNull(row)

    /**
     * Picking several channels at once: OK ticks the selected channel instead of playing it,
     * Back stops picking. [picked] is by stream link, in the order they were ticked.
     */
    var selecting by mutableStateOf(false)
        private set
    val picked = mutableStateMapOf<String, LiveTvChannel>()

    fun startSelecting(first: LiveTvChannel?) {
        picked.clear()
        first?.let { picked[it.streamUrl] = it }
        selecting = true
    }

    fun stopSelecting() {
        selecting = false
        picked.clear()
    }

    /**
     * Moving the selected channel within the list (a playlist of the viewer's): ▲▼ call
     * [onReorder] with it and a step, OK or Back put it down.
     */
    var moving by mutableStateOf(false)
        private set
    var onReorder: ((LiveTvChannel, Int) -> Unit)? = null

    fun stopMoving() {
        moving = false
    }

    fun startMoving() {
        if (onReorder != null && channel != null) moving = true
    }

    fun togglePicked(channel: LiveTvChannel) {
        if (picked.remove(channel.streamUrl) == null) picked[channel.streamUrl] = channel
    }

    /**
     * Each channel's programmes laid out without overlaps ([liveTvGuideBlocks]), worked out once
     * per guide and kept for the channels last shown. Used on the main thread only.
     */
    private val blockCache = object : LinkedHashMap<String, CachedBlocks>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedBlocks>?): Boolean = size > BLOCK_CACHE_SIZE
    }

    private class CachedBlocks(val source: List<LiveTvProgramme>, val blocks: List<LiveTvGuideBlock>)

    fun blocksFor(channel: LiveTvChannel): List<LiveTvGuideBlock> {
        val source = LiveTvRepository.schedule(channel.guideKey)
        if (source.isEmpty()) return emptyList()
        blockCache[channel.guideKey]?.let { if (it.source === source) return it.blocks }
        return liveTvGuideBlocks(source).also { blockCache[channel.guideKey] = CachedBlocks(source, it) }
    }

    /** The selected block: the programme (or the stretch with no guide) at [anchorMs] in the selected channel. */
    fun selectedBlock(): LiveTvGuideBlock? = channel?.let { blocksFor(it).blockAt(anchorMs) }

    /** The selected programme, or null where the guide has nothing. */
    fun selected(): LiveTvProgramme? = selectedBlock()?.programme

    /**
     * Shows [list], on [keepUrl]'s channel when it is in it; [toNow] (another category or search)
     * also brings the timeline back to now, while a list only filtered again stays where it was.
     */
    fun showChannels(list: List<LiveTvChannel>, keepUrl: String?, toNow: Boolean) {
        if (list === channels) return
        Snapshot.withMutableSnapshot {
            // The kept channel gone from a list filtered again (hidden, removed): the same place in it.
            val kept = list.indexOfFirst { it.streamUrl == keepUrl }
            row = if (kept >= 0 || toNow) kept.coerceAtLeast(0) else row.coerceIn(0, (list.size - 1).coerceAtLeast(0))
            channels = list
            if (toNow) backToNow()
        }
    }

    /** Selects the channel at [index] (focus coming back from the player). */
    fun selectRow(index: Int) {
        row = index.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    }

    fun backToNow() {
        val now = LiveTvClock.nowEpochMs()
        anchorMs = now
        viewStartMs = floorSlot(now - leadMs)
        followsNow = true
    }

    /**
     * Until ◀▶ move the selection, it stays on what is on now as time passes (the guide stays
     * open for hours on the Live TV screen); call on each minute.
     */
    fun followNow() {
        if (followsNow) backToNow()
    }

    private var followsNow = true

    private var handledDown = -1
    /** Repeats of a held ◀ while at the left edge, see [moveProgramme]. */
    private var heldAtEdge = 0
    private var longPressed = false

    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            val handled = event.keyCode == handledDown
            if (handled) {
                handledDown = -1
                // OK and Back act on release, as Nuvio's own buttons do, so no release is left
                // for the screen underneath (Back would leave Live TV, OK would pause the player).
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> channel?.let { picked ->
                        if (longPressed) {
                            longPressed = false
                            return true
                        }
                        if (moving) {
                            moving = false
                            return true
                        }
                        if (selecting) {
                            togglePicked(picked)
                            return true
                        }
                        // A programme that has ended plays again where the provider keeps it; anything else plays live.
                        val programme = selected()
                        if (programme != null && programme.stopEpochMs <= LiveTvClock.nowEpochMs() &&
                            LiveTvCatchupLinks.isPlayable(picked.catchup, programme, LiveTvClock.nowEpochMs())
                        ) {
                            onCatchup(picked, programme)
                        } else {
                            onPlay(picked)
                        }
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> when {
                        moving -> moving = false
                        selecting -> stopSelecting()
                        else -> onClose()
                    }
                }
            }
            return handled || event.keyCode in GUIDE_KEYS
        }
        if (moving) {
            when (event.keyCode) {
                // The list moves the channel; the selection follows it when the list comes back.
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    channel?.let { onReorder?.invoke(it, if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> return true
            }
        }
        // While picking, ◀ goes to the playlists at once, not back through a catch-up channel's past.
        if (selecting && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && onExitLeft != null) {
            if (event.repeatCount == 0) onExitLeft.invoke()
            return true
        }
        val acted = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (row == 0 && onExitUp != null) {
                // Only a fresh press leaves: a held ▲ stops on the first channel.
                if (event.repeatCount == 0) onExitUp.invoke()
                return true
            } else {
                moveRow(-1)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveRow(-PAGE)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveRow(PAGE)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveProgramme(-1, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveProgramme(1, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) longPressed = false
                // Held: a long press, once, instead of playing on release.
                if (event.repeatCount > 0 && handledDown == event.keyCode && onLongPress != null && !longPressed) {
                    longPressed = true
                    channel?.let(onLongPress)
                }
                event.repeatCount == 0 || handledDown == event.keyCode
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> event.repeatCount == 0 || handledDown == event.keyCode
            else -> false
        }
        if (acted) handledDown = event.keyCode
        return acted
    }

    /** ▲▼ keep the time and the timeline where they are, as TV guides do. */
    private fun moveRow(step: Int): Boolean {
        if (channels.isEmpty()) return true
        row = (row + step).coerceIn(0, channels.size - 1)
        return true
    }

    /**
     * ◀▶: to the programme before or after the selected one, or the half hour where the guide has
     * nothing; the timeline scrolls only as far as it needs to show it.
     */
    private fun moveProgramme(step: Int, repeating: Boolean = false): Boolean {
        val channel = channel ?: return true
        val now = LiveTvClock.nowEpochMs()
        val first = floorSlot(now - maxOf(windowPastMs(channel.catchup?.days), LiveTvArchive.pastMsFor(channel.catchup?.days)))
        // Going back in time, the channel's older days are read in ([LiveTvRepository.requestHistory]).
        val olderDaysPending = step < 0 && channel.catchup != null && !LiveTvRepository.hasHistory(channel)
        if (olderDaysPending) LiveTvRepository.requestHistory(channel)
        val last = now + windowAheadMs()
        val blocks = blocksFor(channel)
        val current = blocks.blockAt(anchorMs)
        val fromPast = current.start <= now
        val next = if (step > 0) blocks.after(current) else blocks.before(current)
        // Back in time, a stretch the guide has nothing for is passed over to the programme before it.
        val target = if (step < 0 && fromPast && next.programme == null) blocks.programmeBefore(current.start) ?: next else next
        val programme = target.programme
        // Embedded: from now (or the past), ◀ goes on into the past only where it can be played again.
        if (step < 0 && onExitLeft != null && fromPast &&
            (programme == null || !LiveTvCatchupLinks.isPlayable(channel.catchup, programme, now))
        ) {
            // Not before its older days were looked for: they may carry on there.
            if (olderDaysPending && current.start > first) return true
            // A held ◀ pauses on what is on now, then goes on to the categories: it never
            // needs letting go and pressing again, which felt stuck.
            if (!repeating || ++heldAtEdge >= EXIT_AFTER_REPEATS) {
                heldAtEdge = 0
                onExitLeft.invoke()
            }
            return true
        }
        heldAtEdge = 0
        // The ends of the time the guide keeps.
        if (if (step > 0) target.start >= last else target.stop <= first) return true
        followsNow = false
        Snapshot.withMutableSnapshot {
            viewStartMs = liveTvGuideViewFor(target, viewStartMs, viewSpanMs, first, last)
            anchorMs = liveTvGuideAnchorFor(target, viewStartMs)
        }
        return true
    }

    internal companion object {
        const val PAGE = 6
        const val EXIT_AFTER_REPEATS = 6
        /** Channels whose laid-out programmes are kept: a page or two of rows either side. */
        const val BLOCK_CACHE_SIZE = 64
        val GUIDE_KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
        )

        fun floorSlot(ms: Long): Long = guideFloorSlot(ms)
        fun windowPastMs(catchupDays: Int?): Long = LiveTvRepository.guideWindow.pastMsFor(catchupDays)
        fun windowAheadMs(): Long = LiveTvRepository.guideWindow.aheadMs
    }
}

/**
 * The time ruler, the channel rows with their programmes and the line at now. [active] is whether
 * the selection is shown as focused; [corner] fills the space above the channel names.
 */
@Composable
internal fun LiveTvGuideGrid(
    state: LiveTvGuideState,
    active: Boolean,
    clock: State<Long>,
    modifier: Modifier = Modifier,
    channelColumn: Dp = CHANNEL_COLUMN,
    rowHeight: Dp = ROW_HEIGHT,
    corner: @Composable () -> Unit = {},
    rulerHeight: Dp = 28.dp,
) {
    val liveState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val span = ((maxWidth - channelColumn) / MINUTE_WIDTH).toLong().coerceAtLeast(60) * GUIDE_MINUTE
        state.viewSpanMs = span
        // The timeline glides to its new place; programmes are placed at layout, not recomposed per
        // frame. It moves as an offset from a fixed time: epoch milliseconds in a Float would be
        // rounded to two minutes.
        val timeline = remember { GuideTimeline(Snapshot.withoutReadObservation { state.viewStartMs }) }
        timeline.pxPerMs = with(density) { MINUTE_WIDTH.toPx() } / GUIDE_MINUTE
        val timelineScope = rememberCoroutineScope()
        LaunchedEffect(timeline, span) {
            snapshotFlow { state.viewStartMs }.collect { start ->
                val to = (start - timeline.origin).toFloat()
                val scroll = timeline.scroll
                // Further than a screen: straight there, as nothing on the way would be worth seeing.
                if (abs(to - scroll.value) > span) {
                    timelineScope.launch { scroll.snapTo(to) }
                } else {
                    // Read now, while a glide still runs: it carries on at the speed it had.
                    val velocity = glideVelocity(scroll.velocity, scroll.value, to)
                    timelineScope.launch { scroll.animateTo(to, GUIDE_GLIDE, velocity) }
                }
            }
        }
        // The rows hold the programmes of a stretch around the view, moved on in steps, so a step
        // along the timeline recomposes nothing and a glide (never more than a screen) never runs
        // out of programmes.
        val margin = guideCeilSlot(span) + GUIDE_SLOT
        val step by remember(state) { derivedStateOf { Math.floorDiv(state.viewStartMs, WINDOW_STEP) } }
        val windowStart = step * WINDOW_STEP - margin
        val windowEnd = step * WINDOW_STEP + WINDOW_STEP + span + margin
        Column {
            Row(modifier = Modifier.fillMaxWidth().height(rulerHeight), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(channelColumn).padding(start = 8.dp)) { corner() }
                TimeRuler(windowStart, windowEnd, timeline, clock, modifier = Modifier.weight(1f))
            }
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = remember { Snapshot.withoutReadObservation { (state.row - 2).coerceAtLeast(0) } },
            )
            // The list glides so the selection rests a little above the middle, as Nuvio's rows do.
            // One animation carries on through each step, keeping its speed, so holding ▲▼ is a
            // smooth run rather than a series of starts. Read here, not in composition: moving the
            // selection recomposes only the rows it leaves and enters.
            val fallbackRowPx = with(density) { rowHeight.roundToPx() }
            val glide = remember { Animatable(0f) }
            val glideScope = rememberCoroutineScope()
            LaunchedEffect(state, listState, fallbackRowPx) {
                var shownChannels: List<LiveTvChannel>? = null
                snapshotFlow { state.row to state.channels }.collect { (row, channels) ->
                    val changed = shownChannels !== channels
                    shownChannels = channels
                    if (channels.isEmpty()) return@collect
                    val info = listState.layoutInfo
                    // Measured, not worked out from dp: a row is a whole number of pixels.
                    val rowPx = (info.visibleItemsInfo.firstOrNull()?.size ?: fallbackRowPx).coerceAtLeast(1).toFloat()
                    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                    val fullRows = if (viewport > 0f) (viewport / rowPx).toInt().coerceAtLeast(1) else GUIDE_PAGE_ROWS
                    val anchorRows = (fullRows * SELECTION_AT).toInt()
                    // Never past where the list can go, so the glide always lands.
                    val lastFirst = (channels.size - fullRows).coerceAtLeast(0)
                    val targetIndex = (row - anchorRows).coerceIn(0, lastFirst)
                    val target = targetIndex * rowPx
                    val current = listState.firstVisibleItemIndex * rowPx + listState.firstVisibleItemScrollOffset
                    // A jump (another list, a long page, back from the player) lands at once.
                    if (changed || info.visibleItemsInfo.isEmpty() || abs(target - current) > viewport * 1.5f) {
                        glideScope.launch {
                            glide.stop()
                            listState.scrollToItem(targetIndex)
                            glide.snapTo(target)
                        }
                        return@collect
                    }
                    val running = glide.isRunning
                    val from = if (running) glide.value else current
                    val velocity = if (running) glideVelocity(glide.velocity, from, target) else 0f
                    glideScope.launch {
                        if (!running) glide.snapTo(from)
                        glide.animateTo(target, GUIDE_GLIDE, velocity) {
                            val now = listState.firstVisibleItemIndex * rowPx + listState.firstVisibleItemScrollOffset
                            listState.dispatchRawDelta(value - now)
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, userScrollEnabled = false, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.channels, key = { _, channel -> channel.id }, contentType = { _, _ -> "guideRow" }) { index, channel ->
                        val selectedRow = index == state.row
                        // Scrolled back past what is kept in memory: the channel's older days are read in.
                        if (channel.catchup != null && windowStart < LiveTvClock.nowEpochMs() - LiveTvGuideState.windowPastMs(channel.catchup.days)) {
                            LaunchedEffect(channel.guideKey) { LiveTvRepository.requestHistory(channel) }
                        }
                        GuideRow(
                            channel = channel,
                            logo = liveState.logoFor(channel),
                            isFavorite = channel.streamUrl in liveState.favoriteUrls,
                            picked = if (state.selecting) channel.streamUrl in state.picked else null,
                            moving = state.moving && selectedRow,
                            selectedRow = selectedRow,
                            // The guide's blocks are read again when it changes.
                            blocks = remember(channel.guideKey, liveState.guideVersion) { state.blocksFor(channel) },
                            selectedBlock = if (selectedRow && active) state.selectedBlock() else null,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            timeline = timeline,
                            clock = clock,
                            channelColumn = channelColumn,
                            rowHeight = rowHeight,
                        )
                    }
                }
                // Now, as a thin line through the rows.
                Box(
                    modifier = Modifier
                        .padding(start = channelColumn)
                        .fillMaxSize()
                        .clipToBounds()
                        .drawBehind {
                            val x = timeline.x(clock.value).toFloat()
                            if (x in 0f..size.width) {
                                drawLine(NOW_LINE, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                            }
                        },
                )
            }
        }
    }
}

/**
 * Where the timeline is: [scroll] is the left edge's offset from [origin], in ms. Positions are
 * rounded once from the timeline's start, so cells next to each other always meet the same way.
 */
@Stable
private class GuideTimeline(val origin: Long) {
    var pxPerMs = 0f
    val scroll: Animatable<Float, AnimationVector1D> = Animatable(0f)

    /** Where [epochMs] lies along the whole timeline, in px. */
    fun px(epochMs: Long): Int = ((epochMs - origin).toFloat() * pxPerMs).roundToInt()

    /** The x position of [epochMs] in the view now, in px. Read at layout or draw time. */
    fun x(epochMs: Long): Int = px(epochMs) - (scroll.value * pxPerMs).roundToInt()
}

@Composable
private fun TimeRuler(
    windowStart: Long,
    windowEnd: Long,
    timeline: GuideTimeline,
    clock: State<Long>,
    modifier: Modifier = Modifier,
) {
    val slots = remember(windowStart, windowEnd) {
        val first = guideCeilSlot(windowStart)
        LongArray(((windowEnd - first) / GUIDE_SLOT + 1).toInt().coerceAtLeast(0)) { first + it * GUIDE_SLOT }
    }
    Box(modifier = modifier.fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterStart) {
        for (slot in slots) {
            key(slot) {
                Text(
                    text = LiveTvClock.formatClock(slot),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = if (slot <= clock.value && clock.value < slot + GUIDE_SLOT) 0.9f else 0.5f),
                    maxLines = 1,
                    // Placed at layout: the ruler moves with the rows without recomposing.
                    modifier = Modifier.offset { IntOffset(timeline.x(slot) + 8, 0) },
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: LiveTvChannel,
    logo: String?,
    isFavorite: Boolean,
    /** While picking channels: whether this one is ticked; null otherwise. */
    picked: Boolean?,
    /** Being moved within the playlist. */
    moving: Boolean,
    selectedRow: Boolean,
    blocks: List<LiveTvGuideBlock>,
    /** The selected block when this row has the selection and the guide is focused. */
    selectedBlock: LiveTvGuideBlock?,
    windowStart: Long,
    windowEnd: Long,
    timeline: GuideTimeline,
    clock: State<Long>,
    channelColumn: Dp,
    rowHeight: Dp,
) {
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight).padding(vertical = 3.dp)) {
        val rowHighlight by animateColorAsState(if (selectedRow) CHANNEL_SELECTED else Color.Transparent, SELECTION_FADE, label = "guideRow")
        Row(
            modifier = Modifier
                .width(channelColumn)
                .fillMaxHeight()
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .drawBehind { drawRect(rowHighlight) }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (moving) {
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = Color.White, modifier = Modifier.padding(end = 6.dp).size(18.dp))
            }
            if (picked != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (picked) Color.White else Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                }
            }
            LiveTvLogo(url = logo, name = channel.name, width = 48.dp, height = 30.dp)
            Text(
                text = channel.name,
                style = if (rowHeight >= 48.dp) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (selectedRow) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White.copy(alpha = if (selectedRow) 1f else 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            if (isFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = stringResource(R.string.live_tv_favorites),
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 4.dp).size(14.dp),
                )
            }
            if (channel.catchup != null) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = stringResource(R.string.live_tv_catchup),
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                )
            }
        }
        val cells = remember(blocks, windowStart, windowEnd) { blocks.cellsIn(windowStart, windowEnd) }
        GuideCells(
            cells = cells,
            channel = channel,
            selectedBlock = selectedBlock,
            windowStart = windowStart,
            windowEnd = windowEnd,
            timeline = timeline,
            clock = clock,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/**
 * A row's programmes, each placed by its time: measured once at its width, moved at layout as the
 * timeline glides. Blocks never overlap, so neither do cells; a short one selected grows to show
 * its title and pushes the rest of the row along.
 */
@Composable
private fun GuideCells(
    cells: List<LiveTvGuideCell>,
    channel: LiveTvChannel,
    selectedBlock: LiveTvGuideBlock?,
    windowStart: Long,
    windowEnd: Long,
    timeline: GuideTimeline,
    clock: State<Long>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { CELL_GAP.roundToPx() }
    val noGuide = stringResource(R.string.live_tv_guide_none)
    // Where the guide has nothing, the stretch is one cell; the half hour of it selected is drawn on it.
    val overlay = selectedBlock?.takeIf { it.programme == null }
        ?.let { LiveTvGuideCell(it, maxOf(it.start, windowStart), minOf(it.stop, windowEnd)) }
        ?.takeIf { it.stop > it.start }
    val selectedIndex = if (selectedBlock == null || overlay != null) -1 else cells.indexOfFirst { it.block == selectedBlock }
    val now = clock.value
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            cells.forEachIndexed { index, cell ->
                // Keyed by the block's own start, so a row moved on to later hours keeps the cells
                // it still shows (no colour fading over from the cell that used to be there).
                key(cell.block.start) {
                    val programme = cell.block.programme
                    val cellState = when {
                        programme == null -> GuideCellState.Future
                        // Past programmes the provider keeps can be played again: they stay bright.
                        programme.stopEpochMs <= now ->
                            if (LiveTvCatchupLinks.isPlayable(channel.catchup, programme, now)) GuideCellState.Future else GuideCellState.Past
                        programme.startEpochMs <= now -> GuideCellState.Now
                        else -> GuideCellState.Future
                    }
                    GuideCell(
                        cell = cell,
                        title = programme?.title ?: noGuide,
                        selected = index == selectedIndex,
                        state = cellState,
                        progress = cellState == GuideCellState.Now && cell.start <= now && now < cell.stop,
                        timeline = timeline,
                        gapPx = gapPx,
                        clock = clock,
                    )
                }
            }
            if (overlay != null) {
                key("selection") {
                    GuideCell(
                        cell = overlay,
                        title = noGuide,
                        selected = true,
                        state = GuideCellState.Future,
                        progress = false,
                        timeline = timeline,
                        gapPx = gapPx,
                        clock = clock,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val height = constraints.maxHeight
        // Each cell sizes itself (a short one selected grows); only its height is set here.
        val cellConstraints = Constraints(minHeight = height, maxHeight = height)
        val placeables = measurables.map { it.measure(cellConstraints) }
        layout(constraints.maxWidth, height) {
            // A short cell selected grows to show its title: the cells after it move along by as
            // much (following its grow animation), so it never covers them.
            val pushPx = if (selectedIndex < 0) {
                0
            } else {
                val cell = cells[selectedIndex]
                val timePx = (timeline.px(cell.stop) - timeline.px(cell.start) - gapPx).coerceAtLeast(1)
                (placeables[selectedIndex].width - timePx).coerceAtLeast(0)
            }
            // Read here: the glide moves the cells without measuring or composing them again.
            for (i in placeables.indices) {
                val cell = if (i < cells.size) cells[i] else overlay ?: continue
                val front = i == selectedIndex || i >= cells.size
                val push = if (selectedIndex in 0 until i && i < cells.size) pushPx else 0
                placeables[i].place(timeline.x(cell.start) + push, 0, if (front) 1f else 0f)
            }
        }
    }
}

private enum class GuideCellState { Past, Now, Future }

/**
 * A cell's width. A short one, selected, grows to show its title, pushing its neighbours along (up to
 * [GROW_MAX]) and settles back when the selection moves on; only cells that short can animate.
 */
private fun Modifier.cellWidth(width: Dp, selected: Boolean): Modifier =
    if (width >= GROW_MAX) {
        width(width)
    } else {
        // The same chain either way, so the size animation carries on from one to the other.
        zIndex(if (selected) 1f else 0f)
            .animateContentSize(GROW_SPRING)
            .then(if (selected) Modifier.widthIn(min = width, max = GROW_MAX) else Modifier.width(width))
    }

private val GROW_MAX = 280.dp
private val GROW_SPRING = spring<IntSize>(dampingRatio = 1f, stiffness = 500f)

/** How far down the guide the selected row rests. */
private const val SELECTION_AT = 0.4f
/** Rows in a page before the list has been laid out. */
private const val GUIDE_PAGE_ROWS = 6
/** The rows' programmes move on in steps of this much timeline. */
private const val WINDOW_STEP = 4 * GUIDE_SLOT
/** The guide's glide, for rows and the timeline: quick, and settling without a bounce. */
private val GUIDE_GLIDE = spring<Float>(dampingRatio = 1f, stiffness = 320f)

/**
 * The speed a glide to [to] starts with: what it had, but never so much towards [to] that it
 * would pass it and come back (a critically damped spring does when its speed tops ω × distance),
 * so letting go of a held ▲▼ lands on the row without a wobble.
 */
private fun glideVelocity(velocity: Float, from: Float, to: Float): Float {
    val distance = to - from
    if (distance * velocity <= 0f) return velocity
    val limit = GLIDE_OMEGA * abs(distance)
    return velocity.coerceIn(-limit, limit)
}

/** [GUIDE_GLIDE]'s natural frequency, √stiffness. */
private val GLIDE_OMEGA = kotlin.math.sqrt(320f)
private val SELECTION_FADE = tween<Color>(durationMillis = 140, easing = FastOutSlowInEasing)
private val SELECTION_FADE_FLOAT = tween<Float>(durationMillis = 140, easing = FastOutSlowInEasing)

@Composable
private fun GuideCell(
    cell: LiveTvGuideCell,
    title: String,
    selected: Boolean,
    state: GuideCellState,
    /** On now: its progress shows as a line along the bottom, up to now. */
    progress: Boolean,
    timeline: GuideTimeline,
    gapPx: Int,
    clock: State<Long>,
) {
    val density = LocalDensity.current
    // The width its time takes, less the gap to the next cell; worked out from the timeline's
    // start so neighbours meet exactly.
    val timePx = (timeline.px(cell.stop) - timeline.px(cell.start) - gapPx).coerceAtLeast(1)
    val width = with(density) { timePx.toDp() }
    val shape = RoundedCornerShape(8.dp)
    // The selection fades from cell to cell; drawn at draw time, so the fade recomposes nothing.
    val selection by animateFloatAsState(if (selected) 1f else 0f, SELECTION_FADE_FLOAT, label = "guideCell")
    val fill = when (state) {
        GuideCellState.Now -> CELL_NOW
        GuideCellState.Past -> CELL_PAST
        GuideCellState.Future -> CELL
    }
    val text = if (state == GuideCellState.Past) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.9f)
    // A programme that began before the view keeps its title in view, marked ‹ as TV guides do;
    // never pushed so far that none of it shows.
    val maxShift = (timePx - with(density) { 48.dp.roundToPx() }).coerceAtLeast(0)
    val titleShift = { (-timeline.x(cell.start)).coerceIn(0, maxShift) }
    Box(
        modifier = Modifier
            .cellWidth(width, selected)
            .fillMaxHeight()
            .clip(shape)
            .drawBehind { drawRect(lerp(fill, Color.White, selection)) }
            .then(
                if (progress) {
                    // Read at draw time: the minute tick redraws it without recomposing. It runs
                    // along the cell's time only, also while the cell is grown.
                    Modifier.drawBehind {
                        val span = (cell.stop - cell.start).coerceAtLeast(1L)
                        val fraction = ((clock.value - cell.start).toFloat() / span).coerceIn(0f, 1f)
                        val height = 3.dp.toPx()
                        val line = lerp(Color.White.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.5f), selection)
                        drawRect(line, topLeft = Offset(0f, size.height - height), size = Size(timePx.coerceAtMost(size.width.toInt()) * fraction, height))
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val style = MaterialTheme.typography.bodySmall
        BasicText(
            // Drawn only while the title is pushed in from the cell's start (it began earlier).
            text = "‹",
            style = style,
            color = { lerp(Color.White.copy(alpha = 0.6f), Color.Black, selection) },
            modifier = Modifier
                .offset { IntOffset(titleShift(), 0) }
                .padding(start = 6.dp)
                .graphicsLayer { alpha = if (titleShift() > 0) 1f else 0f },
        )
        BasicText(
            text = title,
            style = style.copy(fontWeight = if (selected || state == GuideCellState.Now) FontWeight.Medium else FontWeight.Normal),
            color = { lerp(text, Color.Black, selection) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset { IntOffset(titleShift(), 0) }
                .padding(start = 16.dp, end = 10.dp),
        )
    }
}
