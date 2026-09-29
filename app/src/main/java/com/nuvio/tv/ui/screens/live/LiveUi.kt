@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object LiveColors {
    val Background = Color(0xFF07090C)
    val Card = Color(0xFF141922)
    val CardFocused = Color(0xFF2C4466)
    val FocusBorder = Color(0xFFAEB8C4)
    val PillBorder = Color(0xFF3A424D)
    val Text = Color(0xFFF2F4F7)
    val TextDim = Color(0xFF8B949E)
    val Accent = Color(0xFF7CC4F2)
    val Track = Color(0xFF2C333D)
    val Osd = Color(0xE6161B22)
    val Divider = Color(0xFF39414B)
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

internal fun formatClock(ms: Long): String = synchronized(timeFormat) { timeFormat.format(Date(ms)) }

internal fun EpgProgram.range(): String = "${formatClock(startMs)}-${formatClock(stopMs)}"

@Composable
internal fun ChannelLogo(
    channel: LiveChannel,
    modifier: Modifier = Modifier,
    corner: Dp = 6.dp,
    textSize: TextUnit = 18.sp
) {
    var failed by remember(channel.logo) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (channel.logo != null && !failed) {
            AsyncImage(
                model = channel.logo,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            )
        } else {
            Text(
                text = channel.name,
                color = Color(0xFF111111),
                fontSize = textSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

@Composable
internal fun ProgressLine(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    color: Color = LiveColors.Accent,
    track: Color = LiveColors.Track
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(height / 2))
                .background(color)
        )
    }
}


/**
 * Dialog for one playlist: optional display name, M3U URL and optional XMLTV guide URL.
 * Pass null for [initialName] / [initialEpg] to hide those fields.
 */
@Composable
internal fun PlaylistSourceDialog(
    title: String,
    initialName: String?,
    initialUrl: String,
    initialEpg: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, epg: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    var url by remember { mutableStateOf(initialUrl) }
    var epg by remember { mutableStateOf(initialEpg.orEmpty()) }
    val urlRequester = remember { FocusRequester() }
    val epgRequester = remember { FocusRequester() }
    val saveRequester = remember { FocusRequester() }
    val afterUrl = if (initialEpg != null) epgRequester else saveRequester
    NuvioDialog(onDismiss = onDismiss, title = title, width = 640.dp) {
        if (initialName != null) {
            InputField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.live_name_hint),
                onImeAction = { runCatching { urlRequester.requestFocus() } },
                modifier = Modifier.fillMaxWidth()
            )
        }
        InputField(
            value = url,
            onValueChange = { url = it },
            placeholder = stringResource(R.string.live_m3u_hint),
            onImeAction = { runCatching { afterUrl.requestFocus() } },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(urlRequester)
                .focusProperties { down = afterUrl }
        )
        if (initialEpg != null) {
            InputField(
                value = epg,
                onValueChange = { epg = it },
                placeholder = stringResource(R.string.live_epg_hint),
                onImeAction = { runCatching { saveRequester.requestFocus() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(epgRequester)
                    .focusProperties { down = saveRequester }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSave(name, url, epg) }, modifier = Modifier.focusRequester(saveRequester)) {
                Text(stringResource(R.string.live_save))
            }
            OutlinedButton(onClick = {
                url = ""
                epg = ""
            }) { Text(stringResource(R.string.live_clear)) }
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.live_cancel)) }
        }
    }
}

/** Lists every playlist ("server") with its status, and lets the user add, edit or delete them. */
@Composable
internal fun ManageSourcesDialog(
    sources: List<LiveSource>,
    status: Map<String, SourceStatus>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (LiveSource) -> Unit,
    onDelete: (LiveSource) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val addRequester = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.live_manage_title), width = 760.dp) {
        if (sources.isEmpty()) {
            Text(stringResource(R.string.live_empty_body), color = LiveColors.TextDim, fontSize = 16.sp)
        }
        sources.forEach { source ->
            val st = status[source.id]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LiveColors.Card, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(source.name, color = LiveColors.Text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = when {
                            st == null || st.loading -> stringResource(R.string.live_loading)
                            st.error == LiveTvRepository.NOT_M3U -> stringResource(R.string.live_not_m3u)
                            st.error != null -> stringResource(R.string.live_load_error) + " (" + st.error + ")"
                            else -> stringResource(R.string.live_channel_count, st.count)
                        },
                        color = if (st?.error != null) Color(0xFFF08A8A) else LiveColors.TextDim,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = { onEdit(source) }) { Text(stringResource(R.string.live_edit)) }
                OutlinedButton(onClick = {
                    if (pendingDelete == source.id) {
                        pendingDelete = null
                        onDelete(source)
                        runCatching { addRequester.requestFocus() }
                    } else {
                        pendingDelete = source.id
                    }
                }) {
                    Text(
                        stringResource(if (pendingDelete == source.id) R.string.live_delete_confirm else R.string.live_delete)
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAdd, modifier = Modifier.focusRequester(addRequester)) {
                Text(stringResource(R.string.live_add_playlist))
            }
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.live_close)) }
        }
    }
    LaunchedEffect(Unit) { runCatching { addRequester.requestFocus() } }
}

@Composable
internal fun rememberAppContext() = LocalContext.current.applicationContext
