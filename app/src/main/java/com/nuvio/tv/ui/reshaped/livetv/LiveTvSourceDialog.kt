@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.reshaped.net.LanAddress
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.LiveTvSetupServer
import com.nuvio.tv.reshaped.livetv.LiveTvSource
import com.nuvio.tv.reshaped.livetv.LiveTvSourceGuide
import com.nuvio.tv.reshaped.livetv.LiveTvSourceType
import com.nuvio.tv.reshaped.livetv.LiveTvStalkerSettings
import com.nuvio.tv.reshaped.livetv.LiveTvXtreamSettings
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

private class SetupServerState(val server: LiveTvSetupServer?, val url: String?, val qr: Bitmap?, val error: String?)

/**
 * Where the channels come from: the saved sources, and a form to add one, with a QR code for the
 * phone setup page next to the same fields for the remote. After an add it shows the list again
 * (and closes when that was the first source), whichever side sent it.
 */
@Composable
internal fun LiveTvSourceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uiState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(!uiState.hasSource) }
    var tab by rememberSaveable { mutableStateOf(LiveTvSourceType.M3u) }
    var m3uUrl by rememberSaveable { mutableStateOf("") }
    var xtreamServer by rememberSaveable { mutableStateOf("") }
    var xtreamUser by rememberSaveable { mutableStateOf("") }
    var xtreamPassword by rememberSaveable { mutableStateOf("") }
    var stalkerPortal by rememberSaveable { mutableStateOf("") }
    var stalkerMac by rememberSaveable { mutableStateOf("") }
    var stalkerUser by rememberSaveable { mutableStateOf("") }
    var stalkerPassword by rememberSaveable { mutableStateOf("") }
    var epgLink by rememberSaveable { mutableStateOf("") }
    var sourceName by rememberSaveable { mutableStateOf("") }
    var userAgent by rememberSaveable { mutableStateOf("") }
    /** The saved source the form edits; null while adding. */
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> uiState.sources.firstOrNull { it.id == id } }
    val firstFocus = remember { FocusRequester() }

    // Back to the list once a source was added (or entered again) while the dialog was open; the
    // first source closes it.
    var seenAdds by remember { mutableIntStateOf(uiState.addedCount) }
    val openedEmpty = remember { uiState.sources.isEmpty() }
    LaunchedEffect(uiState.addedCount) {
        if (uiState.addedCount == seenAdds) return@LaunchedEffect
        seenAdds = uiState.addedCount
        if (openedEmpty && uiState.sources.size == 1) onDismiss() else {
            adding = false
            editingId = null
            m3uUrl = ""; xtreamServer = ""; xtreamUser = ""; xtreamPassword = ""
            stalkerPortal = ""; stalkerMac = ""; stalkerUser = ""; stalkerPassword = ""; epgLink = ""; sourceName = ""; userAgent = ""
        }
    }

    // The phone page runs only while this dialog is open and the app is in the foreground.
    var serverState by remember { mutableStateOf<SetupServerState?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var running: LiveTvSetupServer? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (running == null) {
                    val started = startSetupServer(context)
                    running = started.server
                    serverState = started
                }
                Lifecycle.Event.ON_STOP -> {
                    running?.stop()
                    running = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            running?.stop()
        }
    }
    // Removing the last source goes to the form; a removed row's focus moves to the first row.
    LaunchedEffect(adding, uiState.sources.size) {
        if (!uiState.hasSource) adding = true
        // The source being edited was removed (on another device, through sync).
        if (editingId != null && editing == null) editingId = null
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(
            when {
                editing != null -> R.string.live_tv_source_edit_title
                adding -> R.string.live_tv_source_add_title
                else -> R.string.live_tv_sources_title
            },
        ),
        subtitle = stringResource(
            when {
                editing != null -> R.string.live_tv_source_edit_description
                adding -> R.string.live_tv_source_description
                else -> R.string.live_tv_sources_description
            },
        ),
        width = 860.dp,
        // The platform's default dialog width is narrower than this layout on TVs.
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.md,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
        ) {
            Column(
                modifier = Modifier.width(200.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                serverState?.qr?.let { qr ->
                    Image(
                        bitmap = remember(qr) { qr.asImageBitmap() },
                        contentDescription = stringResource(R.string.cd_qr_code),
                        modifier = Modifier.size(170.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    text = serverState?.error ?: stringResource(R.string.live_tv_phone_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                )
                serverState?.url?.let { url ->
                    Text(text = url, style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextTertiary)
                }
            }

            // Scrolls when the screen is short; moving focus down brings each field into view.
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            ) {
                if (!adding) {
                    var confirmRemoveId by remember { mutableStateOf<String?>(null) }
                    uiState.sources.forEachIndexed { index, source ->
                        // Keyed, so the focus goes with a source that is moved.
                        key(source.id) {
                            LiveTvSourceRow(
                                source = source,
                                channelCount = uiState.sourceCounts[source.id] ?: 0,
                                error = uiState.sourceErrors[source.id]?.message(context),
                                guide = uiState.sourceGuides[source.id],
                                confirmingRemove = confirmRemoveId == source.id,
                                onEdit = {
                                    confirmRemoveId = null
                                    tab = source.type
                                    m3uUrl = source.url
                                    xtreamServer = source.xtream.serverUrl
                                    xtreamUser = source.xtream.username
                                    xtreamPassword = source.xtream.password
                                    stalkerPortal = source.stalker.portalUrl
                                    stalkerMac = source.stalker.macAddress
                                    stalkerUser = source.stalker.username
                                    stalkerPassword = source.stalker.password
                                    epgLink = source.epgUrl
                                    sourceName = source.name
                                    userAgent = source.userAgent
                                    editingId = source.id
                                    adding = true
                                },
                                onRemove = {
                                    if (confirmRemoveId == source.id) {
                                        confirmRemoveId = null
                                        LiveTvRepository.removeSource(source.id)
                                    } else {
                                        confirmRemoveId = source.id
                                    }
                                },
                                // The order the guide and the categories list the sources in, synced.
                                onMove = if (uiState.sources.size > 1) { step -> LiveTvRepository.moveSource(source.id, step) } else null,
                                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            )
                        }
                    }
                    if (uiState.isLoading) {
                        Text(
                            text = stringResource(R.string.live_tv_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End),
                    ) {
                        LiveTvPillButton(text = stringResource(R.string.live_tv_close), onClick = onDismiss)
                        LiveTvPillButton(text = stringResource(R.string.live_tv_add_source_button), onClick = { adding = true })
                    }
                } else if (editing == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                        LiveTvPillButton(
                            text = stringResource(R.string.live_tv_source_m3u),
                            selected = tab == LiveTvSourceType.M3u,
                            onClick = { tab = LiveTvSourceType.M3u },
                            modifier = Modifier.focusRequester(firstFocus),
                        )
                        LiveTvPillButton(
                            text = stringResource(R.string.live_tv_source_xtream),
                            selected = tab == LiveTvSourceType.Xtream,
                            onClick = { tab = LiveTvSourceType.Xtream },
                        )
                        LiveTvPillButton(
                            text = stringResource(R.string.live_tv_source_stalker),
                            selected = tab == LiveTvSourceType.Stalker,
                            onClick = { tab = LiveTvSourceType.Stalker },
                        )
                    }
                }
                if (adding) {
                    // Editing has no kind to pick: its name field takes the focus instead.
                    if (editing != null) {
                        LiveTvTextField(
                            sourceName,
                            { sourceName = it },
                            stringResource(R.string.live_tv_source_name_hint, editing.copy(name = "").label),
                            Modifier.focusRequester(firstFocus),
                            keyboardType = KeyboardType.Text,
                        )
                    }
                    when (tab) {
                        LiveTvSourceType.M3u -> {
                            if (editing != null && !editing.url.startsWith("http", ignoreCase = true)) {
                                // An imported file: only its guide can change.
                                Text(
                                    text = stringResource(R.string.live_tv_imported_file, editing.url),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioTheme.colors.TextSecondary,
                                )
                            } else {
                                LiveTvTextField(m3uUrl, { m3uUrl = it }, stringResource(R.string.live_tv_m3u_hint))
                            }
                        }
                        LiveTvSourceType.Xtream -> {
                            LiveTvTextField(xtreamServer, { xtreamServer = it }, stringResource(R.string.live_tv_xtream_server_hint))
                            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                                LiveTvTextField(xtreamUser, { xtreamUser = it }, stringResource(R.string.live_tv_username_hint), Modifier.weight(1f), keyboardType = KeyboardType.Text)
                                LiveTvTextField(xtreamPassword, { xtreamPassword = it }, stringResource(R.string.live_tv_password_hint), Modifier.weight(1f), password = true)
                            }
                        }
                        LiveTvSourceType.Stalker -> {
                            LiveTvTextField(stalkerPortal, { stalkerPortal = it }, stringResource(R.string.live_tv_stalker_portal_hint))
                            LiveTvTextField(stalkerMac, { stalkerMac = it }, stringResource(R.string.live_tv_stalker_mac_hint), keyboardType = KeyboardType.Ascii)
                            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                                LiveTvTextField(stalkerUser, { stalkerUser = it }, stringResource(R.string.live_tv_optional_username_hint), Modifier.weight(1f), keyboardType = KeyboardType.Text)
                                LiveTvTextField(stalkerPassword, { stalkerPassword = it }, stringResource(R.string.live_tv_optional_password_hint), Modifier.weight(1f), password = true)
                            }
                        }
                    }
                    LiveTvTextField(epgLink, { epgLink = it }, stringResource(R.string.live_tv_epg_hint))
                    // Portals need their own set-top box agent, so only lists and Xtream panels take one.
                    if (tab != LiveTvSourceType.Stalker) {
                        LiveTvTextField(userAgent, { userAgent = it }, stringResource(R.string.live_tv_user_agent_hint), keyboardType = KeyboardType.Ascii)
                    }

                    val status = when {
                        uiState.isLoading -> stringResource(R.string.live_tv_loading)
                        uiState.error != null -> uiState.error?.message(context)
                        else -> null
                    }
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.error != null && !uiState.isLoading) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End),
                    ) {
                        if (uiState.hasSource) {
                            LiveTvPillButton(text = stringResource(R.string.live_tv_back), onClick = { adding = false; editingId = null })
                        } else {
                            LiveTvPillButton(text = stringResource(R.string.live_tv_close), onClick = onDismiss)
                        }
                        LiveTvPillButton(
                            text = stringResource(if (editing != null) R.string.live_tv_save_source else R.string.live_tv_load),
                            enabled = !uiState.isLoading,
                            onClick = {
                                val xtream = LiveTvXtreamSettings(xtreamServer, xtreamUser, xtreamPassword)
                                val stalker = LiveTvStalkerSettings(stalkerPortal, stalkerMac, stalkerUser, stalkerPassword)
                                if (editing != null) {
                                    LiveTvRepository.updateSource(
                                        editing.id,
                                        LiveTvSource(editing.id, editing.type, m3uUrl, stalker = stalker, xtream = xtream, epgUrl = epgLink, name = sourceName, userAgent = userAgent),
                                    )
                                } else {
                                    when (tab) {
                                        LiveTvSourceType.M3u -> LiveTvRepository.loadM3uUrl(m3uUrl, epgLink, userAgent)
                                        LiveTvSourceType.Xtream -> LiveTvRepository.loadXtream(xtream, epgLink, userAgent)
                                        LiveTvSourceType.Stalker -> LiveTvRepository.loadStalker(stalker, epgLink)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A saved source: its name, kind, channel count and guide (or why it failed), Edit, and a two-press Remove. */
@Composable
private fun LiveTvSourceRow(
    source: LiveTvSource,
    channelCount: Int,
    error: String?,
    guide: LiveTvSourceGuide?,
    confirmingRemove: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    /** Moves the source up (-1) or down (1); null when there is nothing to order. */
    onMove: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val kind = stringResource(
        when (source.type) {
            LiveTvSourceType.M3u -> R.string.live_tv_source_m3u
            LiveTvSourceType.Xtream -> R.string.live_tv_source_xtream
            LiveTvSourceType.Stalker -> R.string.live_tv_source_stalker
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.6f))
            .padding(start = NuvioTheme.spacing.lg, end = NuvioTheme.spacing.sm, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.label.redacted(),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val guideText = when (guide?.state) {
                null -> null
                LiveTvSourceGuide.State.None -> stringResource(R.string.live_tv_source_guide_none)
                LiveTvSourceGuide.State.Loading -> stringResource(R.string.live_tv_source_guide_loading)
                LiveTvSourceGuide.State.Failed -> stringResource(R.string.live_tv_source_guide_failed)
                LiveTvSourceGuide.State.Loaded -> if (guide.retrying) {
                    stringResource(R.string.live_tv_source_guide_retrying, guide.channels)
                } else {
                    stringResource(R.string.live_tv_source_guide_loaded, guide.channels)
                }
            }
            val summary = stringResource(R.string.live_tv_source_summary, kind, channelCount)
            Text(
                text = error ?: guideText?.let { "$summary · $it" } ?: summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onMove != null) {
            LiveTvPillButton(
                text = "",
                onClick = { onMove(-1) },
                icon = Icons.Filled.KeyboardArrowUp,
                iconDescription = stringResource(R.string.live_tv_move_source_up),
            )
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            LiveTvPillButton(
                text = "",
                onClick = { onMove(1) },
                icon = Icons.Filled.KeyboardArrowDown,
                iconDescription = stringResource(R.string.live_tv_move_source_down),
            )
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
        }
        LiveTvPillButton(
            text = stringResource(R.string.live_tv_edit_source),
            onClick = onEdit,
            modifier = modifier,
        )
        Spacer(Modifier.width(NuvioTheme.spacing.sm))
        LiveTvPillButton(
            text = stringResource(if (confirmingRemove) R.string.live_tv_remove_confirm else R.string.live_tv_remove_source),
            onClick = onRemove,
        )
    }
}

private fun startSetupServer(context: android.content.Context): SetupServerState {
    val ip = LanAddress.get(context)
        ?: return SetupServerState(null, null, null, context.getString(R.string.error_network_required))
    val server = LiveTvSetupServer.startOnAvailablePort(context)
        ?: return SetupServerState(null, null, null, context.getString(R.string.error_server_ports_unavailable))
    val url = "http://$ip:${server.listeningPort}/${server.token}/"
    return SetupServerState(server, url, QrCodeGenerator.generate(url, 400), null)
}

/** A source for display: an Xtream-style link's login is not shown on screen. */
private fun String.redacted(): String =
    replace(Regex("""(?i)(password|username)=[^&]*"""), "$1=…")
