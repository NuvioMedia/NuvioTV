@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.data.iptv.IptvGroup
import com.nuvio.tv.data.iptv.IptvGroupIds
import com.nuvio.tv.data.iptv.IptvLoadStage
import com.nuvio.tv.data.iptv.IptvLoadStatus
import com.nuvio.tv.data.iptv.IptvProgramme
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val GUIDE_SPAN_MS = 2 * IPTV_HOUR_MS
private const val GUIDE_HORIZON_MS = 3L * 24 * IPTV_HOUR_MS
private const val REVEAL_MARGIN_MS = 10L * 60 * 1000
private val CHANNEL_COLUMN_WIDTH = 230.dp
private val ROW_HEIGHT = 58.dp
private val RULER_HEIGHT = 34.dp
private val GUIDE_START_PADDING = 32.dp

internal data class EpgSegment(
    val start: Long,
    val end: Long,
    val programme: IptvProgramme?
)

internal fun buildEpgSegments(programmes: List<IptvProgramme>?, from: Long, to: Long): List<EpgSegment> {
    val list = programmes.orEmpty()
    val result = ArrayList<EpgSegment>()
    var low = 0
    var high = list.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (list[mid].end <= from) low = mid + 1 else high = mid
    }
    var index = low
    var cursor = from
    while (cursor < to) {
        val programme = list.getOrNull(index)
        if (programme != null && programme.end <= cursor) {
            index++
            continue
        }
        if (programme != null && programme.start <= cursor) {
            result.add(EpgSegment(programme.start, programme.end, programme))
            cursor = programme.end
            index++
        } else {
            val gapEnd = min(programme?.start ?: to, to)
            var start = cursor
            while (start < gapEnd) {
                val end = min(floorLocal(start, IPTV_HOUR_MS) + IPTV_HOUR_MS, gapEnd)
                result.add(EpgSegment(start, end, null))
                start = end
            }
            cursor = gapEnd
        }
    }
    return result
}

private enum class GuideFocusArea { PROGRAMMES, CHANNELS }

private fun digitFor(keyCode: Int): Int? = when (keyCode) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> keyCode - AndroidKeyEvent.KEYCODE_0
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

@Composable
internal fun groupLabel(group: IptvGroup?): String = when (group?.id) {
    null, IptvGroupIds.ALL -> stringResource(R.string.iptv_group_all)
    IptvGroupIds.FAVORITES -> stringResource(R.string.iptv_group_favorites)
    IptvGroupIds.RECENT -> stringResource(R.string.iptv_group_recent)
    else -> group.label
}

