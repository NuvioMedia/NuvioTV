@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvCatchupLinks
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.LiveTvUiState
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One channel's programmes, top to bottom (▶ on a channel in the player's channel list): the
 * kept guide as it is (no copy), opening on what is on now. OK plays the channel, or an ended
 * programme again where the provider keeps it; held OK on the one on now starts it over.
 */
@Composable
internal fun LiveTvProgrammeColumn(
    state: LiveTvPlayerState,
    channel: LiveTvChannel,
    liveState: LiveTvUiState,
    clock: State<Long>,
) {
    // Read again only when the guide is read again.
    val programmes = remember(channel.guideKey, liveState.guideVersion) { LiveTvRepository.schedule(channel.guideKey) }
    val startIndex = remember(programmes) {
        val now = LiveTvClock.nowEpochMs()
        programmes.indexOfFirst { it.stopEpochMs > now }.let { if (it < 0) programmes.lastIndex.coerceAtLeast(0) else it }
    }
    // Each row by its start (and its place among rows starting then), so older days read in
    // above ([LiveTvRepository.requestHistory]) leave the focused row where it is.
    val keys = remember(programmes) {
        var previous = Long.MIN_VALUE
        var same = 0
        programmes.map { programme ->
            same = if (programme.startEpochMs == previous) same + 1 else 0
            previous = programme.startEpochMs
            "${programme.startEpochMs}:$same"
        }
    }
    // The list's rows: a day's label before its first programme (a negative number: -1 - its
    // index in [days]), then the programmes (their index).
    val zone = remember { ZoneId.systemDefault() }
    val layout = remember(programmes) { ProgrammeDays.of(programmes, zone) }
    // A couple of what came before stay in view above what is on now.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (layout.rowOf(startIndex) - 2).coerceAtLeast(0))
    LaunchedEffect(channel.guideKey) { LiveTvRepository.requestHistory(channel) }
    // The programme that has (or is to get) the focus, by its key: it stays the same programme
    // when older days are read in above it.
    // What is on now has it from the first frame, so its row is ready to take the focus at once.
    var focusKey by remember(channel.guideKey) { mutableStateOf(keys.getOrNull(startIndex)) }
    var focusedKey by remember(channel.guideKey) { mutableStateOf<String?>(null) }
    val rowFocus = remember { FocusRequester() }
    val dayFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now(zone).toEpochDay()
    // The day of the programme last focused: the one the day column starts on.
    val focusedDay = focusKey?.let { key -> keys.indexOf(key).takeIf { it >= 0 } }?.let { layout.dayOfProgramme[it] }
        ?: layout.dayOfProgramme.getOrNull(startIndex)
    val hasDays = layout.days.size > 1

    /** Brings [index]'s programme into view, with its day's label when it starts a day, and focuses it. */
    suspend fun focusProgramme(index: Int) {
        val key = keys.getOrNull(index) ?: return
        focusKey = key
        val row = layout.rowOf(index)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == row }) listState.scrollToItem((row - 1).coerceAtLeast(0))
        // The row must be composed (and hold [rowFocus]) before it can take the focus: tried
        // until it has it (the panel's slide in takes a moment), since a request before then does nothing.
        repeat(30) {
            runCatching { rowFocus.requestFocus() }
            delay(16)
            if (focusedKey == key) return
        }
    }
    LaunchedEffect(Unit) {
        if (programmes.isEmpty()) {
            repeat(10) {
                if (runCatching { rowFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(16)
            }
        } else {
            focusProgramme(startIndex)
        }
    }
    // ▶ into the days and ◀ (or Back) out of them.
    LaunchedEffect(state.programmeDaysFocused, hasDays) {
        if (!state.programmeDaysFocused) {
            val index = keys.indexOf(focusKey)
            if (index >= 0 && focusedKey == null) focusProgramme(index)
            return@LaunchedEffect
        }
        if (!hasDays) {
            state.programmeDaysFocused = false
            return@LaunchedEffect
        }
        repeat(10) {
            if (runCatching { dayFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(if (hasDays) 460.dp + DAY_COLUMN_WIDTH else 460.dp)
            .padding(start = 32.dp, end = 40.dp, top = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveTvLogo(url = liveState.logoFor(channel), name = channel.name, width = 64.dp, height = 40.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = liveTvGroupLabel(channel.group, liveState.groupNames),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = stringResource(if (hasDays) R.string.live_tv_player_programmes_days_hint else R.string.live_tv_player_programmes_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        if (programmes.isEmpty()) {
            // Focusable, so the remote stays on the panel: OK plays the channel, ◀ and Back go back.
            Card(
                onClick = { state.pickFromPanel(channel) },
                modifier = Modifier.fillMaxWidth().focusRequester(rowFocus),
                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White.copy(alpha = 0.14f)),
                scale = CardDefaults.scale(focusedScale = 1f),
            ) {
                Text(
                    text = stringResource(R.string.live_tv_info_no_guide),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
            return@Column
        }
        Row(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(
                    count = layout.rows.size,
                    key = { row -> layout.rows[row].let { if (it < 0) "day:${layout.days[-1 - it]}" else keys[it] } },
                    contentType = { row -> if (layout.rows[row] < 0) "day" else "programme" },
                ) { row ->
                    val entry = layout.rows[row]
                    if (entry < 0) {
                        DayLabel(dayLabel(layout.days[-1 - entry], today, zone, long = true))
                        return@items
                    }
                    val programme = programmes[entry]
                    val key = keys[entry]
                    // Read here, so the minute tick recomposes only the rows in view.
                    val now = clock.value
                    val live = programme.startEpochMs <= now && now < programme.stopEpochMs
                    val ended = programme.stopEpochMs <= now
                    val replayable = ended && LiveTvCatchupLinks.isPlayable(channel.catchup, programme, now)
                    val startOver = live && LiveTvCatchupLinks.isPlayable(channel.catchup, programme, now)
                    ProgrammeRow(
                        programme = programme,
                        live = live,
                        dimmed = ended && !replayable,
                        replayable = replayable,
                        clock = clock,
                        onClick = { state.playProgramme(channel, programme) },
                        onLongClick = if (startOver) ({ state.playCatchup(channel, programme) }) else null,
                        modifier = (if (key == focusKey) Modifier.focusRequester(rowFocus) else Modifier)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focusedKey = key
                                    focusKey = key
                                } else if (focusedKey == key) {
                                    focusedKey = null
                                }
                            },
                    )
                }
            }
            if (hasDays) {
                // The days the list has: OK jumps it to that day (to what is on now, for today).
                Column(
                    modifier = Modifier.width(DAY_COLUMN_WIDTH).verticalScroll(rememberScrollState()).padding(start = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    layout.days.forEachIndexed { dayIndex, day ->
                        DayButton(
                            label = dayLabel(day, today, zone, long = false),
                            current = day == focusedDay,
                            onClick = {
                                val target = if (day == today && layout.dayOfProgramme.getOrNull(startIndex) == day) {
                                    startIndex
                                } else {
                                    layout.firstOfDay[dayIndex]
                                }
                                focusKey = keys[target]
                                focusedKey = null
                                state.programmeDaysFocused = false
                                scope.launch { focusProgramme(target) }
                            },
                            modifier = if (day == focusedDay) Modifier.focusRequester(dayFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/** Which day each programme starts on, and the list's rows with a label before each day. */
private class ProgrammeDays(
    /** Each programme's day (epoch day). */
    val dayOfProgramme: LongArray,
    /** The days, earliest first. */
    val days: LongArray,
    /** Each day's first programme. */
    val firstOfDay: IntArray,
    /** The rows: a day's label as -1 - its index in [days], else a programme's index. */
    val rows: IntArray,
    private val rowOfProgramme: IntArray,
) {
    fun rowOf(programme: Int): Int = rowOfProgramme.getOrElse(programme) { 0 }

    companion object {
        fun of(programmes: List<LiveTvProgramme>, zone: ZoneId): ProgrammeDays {
            val dayOf = LongArray(programmes.size)
            val days = ArrayList<Long>()
            val firsts = ArrayList<Int>()
            val rows = ArrayList<Int>(programmes.size + 8)
            val rowOf = IntArray(programmes.size)
            programmes.forEachIndexed { index, programme ->
                val day = Instant.ofEpochMilli(programme.startEpochMs).atZone(zone).toLocalDate().toEpochDay()
                dayOf[index] = day
                if (days.isEmpty() || days.last() != day) {
                    days += day
                    firsts += index
                    rows += -days.size
                }
                rowOf[index] = rows.size
                rows += index
            }
            return ProgrammeDays(dayOf, days.toLongArray(), firsts.toIntArray(), rows.toIntArray(), rowOf)
        }
    }
}

/** "Today", "Yesterday", "Tomorrow", else the day: "Mon, Oct 5" ([long]) or "Mon\nOct 5". */
@Composable
private fun dayLabel(day: Long, today: Long, zone: ZoneId, long: Boolean): String {
    val near = when (day - today) {
        0L -> stringResource(R.string.live_tv_day_today)
        -1L -> stringResource(R.string.live_tv_day_yesterday)
        1L -> stringResource(R.string.live_tv_day_tomorrow)
        else -> null
    }
    val date = remember(day, long) {
        val locale = Locale.getDefault()
        val at = java.util.Date(LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli())
        if (long) {
            java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale).format(at)
        } else {
            java.text.SimpleDateFormat("EEE", locale).format(at) + "\n" +
                java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(at)
        }
    }
    return when {
        near == null -> date
        long -> near
        else -> near + "\n" + date.substringAfter('\n')
    }
}

@Composable
private fun DayLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 1,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DayButton(label: String, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = CardDefaults.colors(
            containerColor = if (current) Color.White.copy(alpha = 0.14f) else Color.Transparent,
            focusedContainerColor = Color.White,
        ),
        scale = CardDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) Color.Black else Color.White.copy(alpha = if (current) 1f else 0.7f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
    }
}

private val DAY_COLUMN_WIDTH = 92.dp

@Composable
private fun ProgrammeRow(
    programme: LiveTvProgramme,
    live: Boolean,
    dimmed: Boolean,
    replayable: Boolean,
    clock: State<Long>,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val text = if (focused) Color.Black else Color.White
    val alpha = if (dimmed && !focused) 0.5f else 1f
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = if (live) Color.White.copy(alpha = 0.10f) else Color.Transparent,
            focusedContainerColor = Color.White,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(spring<IntSize>(stiffness = Spring.StiffnessMediumLow))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = LiveTvClock.formatClock(programme.startEpochMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = text.copy(alpha = (if (focused) 0.65f else 0.6f) * alpha),
                    maxLines = 1,
                    modifier = Modifier.width(TIME_WIDTH),
                )
                Text(
                    text = programme.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                    color = text.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (replayable) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = stringResource(R.string.live_tv_catchup),
                        tint = text.copy(alpha = 0.55f),
                        modifier = Modifier.padding(start = 8.dp).size(16.dp),
                    )
                }
                if (live) {
                    Text(
                        text = stringResource(R.string.live_tv_live_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = if (focused) Color.White else Color.Black,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(LiveTvPillShape)
                            .background(if (focused) Color.Black else Color.White.copy(alpha = 0.9f))
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            if (live) {
                Row(modifier = Modifier.padding(start = TIME_WIDTH, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveTvProgressBar(
                        programme = programme,
                        clock = clock,
                        fill = text,
                        track = text.copy(alpha = 0.15f),
                        modifier = Modifier.width(140.dp),
                    )
                    Text(
                        text = liveTvTimeLeft(programme, clock),
                        style = MaterialTheme.typography.labelSmall,
                        color = text.copy(alpha = 0.55f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            // The description, where the guide kept one, only on the focused row.
            if (focused) {
                programme.description?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black.copy(alpha = 0.65f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = TIME_WIDTH, top = 4.dp),
                    )
                }
            }
        }
    }
}

private val TIME_WIDTH = 96.dp
