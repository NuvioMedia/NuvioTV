@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import kotlinx.coroutines.delay

@Composable
fun LiveTvScreen(onPlayChannel: (Int) -> Unit) {
    val context = rememberAppContext()
    LaunchedEffect(Unit) { LiveTvRepository.ensureLoaded(context) }
    val state by LiveTvRepository.state.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    var server by rememberSaveable { mutableStateOf<String?>(null) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedIndex by remember { mutableIntStateOf(LiveTvRepository.lastChannelIndex()) }
    var showManager by remember { mutableStateOf(false) }
    // null = closed; a source with a fresh id = adding a new one
    var editing by remember { mutableStateOf<LiveSource?>(null) }

    // Forget a selected server once it has been deleted.
    if (server != null && state.sources.none { it.id == server }) server = null

    val inServer = remember(state.channels, server) {
        if (server == null) state.channels else state.channels.filter { it.sourceId == server }
    }
    val groups = remember(inServer) { inServer.map { it.group }.filter { it.isNotBlank() }.distinct() }
    if (group != null && group !in groups) group = null
    val visible = remember(inServer, group) {
        if (group == null) inServer else inServer.filter { it.group == group }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LiveColors.Background)
            .padding(start = 40.dp, end = 34.dp, top = 26.dp)
    ) {
        Text(
            text = stringResource(R.string.live_tv_title),
            color = LiveColors.Text,
            fontSize = 34.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(14.dp))

        // Row 1: servers (one per playlist) + manage / refresh
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
        ) {
            item {
                Pill(
                    text = "${stringResource(R.string.live_all)} (${state.channels.size})",
                    selected = server == null,
                    onClick = { server = null; group = null }
                )
            }
            items(state.sources, key = { it.id }) { source ->
                val st = state.status[source.id]
                val suffix = when {
                    st == null || st.loading -> "…"
                    st.error != null -> "!"
                    else -> st.count.toString()
                }
                Pill(
                    text = "${source.name} ($suffix)",
                    selected = server == source.id,
                    onClick = { server = source.id; group = null }
                )
            }
            item {
                Pill(
                    text = stringResource(R.string.live_playlist_settings),
                    selected = false,
                    icon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) },
                    onClick = { showManager = true }
                )
            }
            if (state.sources.isNotEmpty()) {
                item {
                    Pill(
                        text = "",
                        selected = false,
                        icon = { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)) },
                        onClick = { LiveTvRepository.reloadAll() }
                    )
                }
            }
        }

        // Row 2: categories inside the selected server
        if (groups.size > 1) {
            Spacer(Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
            ) {
                item {
                    Pill(
                        text = stringResource(R.string.live_all_categories),
                        selected = group == null,
                        small = true,
                        onClick = { group = null }
                    )
                }
                items(groups) { g ->
                    Pill(
                        text = "$g (${inServer.count { it.group == g }})",
                        selected = group == g,
                        small = true,
                        onClick = { group = g }
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        when {
            visible.isEmpty() -> {
                val serverError = server?.let { state.status[it]?.error }
                EmptyState(
                    loading = state.loading,
                    error = serverError ?: state.firstError,
                    onAdd = { showManager = true }
                )
            }
            else -> Row(modifier = Modifier.fillMaxSize()) {
                ChannelList(
                    channels = visible,
                    initialIndex = focusedIndex,
                    now = now,
                    epg = state.epg,
                    onFocused = { focusedIndex = it },
                    onClick = onPlayChannel,
                    modifier = Modifier.weight(1.15f)
                )
                Spacer(Modifier.width(44.dp))
                ChannelPreview(
                    channel = state.channels.getOrNull(focusedIndex)?.takeIf { it in visible } ?: visible.first(),
                    now = now,
                    epg = state.epg,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showManager && editing == null) {
        ManageSourcesDialog(
            sources = state.sources,
            status = state.status,
            onDismiss = { showManager = false },
            onAdd = { editing = LiveSource(LiveTvRepository.newSourceId(), "", "", "") },
            onEdit = { editing = it },
            onDelete = { LiveTvRepository.removeSource(context, it.id) }
        )
    }
    editing?.let { source ->
        PlaylistSourceDialog(
            title = stringResource(R.string.live_dialog_title),
            initialName = source.name,
            initialUrl = source.m3uUrl,
            initialEpg = source.epgUrl,
            onDismiss = { editing = null },
            onSave = { name, url, epg ->
                editing = null
                if (url.isNotBlank()) {
                    LiveTvRepository.upsertSource(context, source.copy(name = name, m3uUrl = url, epgUrl = epg))
                }
            }
        )
    }
}

@Composable
private fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    small: Boolean = false,
    icon: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(23.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Color.White else Color(0xFFD6DBE1),
            focusedContainerColor = Color(0xFFE9EEF4),
            focusedContentColor = Color(0xFF0B0F14)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.5.dp, if (selected) Color(0xFF8FB4D6) else LiveColors.PillBorder), shape = shape),
            focusedBorder = Border(BorderStroke(1.5.dp, Color.White), shape = shape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        glow = ClickableSurfaceDefaults.glow()
    ) {
        Row(
            modifier = Modifier
                .height(if (small) 36.dp else 44.dp)
                .padding(horizontal = if (text.isEmpty()) 14.dp else if (small) 16.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon?.invoke()
            if (text.isNotEmpty()) Text(text = text, fontSize = if (small) 14.sp else 16.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<LiveChannel>,
    initialIndex: Int,
    now: Long,
    epg: Map<String, List<EpgProgram>>,
    onFocused: (Int) -> Unit,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val restoreRequester = remember { FocusRequester() }
    val restorePosition = channels.indexOfFirst { it.index == initialIndex }.coerceAtLeast(0)

    LaunchedEffect(channels) {
        listState.scrollToItem((restorePosition - 1).coerceAtLeast(0))
        delay(50)
        runCatching { restoreRequester.requestFocus() }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 40.dp)
    ) {
        itemsIndexed(channels, key = { _, ch -> ch.index }) { pos, channel ->
            ChannelRow(
                channel = channel,
                now = now,
                epg = epg,
                onClick = { onClick(channel.index) },
                modifier = Modifier
                    .then(if (pos == restorePosition) Modifier.focusRequester(restoreRequester) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocused(channel.index) }
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    now: Long,
    epg: Map<String, List<EpgProgram>>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val (current, _) = LiveTvRepository.nowAndNext(channel, now, epg)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = LiveColors.Card,
            contentColor = LiveColors.Text,
            focusedContainerColor = LiveColors.CardFocused,
            focusedContentColor = LiveColors.Text
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, LiveColors.FocusBorder), shape = shape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.035f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelLogo(channel, Modifier.size(76.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = current?.let { "${it.range()}   ${it.title}" } ?: channel.group,
                    color = LiveColors.TextDim,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )
                ProgressLine(current?.progress(now) ?: 0f)
            }
        }
    }
}

@Composable
private fun ChannelPreview(
    channel: LiveChannel,
    now: Long,
    epg: Map<String, List<EpgProgram>>,
    modifier: Modifier = Modifier
) {
    val (current, _) = LiveTvRepository.nowAndNext(channel, now, epg)
    Column(modifier = modifier.padding(top = 6.dp)) {
        ChannelLogo(
            channel = channel,
            corner = 22.dp,
            textSize = 40.sp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2.35f)
        )
        Text(
            text = channel.name,
            color = LiveColors.Text,
            fontSize = 36.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)
        )
        if (current != null) {
            Text(stringResource(R.string.live_now), color = LiveColors.TextDim, fontSize = 15.sp)
            Text(current.range(), color = LiveColors.Accent, fontSize = 19.sp, modifier = Modifier.padding(vertical = 4.dp))
            Text(current.title, color = LiveColors.Text, fontSize = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            ProgressLine(current.progress(now), height = 8.dp)
        } else {
            Text(stringResource(R.string.live_no_epg), color = LiveColors.TextDim, fontSize = 18.sp)
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, error: String?, onAdd: () -> Unit) {
    val requester = remember { FocusRequester() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (loading) {
            Text(stringResource(R.string.live_loading), color = LiveColors.TextDim, fontSize = 22.sp)
            return@Column
        }
        Text(
            text = if (error != null) stringResource(R.string.live_load_error) else stringResource(R.string.live_empty_title),
            color = LiveColors.Text,
            fontSize = 28.sp
        )
        Text(
            text = when (error) {
                null -> stringResource(R.string.live_empty_body)
                LiveTvRepository.NOT_M3U -> stringResource(R.string.live_not_m3u)
                else -> error
            },
            color = LiveColors.TextDim,
            fontSize = 18.sp
        )
        Box(Modifier.padding(top = 10.dp)) {
            Button(onClick = onAdd, modifier = Modifier.focusRequester(requester)) {
                Text(stringResource(R.string.live_add_playlist))
            }
        }
        LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    }
}
