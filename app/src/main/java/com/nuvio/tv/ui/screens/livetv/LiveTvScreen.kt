package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvProgram
import com.nuvio.tv.ui.components.LiveTvChannelTile
import com.nuvio.tv.ui.components.LiveTvNowNext
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LiveTvScreen(
    onPlayChannel: (LiveTvChannel) -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var playlistUrl by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    val sourceDateFormatter = remember { DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()) }
    val programsByChannel = remember(state.programs) {
        state.programs.groupBy { it.sourceId to it.channelId }
            .mapValues { (_, programs) -> programs.sortedBy(LiveTvProgram::startEpochMillis) }
    }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    LaunchedEffect(state.lastSavedSourceId) {
        if (state.lastSavedSourceId != null) {
            name = ""
            playlistUrl = ""
            epgUrl = ""
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.live_tv_title), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.live_tv_description), style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.live_tv_add_source), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value = name, onValueChange = { name = it.take(80) },
                        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.live_tv_source_name)) }, singleLine = true)
                    OutlinedTextField(value = playlistUrl, onValueChange = { playlistUrl = it.take(4096) },
                        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.live_tv_playlist_url)) }, singleLine = true)
                    OutlinedTextField(value = epgUrl, onValueChange = { epgUrl = it.take(4096) },
                        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.live_tv_epg_url)) }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            viewModel.addSource(name, playlistUrl, epgUrl.ifBlank { null })
                        }, enabled = !state.isLoading && playlistUrl.isNotBlank()) {
                            Text(stringResource(R.string.live_tv_save_source))
                        }
                        Button(onClick = viewModel::refresh, enabled = !state.isLoading && state.sources.any { it.enabled }) {
                            Text(stringResource(R.string.live_tv_refresh))
                        }
                    }
                }
            }
        }
        if (state.isLoading) item { Text(stringResource(R.string.live_tv_loading), color = NuvioTheme.colors.TextSecondary) }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (state.sources.isNotEmpty()) {
            item { Text(stringResource(R.string.live_tv_sources), style = MaterialTheme.typography.titleLarge) }
            items(state.sources, key = { "source:${it.id}" }) { source ->
                Surface(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name, style = MaterialTheme.typography.titleMedium)
                            val sourceStatus = state.sourceErrors[source.id]
                                ?: state.lastUpdatedEpochMillis[source.id]?.let { timestamp ->
                                    stringResource(R.string.live_tv_updated, sourceDateFormatter.format(java.time.Instant.ofEpochMilli(timestamp)))
                                }
                                ?: source.playlistUrl.hostForDisplay()
                            Text(sourceStatus,
                                style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
                        }
                        Button(onClick = { viewModel.setSourceEnabled(source, !source.enabled) }) {
                            Text(if (source.enabled) stringResource(R.string.live_tv_disable) else stringResource(R.string.live_tv_enable))
                        }
                        Button(onClick = { viewModel.removeSource(source) }) { Text(stringResource(R.string.live_tv_remove)) }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.live_tv_channels, state.channels.size), style = MaterialTheme.typography.titleLarge) }
        if (!state.isLoading && state.channels.isEmpty()) {
            item { Text(stringResource(R.string.live_tv_empty_channels), color = NuvioTheme.colors.TextSecondary) }
        }
        items(state.channels, key = { "channel:${it.id}" }) { channel ->
            val programs = channel.tvgId?.takeIf(String::isNotBlank)
                ?.let { programsByChannel[channel.sourceId to it] }
                .orEmpty()
            val now = nowMillis
            val current = programs.firstOrNull { now >= it.startEpochMillis && now < it.endEpochMillis }
            val next = programs.firstOrNull { it.startEpochMillis > now }
            val progress = current?.let {
                ((now - it.startEpochMillis).toFloat() / (it.endEpochMillis - it.startEpochMillis).toFloat()).coerceIn(0f, 1f)
            }
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (channel.streamUrl != null) {
                        LiveTvChannelTile(channel.displayName, null, null, false, { onPlayChannel(channel) })
                    } else {
                        Text(channel.displayName, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        val nowLabel = current?.let { "${formatter.format(java.time.Instant.ofEpochMilli(it.startEpochMillis))}  ${it.title}" }
                            ?: stringResource(R.string.live_tv_no_current_program)
                        val nextLabel = next?.let { "${formatter.format(java.time.Instant.ofEpochMilli(it.startEpochMillis))}  ${it.title}" }
                        LiveTvNowNext(nowLabel, nextLabel, stringResource(R.string.live_tv_next), progress)
                    }
                    if (channel.streamUrl != null) {
                        Button(onClick = { onPlayChannel(channel) }) { Text(stringResource(R.string.live_tv_watch)) }
                    }
                }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 28.dp)) }
    }
}

private fun String.hostForDisplay(): String = runCatching { java.net.URI(this).host.orEmpty() }
    .getOrDefault("").ifBlank { stringResourceFallback }

private const val stringResourceFallback = "URL configurada"
