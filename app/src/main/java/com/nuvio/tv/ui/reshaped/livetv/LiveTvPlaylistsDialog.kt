@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvCustomList
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Names a new playlist; with [channel], that channel is put in it. */
@Composable
internal fun LiveTvNewListDialog(onCreated: (String) -> Unit, onDismiss: () -> Unit, channels: List<LiveTvChannel> = emptyList()) {
    var name by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { field.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val create: () -> Unit = { LiveTvRepository.createCustomList(name, channels)?.let(onCreated) }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_playlist_new),
        subtitle = when (channels.size) {
            0 -> stringResource(R.string.live_tv_playlist_new_description)
            1 -> stringResource(R.string.live_tv_playlist_new_with_channel, channels[0].name)
            else -> pluralStringResource(R.plurals.live_tv_playlist_new_with_channels, channels.size, channels.size)
        },
        width = 520.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.md,
    ) {
        LiveTvTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.live_tv_playlist_name_hint),
            keyboardType = KeyboardType.Text,
            onDone = create,
            modifier = Modifier.fillMaxWidth().focusRequester(field),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End),
        ) {
            LiveTvPillButton(text = stringResource(R.string.live_tv_cancel), onClick = onDismiss)
            LiveTvPillButton(text = stringResource(R.string.live_tv_playlist_create), onClick = create, enabled = name.isNotBlank())
        }
    }
}

/**
 * The viewer's own playlists: make one, and in each rename it, take channels out, put them in
 * order (hold OK, ▲▼, OK) or delete it. Channels go in with a long OK in the guide.
 */
@Composable
internal fun LiveTvPlaylistsDialog(onDismiss: () -> Unit, openOnly: String? = null) {
    val uiState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    // [openOnly]: one playlist straight away (held OK on it in the categories); Back closes.
    var openId by remember { mutableStateOf(openOnly) }
    // Coming back from a playlist focuses it again.
    var returnTo by remember { mutableStateOf<String?>(null) }
    val open = uiState.customLists.firstOrNull { it.id == openId }
    NuvioDialog(
        onDismiss = onDismiss,
        title = open?.name ?: stringResource(R.string.live_tv_my_playlists),
        subtitle = stringResource(if (open != null) R.string.live_tv_playlist_description else R.string.live_tv_playlists_description),
        width = 640.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.md,
    ) {
        if (open != null) {
            LiveTvPlaylistChannels(list = open, onBack = { if (openOnly != null) onDismiss() else openId = null })
            return@NuvioDialog
        }
        if (openOnly != null) {
            // Deleted: nothing left to show.
            LaunchedEffect(Unit) { onDismiss() }
            return@NuvioDialog
        }
        var naming by remember { mutableStateOf(false) }
        if (naming) {
            LiveTvNewListDialog(
                onCreated = { id ->
                    naming = false
                    returnTo = id
                },
                onDismiss = { naming = false },
            )
        }
        val firstFocus = remember { FocusRequester() }
        val returnFocus = remember { FocusRequester() }
        // Again after a playlist is made: it takes focus.
        LaunchedEffect(returnTo, naming) {
            if (naming) return@LaunchedEffect
            repeat(5) {
                withFrameNanos { }
                val target = if (returnTo != null && uiState.customLists.any { it.id == returnTo }) returnFocus else firstFocus
                if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            LiveTvPillButton(
                text = stringResource(R.string.live_tv_playlist_new),
                icon = Icons.Filled.Add,
                onClick = { naming = true },
                modifier = Modifier.focusRequester(firstFocus),
            )
            Spacer(Modifier.weight(1f))
            LiveTvPillButton(text = stringResource(R.string.live_tv_done), onClick = onDismiss)
        }
        if (uiState.customLists.isEmpty()) {
            Text(
                text = stringResource(R.string.live_tv_playlists_none),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(uiState.customLists, key = { it.id }) { list ->
                    val openThis = {
                        returnTo = list.id
                        openId = list.id
                    }
                    LiveTvCategoryToggle(
                        label = list.name,
                        count = list.urls.size.toString(),
                        visible = true,
                        onToggle = openThis,
                        onOpen = openThis,
                        modifier = if (list.id == returnTo) Modifier.focusRequester(returnFocus) else Modifier,
                    )
                }
            }
        }
    }
}

/** One playlist: its name, its channels in order, and Delete. Back returns to the playlists. */
@Composable
private fun LiveTvPlaylistChannels(list: LiveTvCustomList, onBack: () -> Unit) {
    val uiState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    // Each link with its channel, or null when no source lists it now (removed, or not loaded yet).
    val channels by produceState(emptyMap<String, LiveTvChannel>(), uiState.channels, list.urls) {
        value = withContext(Dispatchers.Default) {
            val wanted = list.urls.toHashSet()
            val found = HashMap<String, LiveTvChannel>(wanted.size * 2)
            uiState.channels.forEach { if (it.streamUrl in wanted) found.putIfAbsent(it.streamUrl, it) }
            found
        }
    }
    var name by remember(list.id) { mutableStateOf(list.name) }
    var moving by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(list.id) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val unavailable = stringResource(R.string.live_tv_playlist_unavailable)
    Column(
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        modifier = Modifier.onPreviewKeyEvent { event ->
            // A channel being moved takes Back itself, to put it down.
            if (moving != null) return@onPreviewKeyEvent false
            val native = event.nativeKeyEvent
            val back = native.keyCode == KeyEvent.KEYCODE_BACK || native.keyCode == KeyEvent.KEYCODE_ESCAPE
            if (back && native.action == KeyEvent.ACTION_UP) onBack()
            back
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            LiveTvTextField(
                value = name,
                onValueChange = {
                    name = it
                    LiveTvRepository.renameCustomList(list.id, it)
                },
                placeholder = stringResource(R.string.live_tv_playlist_name_hint),
                keyboardType = KeyboardType.Text,
                modifier = Modifier.weight(1f).focusRequester(firstFocus),
            )
            LiveTvPillButton(
                text = stringResource(if (confirmDelete) R.string.live_tv_playlist_delete_confirm else R.string.live_tv_playlist_delete),
                icon = Icons.Filled.Delete,
                onClick = {
                    if (confirmDelete) {
                        LiveTvRepository.deleteCustomList(list.id)
                        onBack()
                    } else {
                        confirmDelete = true
                    }
                },
            )
            LiveTvPillButton(text = stringResource(R.string.live_tv_back), onClick = onBack)
        }
        if (list.urls.isEmpty()) {
            Text(
                text = stringResource(R.string.live_tv_playlist_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(list.urls, key = { _, url -> url }) { index, url ->
                val isMoving = moving == url
                LiveTvCategoryToggle(
                    label = channels[url]?.name ?: unavailable,
                    count = null,
                    visible = true,
                    moving = isMoving,
                    // OK takes a channel out; held, it is picked up to move.
                    onToggle = { if (isMoving) moving = null else LiveTvRepository.removeFromCustomList(list.id, url) },
                    onPickUp = { moving = if (isMoving) null else url },
                    onMove = { step ->
                        val target = index + step
                        LiveTvRepository.moveInCustomList(list.id, url, step)
                        val shownRows = listState.layoutInfo.visibleItemsInfo
                        val first = shownRows.firstOrNull()?.index ?: 0
                        val last = shownRows.lastOrNull()?.index ?: 0
                        if (target <= first || target >= last) {
                            scope.launch { listState.scrollToItem((target - if (step < 0) 1 else shownRows.size - 2).coerceAtLeast(0)) }
                        }
                    },
                    onDrop = { moving = null },
                )
            }
        }
    }
}
