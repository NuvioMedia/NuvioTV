@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvPlaylist
import com.nuvio.tv.data.iptv.IptvSourceType
import com.nuvio.tv.data.iptv.XtreamCodes
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun IptvPlaylistManagerDialog(
    playlists: List<IptvPlaylist>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (IptvPlaylist) -> Unit,
    onRefresh: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.iptv_manage_playlists),
        width = 600.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            playlists.forEachIndexed { index, playlist ->
                IptvMenuItem(
                    text = playlist.name,
                    supportingText = playlistSummary(playlist),
                    icon = if (playlist.type == IptvSourceType.XTREAM) Icons.Filled.Dns else Icons.Filled.Link,
                    onClick = { onEdit(playlist) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
            IptvMenuItem(
                text = stringResource(R.string.iptv_add_playlist),
                icon = Icons.Filled.Add,
                onClick = onAdd,
                modifier = if (playlists.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
            )
            if (playlists.isNotEmpty()) {
                IptvMenuItem(
                    text = stringResource(R.string.iptv_refresh),
                    icon = Icons.Filled.Refresh,
                    onClick = onRefresh
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
}

private fun playlistSummary(playlist: IptvPlaylist): String = when (playlist.type) {
    IptvSourceType.M3U -> "M3U · " + playlist.url.substringAfter("://").substringBefore('/').substringBefore('?')
    IptvSourceType.XTREAM -> "Xtream · " + XtreamCodes.normalizeServer(playlist.url).substringAfter("://") +
        " · " + playlist.username
}

@Composable
internal fun IptvPlaylistEditorDialog(
    initial: IptvPlaylist?,
    onDismiss: () -> Unit,
    onSave: (IptvPlaylist) -> Unit,
    onDelete: (IptvPlaylist) -> Unit
) {
    val defaultName = stringResource(R.string.iptv_default_playlist_name)
    var type by rememberSaveable { mutableStateOf(initial?.type ?: IptvSourceType.M3U) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(initial?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(initial?.password.orEmpty()) }
    var epgUrl by rememberSaveable { mutableStateOf(initial?.epgUrl.orEmpty()) }
    var userAgent by rememberSaveable { mutableStateOf(initial?.userAgent.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val urlRequired = stringResource(R.string.iptv_error_url_required)
    val credentialsRequired = stringResource(R.string.iptv_error_credentials_required)
    val firstFocus = remember { FocusRequester() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(if (initial == null) R.string.iptv_add_playlist else R.string.iptv_edit_playlist),
        width = 640.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IptvButton(
                    text = stringResource(R.string.iptv_type_m3u),
                    icon = Icons.Filled.Link,
                    primary = type == IptvSourceType.M3U,
                    onClick = {
                        type = IptvSourceType.M3U
                        error = null
                    },
                    modifier = Modifier.focusRequester(firstFocus)
                )
                IptvButton(
                    text = stringResource(R.string.iptv_type_xtream),
                    icon = Icons.Filled.Dns,
                    primary = type == IptvSourceType.XTREAM,
                    onClick = {
                        type = IptvSourceType.XTREAM
                        error = null
                    }
                )
            }
            InputField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.iptv_field_name),
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
            if (type == IptvSourceType.M3U) {
                InputField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    placeholder = stringResource(R.string.iptv_field_m3u_url),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                InputField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    placeholder = stringResource(R.string.iptv_field_server),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
                InputField(
                    value = username,
                    onValueChange = {
                        username = it
                        error = null
                    },
                    placeholder = stringResource(R.string.iptv_field_username),
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
                InputField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    placeholder = stringResource(R.string.iptv_field_password),
                    isPassword = true,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            InputField(
                value = epgUrl,
                onValueChange = { epgUrl = it },
                placeholder = stringResource(R.string.iptv_field_epg_url),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
            InputField(
                value = userAgent,
                onValueChange = { userAgent = it },
                placeholder = stringResource(R.string.iptv_field_user_agent),
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth()
            )
        }

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.Error
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IptvButton(
                text = stringResource(R.string.iptv_save),
                primary = true,
                onClick = {
                    val trimmedUrl = url.trim()
                    when {
                        trimmedUrl.isEmpty() && type == IptvSourceType.M3U -> error = urlRequired
                        type == IptvSourceType.XTREAM &&
                            (trimmedUrl.isEmpty() || username.isBlank() || password.isBlank()) -> error = credentialsRequired
                        else -> {
                            val resolvedUrl = if (type == IptvSourceType.XTREAM) {
                                XtreamCodes.normalizeServer(trimmedUrl)
                            } else if (!trimmedUrl.contains("://")) {
                                "http://$trimmedUrl"
                            } else {
                                trimmedUrl
                            }
                            onSave(
                                IptvPlaylist(
                                    id = initial?.id.orEmpty(),
                                    name = name.trim().ifEmpty { defaultName },
                                    type = type,
                                    url = resolvedUrl,
                                    username = if (type == IptvSourceType.XTREAM) username.trim() else "",
                                    password = if (type == IptvSourceType.XTREAM) password.trim() else "",
                                    epgUrl = epgUrl.trim(),
                                    userAgent = userAgent.trim(),
                                    enabled = initial?.enabled ?: true
                                )
                            )
                        }
                    }
                }
            )
            IptvButton(
                text = stringResource(R.string.iptv_cancel),
                onClick = onDismiss
            )
            if (initial != null) {
                IptvButton(
                    text = stringResource(R.string.iptv_delete),
                    icon = Icons.Filled.Delete,
                    onClick = { onDelete(initial) }
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
}