@Composable
internal fun IptvGuide(
    channels: List<IptvChannel>,
    epg: Map<String, List<IptvProgramme>>,
    groups: List<IptvGroup>,
    selectedGroupId: String,
    favorites: Set<String>,
    previewChannel: IptvChannel?,
    focusChannelKey: String?,
    status: IptvLoadStatus,
    previewPlayer: ExoPlayer,
    externalDialogOpen: Boolean,
    onSelectGroup: (String) -> Unit,
    onPreviewChannel: (IptvChannel) -> Unit,
    onPlayChannel: (IptvChannel) -> Unit,
    onToggleFavorite: (IptvChannel) -> Unit,
    onManagePlaylists: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val formatter = remember(context) { IptvTimeFormatter(context) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val liveFloor = floorLocal(now, IPTV_HALF_HOUR_MS)

    var windowStart by remember { mutableLongStateOf(liveFloor) }
    var cursorTime by remember { mutableLongStateOf(now) }
    var focusArea by remember { mutableStateOf(GuideFocusArea.PROGRAMMES) }
    var selectedKey by remember { mutableStateOf(focusChannelKey) }
    var selectedRowHint by remember { mutableIntStateOf(0) }
    var groupsOpen by remember { mutableStateOf(false) }
    var optionsChannel by remember { mutableStateOf<IptvChannel?>(null) }
    var numberInput by remember { mutableStateOf("") }
    var centerPressed by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }

    LaunchedEffect(liveFloor) {
        if (windowStart < liveFloor) windowStart = liveFloor
    }

    val selectedRow = remember(channels, selectedKey, selectedRowHint) {
        val hinted = channels.getOrNull(selectedRowHint)
        if (hinted != null && hinted.key == selectedKey) {
            selectedRowHint
        } else {
            val index = channels.indexOfFirst { it.key == selectedKey }
            if (index >= 0) index else selectedRowHint.coerceIn(0, max(0, channels.lastIndex))
        }
    }

    LaunchedEffect(selectedGroupId) {
        val key = previewChannel?.key ?: focusChannelKey
        val index = channels.indexOfFirst { it.key == key }
        selectedRowHint = max(0, index)
        selectedKey = channels.getOrNull(max(0, index))?.key
        focusArea = GuideFocusArea.PROGRAMMES
        cursorTime = System.currentTimeMillis()
        windowStart = floorLocal(cursorTime, IPTV_HALF_HOUR_MS)
    }

    val segmentCache = remember(epg, liveFloor) { HashMap<String, List<EpgSegment>>() }
    fun segmentsFor(channel: IptvChannel): List<EpgSegment> =
        segmentCache.getOrPut(channel.key) {
            buildEpgSegments(epg[channel.key], liveFloor, liveFloor + GUIDE_HORIZON_MS)
        }

    val effectiveCursor = if (focusArea == GuideFocusArea.CHANNELS) now else max(cursorTime, now)
    fun segmentAt(segments: List<EpgSegment>, time: Long): Int =
        segments.indexOfFirst { time < it.end }.let { if (it < 0) segments.lastIndex else it }

    val selectedChannel = channels.getOrNull(selectedRow)
    val selectedSegments = selectedChannel?.let { segmentsFor(it) }.orEmpty()
    val selectedSegmentIndex = segmentAt(selectedSegments, effectiveCursor)
    val selectedSegment = selectedSegments.getOrNull(selectedSegmentIndex)

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(0, selectedRow - 2))
    var visibleRows by remember { mutableIntStateOf(6) }

    LaunchedEffect(selectedRow, visibleRows, channels.size) {
        if (channels.isEmpty()) return@LaunchedEffect
        val first = listState.firstVisibleItemIndex
        when {
            selectedRow < first -> listState.scrollToItem(selectedRow)
            selectedRow >= first + visibleRows -> listState.scrollToItem(max(0, selectedRow - visibleRows + 1))
        }
    }

    LaunchedEffect(numberInput) {
        if (numberInput.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val target = numberInput.toIntOrNull()
        val index = channels.indexOfFirst { it.number == target }
        if (index >= 0) {
            selectedKey = channels[index].key
            selectedRowHint = index
            focusArea = GuideFocusArea.PROGRAMMES
            cursorTime = System.currentTimeMillis()
            windowStart = floorLocal(cursorTime, IPTV_HALF_HOUR_MS)
        }
        numberInput = ""
    }

    val gridFocusRequester = remember { FocusRequester() }
    val groupsFocusRequester = remember { FocusRequester() }
    LaunchedEffect(groupsOpen, optionsChannel == null, externalDialogOpen) {
        if (externalDialogOpen || optionsChannel != null) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (groupsOpen) {
            runCatching { groupsFocusRequester.requestFocus() }
        } else {
            runCatching { gridFocusRequester.requestFocus() }
        }
    }
    BackHandler(enabled = groupsOpen) { groupsOpen = false }

    val currentChannels by rememberUpdatedState(channels)
    val currentPreview by rememberUpdatedState(previewChannel)

    fun currentRow(): Int {
        val list = currentChannels
        if (list.isEmpty()) return 0
        val hinted = list.getOrNull(selectedRowHint)
        if (hinted != null && hinted.key == selectedKey) return selectedRowHint
        val index = list.indexOfFirst { it.key == selectedKey }
        return if (index >= 0) index else selectedRowHint.coerceIn(0, list.lastIndex)
    }

    fun selectRow(index: Int) {
        val list = currentChannels
        if (list.isEmpty()) return
        val clamped = index.coerceIn(0, list.lastIndex)
        selectedRowHint = clamped
        selectedKey = list[clamped].key
    }

    fun currentSegments(): List<EpgSegment> =
        currentChannels.getOrNull(currentRow())?.let { segmentsFor(it) }.orEmpty()

    fun moveRight() {
        val time = System.currentTimeMillis()
        val floor = floorLocal(time, IPTV_HALF_HOUR_MS)
        if (focusArea == GuideFocusArea.CHANNELS) {
            focusArea = GuideFocusArea.PROGRAMMES
            cursorTime = time
            windowStart = floor
            return
        }
        val segments = currentSegments()
        val index = segmentAt(segments, max(cursorTime, time))
        val next = segments.getOrNull(index + 1) ?: return
        if (next.start >= floor + GUIDE_HORIZON_MS - GUIDE_SPAN_MS) return
        cursorTime = next.start
        if (next.start >= windowStart + GUIDE_SPAN_MS - REVEAL_MARGIN_MS) {
            windowStart = max(floor, floorLocal(next.start, IPTV_HALF_HOUR_MS))
        }
    }

    fun moveLeft() {
        val time = System.currentTimeMillis()
        val floor = floorLocal(time, IPTV_HALF_HOUR_MS)
        if (focusArea == GuideFocusArea.CHANNELS) {
            groupsOpen = true
            return
        }
        val segments = currentSegments()
        val index = segmentAt(segments, max(cursorTime, time))
        val current = segments.getOrNull(index)
        val previous = segments.getOrNull(index - 1)
        if (current == null || current.start <= time || previous == null || previous.end <= time) {
            focusArea = GuideFocusArea.CHANNELS
            windowStart = floor
            return
        }
        cursorTime = max(previous.start, time)
        if (previous.start < windowStart || previous.end <= windowStart + REVEAL_MARGIN_MS) {
            windowStart = max(floor, floorLocal(previous.start, IPTV_HALF_HOUR_MS))
        }
    }

    fun activate() {
        val channel = currentChannels.getOrNull(currentRow()) ?: return
        if (currentPreview?.key == channel.key) onPlayChannel(channel) else onPreviewChannel(channel)
    }

    val groupLabelText = groupLabel(groups.firstOrNull { it.id == selectedGroupId })

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GuideInfoHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.42f),
                groupLabel = groupLabelText,
                channelCount = channels.size,
                channel = selectedChannel,
                segment = selectedSegment,
                now = now,
                status = status,
                formatter = formatter,
                isFavorite = selectedChannel?.key in favorites,
                previewChannel = previewChannel,
                previewPlayer = previewPlayer
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.58f)
                    .padding(start = GUIDE_START_PADDING)
            ) {
                val totalWidthPx = constraints.maxWidth.toFloat()
                val channelColumnPx = with(density) { CHANNEL_COLUMN_WIDTH.toPx() }
                val programmesWidthPx = (totalWidthPx - channelColumnPx).coerceAtLeast(1f)
                val pxPerMs = programmesWidthPx / GUIDE_SPAN_MS
                val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }
                val rulerHeightPx = with(density) { RULER_HEIGHT.toPx() }
                val rows = ((constraints.maxHeight - rulerHeightPx) / rowHeightPx).toInt().coerceAtLeast(1)
                LaunchedEffect(rows) { visibleRows = rows }

                Column(modifier = Modifier.fillMaxSize()) {
                    TimeRuler(
                        windowStart = windowStart,
                        now = now,
                        pxPerMs = pxPerMs,
                        formatter = formatter
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .focusRequester(gridFocusRequester)
                            .onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                val keyCode = native.keyCode
                                val isSelect = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                                if (native.action == AndroidKeyEvent.ACTION_UP) {
                                    if (isSelect) {
                                        if (centerPressed && !longPressHandled) activate()
                                        centerPressed = false
                                        longPressHandled = false
                                        return@onPreviewKeyEvent true
                                    }
                                    return@onPreviewKeyEvent false
                                }
                                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                                if (isSelect) {
                                    if (native.repeatCount == 0) {
                                        centerPressed = true
                                        longPressHandled = false
                                    } else if (centerPressed && !longPressHandled) {
                                        longPressHandled = true
                                        currentChannels.getOrNull(currentRow())?.let { optionsChannel = it }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                val digit = digitFor(keyCode)
                                if (digit != null) {
                                    if (numberInput.length < 5) numberInput += digit.toString()
                                    return@onPreviewKeyEvent true
                                }
                                when (keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                        selectRow(currentRow() - 1)
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                        selectRow(currentRow() + 1)
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_PAGE_UP,
                                    AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
                                        selectRow(currentRow() - visibleRows)
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_PAGE_DOWN,
                                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                                        selectRow(currentRow() + visibleRows)
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        moveRight()
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                        moveLeft()
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_MENU -> {
                                        groupsOpen = true
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY,
                                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                        currentChannels.getOrNull(currentRow())?.let(onPlayChannel)
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .focusable()
                    ) {
                        if (channels.isEmpty()) {
                            Text(
                                text = stringResource(R.string.iptv_no_channels_in_group),
                                style = MaterialTheme.typography.bodyLarge,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                userScrollEnabled = false,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(channels, key = { _, channel -> channel.key }) { index, channel ->
                                    GuideRow(
                                        channel = channel,
                                        segments = segmentsFor(channel),
                                        isSelectedRow = index == selectedRow,
                                        channelFocused = index == selectedRow && focusArea == GuideFocusArea.CHANNELS,
                                        selectedSegment = if (index == selectedRow && focusArea == GuideFocusArea.PROGRAMMES) selectedSegment else null,
                                        isPlaying = previewChannel?.key == channel.key,
                                        isFavorite = channel.key in favorites,
                                        windowStart = windowStart,
                                        now = now,
                                        pxPerMs = pxPerMs,
                                        formatter = formatter
                                    )
                                }
                            }
                        }
                    }
                }

                val nowX = channelColumnPx + (now - windowStart) * pxPerMs
                if (now >= windowStart && now < windowStart + GUIDE_SPAN_MS) {
                    val nowXDp = with(density) { nowX.toDp() }
                    Box(
                        modifier = Modifier
                            .offset(x = nowXDp - 1.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(NuvioTheme.colors.Secondary)
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = nowXDp - 5.dp, y = RULER_HEIGHT - 10.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(NuvioTheme.colors.Secondary)
                    )
                }

                if (numberInput.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = RULER_HEIGHT + 12.dp, end = 32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NuvioTheme.colors.Modal)
                            .border(2.dp, NuvioTheme.colors.Secondary, RoundedCornerShape(10.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = numberInput,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = groupsOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.fillMaxHeight()
        ) {
            GroupsPanel(
                groups = groups,
                selectedGroupId = selectedGroupId,
                focusRequester = groupsFocusRequester,
                onSelect = { groupId ->
                    groupsOpen = false
                    if (groupId != selectedGroupId) onSelectGroup(groupId)
                },
                onClose = { groupsOpen = false },
                onManagePlaylists = {
                    groupsOpen = false
                    onManagePlaylists()
                },
                onRefresh = {
                    groupsOpen = false
                    onRefresh()
                }
            )
        }
    }

    optionsChannel?.let { channel ->
        ChannelOptionsDialog(
            channel = channel,
            isFavorite = channel.key in favorites,
            onDismiss = { optionsChannel = null },
            onWatch = {
                optionsChannel = null
                onPlayChannel(channel)
            },
            onPreview = {
                optionsChannel = null
                onPreviewChannel(channel)
            },
            onToggleFavorite = {
                optionsChannel = null
                onToggleFavorite(channel)
            }
        )
    }
}

@Composable
private fun GuideInfoHeader(
    modifier: Modifier,
    groupLabel: String,
    channelCount: Int,
    channel: IptvChannel?,
    segment: EpgSegment?,
    now: Long,
    status: IptvLoadStatus,
    formatter: IptvTimeFormatter,
    isFavorite: Boolean,
    previewChannel: IptvChannel?,
    previewPlayer: ExoPlayer
) {
    val programme = segment?.programme
    Row(
        modifier = modifier.padding(start = GUIDE_START_PADDING, end = 32.dp, top = 24.dp, bottom = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = groupLabel.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = "  ·  " + stringResource(R.string.iptv_channels_count, channelCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1
                )
                if (status is IptvLoadStatus.Loading) {
                    Text(
                        text = "  ·  " + stringResource(
                            if (status.stage == IptvLoadStage.EPG) R.string.iptv_updating_guide else R.string.iptv_updating_channels
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 1
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (channel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IptvChannelLogo(logo = channel.logo, name = channel.name, size = 40.dp)
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "${channel.number}",
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextTertiary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isFavorite) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = NuvioTheme.colors.Rating,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = programme?.title ?: stringResource(R.string.iptv_no_information),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (segment != null && programme != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val isLive = now in segment.start until segment.end
                    val dayOffset = formatter.dayOffset(segment.start, now)
                    val dayPrefix = when (dayOffset) {
                        0 -> ""
                        1 -> stringResource(R.string.iptv_tomorrow) + ", "
                        else -> formatter.day(segment.start) + ", "
                    }
                    val minutes = if (isLive) {
                        ((segment.end - now) / 60_000L).toInt().coerceAtLeast(1)
                    } else {
                        ((segment.start - now) / 60_000L).toInt().coerceAtLeast(1)
                    }
                    val relative = if (isLive) {
                        stringResource(R.string.iptv_time_left, minutes)
                    } else if (minutes < 120) {
                        stringResource(R.string.iptv_starts_in, minutes)
                    } else {
                        null
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isLive) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NuvioTheme.colors.Secondary)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.iptv_live_badge),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = NuvioTheme.colors.OnSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = dayPrefix + formatter.range(segment.start, segment.end) +
                                (relative?.let { "  ·  $it" } ?: "") +
                                (programme.category?.let { "  ·  $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isLive) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val progress = ((now - segment.start).toFloat() / (segment.end - segment.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .width(280.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(NuvioTheme.colors.SurfaceVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .background(NuvioTheme.colors.Secondary)
                            )
                        }
                    }
                    val details = listOfNotNull(programme.subtitle, programme.description)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    if (details.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(28.dp))
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = formatter.time(now),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(10.dp))
            ) {
                IptvPreviewSurface(
                    player = previewPlayer,
                    channel = previewChannel,
                    modifier = Modifier.fillMaxSize()
                )
                if (previewChannel == null) {
                    Icon(
                        imageVector = Icons.Filled.Tv,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Center)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${previewChannel.number}  ${previewChannel.name}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(
    windowStart: Long,
    now: Long,
    pxPerMs: Float,
    formatter: IptvTimeFormatter
) {
    val density = LocalDensity.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT)
    ) {
        Box(
            modifier = Modifier
                .width(CHANNEL_COLUMN_WIDTH)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart
        ) {
            val offset = formatter.dayOffset(windowStart, now)
            Text(
                text = when (offset) {
                    0 -> stringResource(R.string.iptv_today)
                    1 -> stringResource(R.string.iptv_tomorrow)
                    else -> formatter.day(windowStart)
                },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            var tick = windowStart
            while (tick < windowStart + GUIDE_SPAN_MS) {
                val x = ((tick - windowStart) * pxPerMs).roundToInt()
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x, 0) }
                        .width(1.dp)
                        .height(10.dp)
                        .align(Alignment.BottomStart)
                        .background(NuvioTheme.colors.Border)
                )
                Text(
                    text = formatter.time(tick),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(x + with(density) { 6.dp.roundToPx() }, 0) }
                )
                tick += IPTV_HALF_HOUR_MS
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: IptvChannel,
    segments: List<EpgSegment>,
    isSelectedRow: Boolean,
    channelFocused: Boolean,
    selectedSegment: EpgSegment?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    windowStart: Long,
    now: Long,
    pxPerMs: Float,
    formatter: IptvTimeFormatter
) {
    val density = LocalDensity.current
    val windowEnd = windowStart + GUIDE_SPAN_MS
    val cellShape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .width(CHANNEL_COLUMN_WIDTH)
                .fillMaxHeight()
                .padding(end = 4.dp)
                .clip(cellShape)
                .background(
                    when {
                        channelFocused -> NuvioTheme.colors.FocusBackground
                        isSelectedRow -> NuvioTheme.colors.SurfaceVariant
                        else -> NuvioTheme.colors.BackgroundCard
                    }
                )
                .then(
                    if (channelFocused) Modifier.border(NuvioTheme.focusRing.border(2.dp), cellShape) else Modifier
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = channel.number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (isPlaying) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextTertiary,
                maxLines = 1,
                modifier = Modifier.width(34.dp)
            )
            IptvChannelLogo(logo = channel.logo, name = channel.name, size = 30.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isSelectedRow || isPlaying) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isPlaying) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = NuvioTheme.colors.Rating,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            segments.forEach { segment ->
                if (segment.end <= windowStart || segment.start >= windowEnd) return@forEach
                val visibleStart = max(segment.start, windowStart)
                val visibleEnd = min(segment.end, windowEnd)
                val x = ((visibleStart - windowStart) * pxPerMs).roundToInt()
                val widthPx = ((visibleEnd - visibleStart) * pxPerMs).roundToInt() - with(density) { 3.dp.roundToPx() }
                if (widthPx <= 0) return@forEach
                val isSelected = selectedSegment != null &&
                    selectedSegment.start == segment.start && selectedSegment.end == segment.end
                val isLive = now in segment.start until segment.end
                val isPast = segment.end <= now
                val background = when {
                    isSelected -> NuvioTheme.colors.FocusBackground
                    isLive && isSelectedRow -> NuvioTheme.colors.SurfaceVariant
                    isLive -> NuvioTheme.colors.Surface
                    isSelectedRow -> NuvioTheme.colors.Surface
                    else -> NuvioTheme.colors.BackgroundCard
                }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x, 0) }
                        .width(with(density) { widthPx.toDp() })
                        .fillMaxHeight()
                        .clip(cellShape)
                        .background(background)
                        .then(
                            if (isSelected) Modifier.border(NuvioTheme.focusRing.border(2.dp), cellShape) else Modifier
                        )
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = (if (segment.start < windowStart) "‹ " else "") +
                                (segment.programme?.title ?: stringResource(R.string.iptv_no_information)),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected || isLive) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = when {
                                isSelected -> NuvioTheme.colors.TextPrimary
                                isPast -> NuvioTheme.colors.TextTertiary
                                segment.programme == null -> NuvioTheme.colors.TextTertiary
                                else -> NuvioTheme.colors.TextPrimary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (segment.programme != null && widthPx > with(density) { 110.dp.toPx() }) {
                            Text(
                                text = formatter.range(segment.start, segment.end),
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.colors.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (isLive) {
                        val progress = ((now - segment.start).toFloat() / (segment.end - segment.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        val fullWidthPx = ((segment.end - segment.start) * pxPerMs)
                        val progressPx = (fullWidthPx * progress - (visibleStart - segment.start) * pxPerMs).coerceIn(0f, widthPx.toFloat())
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .offset(x = (-10).dp)
                                .width(with(density) { progressPx.toDp() })
                                .height(2.dp)
                                .background(NuvioTheme.colors.Secondary.copy(alpha = 0.8f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupsPanel(
    groups: List<IptvGroup>,
    selectedGroupId: String,
    focusRequester: FocusRequester,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    onManagePlaylists: () -> Unit,
    onRefresh: () -> Unit
) {
    val selectedIndex = groups.indexOfFirst { it.id == selectedGroupId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(0, selectedIndex - 3))
    Column(
        modifier = Modifier
            .width(360.dp)
            .fillMaxHeight()
            .background(NuvioTheme.colors.Panel.copy(alpha = 0.97f))
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                    (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                ) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .padding(start = 24.dp, end = 16.dp, top = 28.dp, bottom = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.iptv_groups_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(groups, key = { _, group -> group.id }) { index, group ->
                val icon = when (group.id) {
                    IptvGroupIds.ALL -> Icons.Filled.Apps
                    IptvGroupIds.FAVORITES -> Icons.Filled.Favorite
                    IptvGroupIds.RECENT -> Icons.Filled.History
                    else -> null
                }
                IptvMenuItem(
                    text = groupLabel(group),
                    trailingText = group.count.toString(),
                    icon = icon,
                    selected = group.id == selectedGroupId,
                    onClick = { onSelect(group.id) },
                    modifier = if (index == selectedIndex) Modifier.focusRequester(focusRequester) else Modifier
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NuvioTheme.colors.Divider)
        )
        Spacer(modifier = Modifier.height(8.dp))
        IptvMenuItem(
            text = stringResource(R.string.iptv_manage_playlists),
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            onClick = onManagePlaylists
        )
        IptvMenuItem(
            text = stringResource(R.string.iptv_refresh),
            icon = Icons.Filled.Refresh,
            onClick = onRefresh
        )
    }
}

@Composable
private fun ChannelOptionsDialog(
    channel: IptvChannel,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onWatch: () -> Unit,
    onPreview: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val firstItemFocus = remember { FocusRequester() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = channel.name,
        subtitle = channel.group.takeIf { it.isNotBlank() },
        width = 460.dp
    ) {
        IptvMenuItem(
            text = stringResource(R.string.iptv_watch_fullscreen),
            icon = Icons.Filled.Tv,
            onClick = onWatch,
            modifier = Modifier.focusRequester(firstItemFocus)
        )
        IptvMenuItem(
            text = stringResource(R.string.iptv_play_in_preview),
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            onClick = onPreview
        )
        IptvMenuItem(
            text = stringResource(if (isFavorite) R.string.iptv_remove_favorite else R.string.iptv_add_favorite),
            icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            onClick = onToggleFavorite
        )
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstItemFocus.requestFocus() }
    }
}
