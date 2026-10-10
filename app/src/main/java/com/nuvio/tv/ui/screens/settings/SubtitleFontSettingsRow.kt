@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.subtitlefont.LanAddress
import com.nuvio.tv.core.subtitlefont.SubtitleFontImportResult
import com.nuvio.tv.core.subtitlefont.SubtitleFontStore
import com.nuvio.tv.core.subtitlefont.SubtitleFontUploadServer
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Subtitle font" row: a user-imported .ttf/.otf used by ExoPlayer and libmpv, default system
 * font otherwise. The font file in app storage is the setting (see SubtitleFontStore).
 */
@Composable
internal fun SubtitleFontSettingsRow(enabled: Boolean = true) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SubtitleFontStore.current(context) } }
    val font by SubtitleFontStore.font.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    SettingsActionRow(
        title = stringResource(R.string.subtitle_font_title),
        subtitle = null,
        value = font?.familyName ?: stringResource(R.string.subtitle_font_default),
        enabled = enabled,
        onClick = { showDialog = true },
    )

    if (showDialog) {
        SubtitleFontDialog(onDismiss = { showDialog = false })
    }
}

private class SubtitleFontServerState(
    val server: SubtitleFontUploadServer?,
    val url: String?,
    val qr: Bitmap?,
    val error: String?,
)

private fun startSubtitleFontServer(
    context: android.content.Context,
    onImported: (SubtitleFontImportResult) -> Unit,
): SubtitleFontServerState {
    val ip = LanAddress.get(context)
        ?: return SubtitleFontServerState(null, null, null, context.getString(R.string.error_network_required))
    val server = SubtitleFontUploadServer.startOnAvailablePort(context, onImported)
        ?: return SubtitleFontServerState(null, null, null, context.getString(R.string.error_server_ports_unavailable))
    val url = "http://$ip:${server.listeningPort}/${server.token}/"
    return SubtitleFontServerState(server, url, QrCodeGenerator.generate(url, 512), null)
}

@Composable
private fun SubtitleFontDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val font by SubtitleFontStore.font.collectAsStateWithLifecycle()
    val status = remember { MutableStateFlow<String?>(null) }
    val statusText by status.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    val importingText = stringResource(R.string.subtitle_font_importing)
    val invalidText = stringResource(R.string.subtitle_font_invalid)
    val tooLargeText = stringResource(R.string.subtitle_font_too_large)
    val downloadFailedText = stringResource(R.string.subtitle_font_download_failed)
    val noPickerText = stringResource(R.string.subtitle_font_no_picker)

    fun describe(result: SubtitleFontImportResult): String = when (result) {
        SubtitleFontImportResult.IMPORTED -> context.getString(
            R.string.subtitle_font_imported,
            SubtitleFontStore.font.value?.familyName.orEmpty(),
        )
        SubtitleFontImportResult.TOO_LARGE -> tooLargeText
        SubtitleFontImportResult.INVALID -> invalidText
        SubtitleFontImportResult.DOWNLOAD_FAILED -> downloadFailedText
    }

    fun runImport(block: suspend () -> SubtitleFontImportResult) {
        if (importing) return
        importing = true
        status.value = importingText
        scope.launch {
            val result = block()
            importing = false
            status.value = describe(result)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport { SubtitleFontStore.importFromUri(context, uri) }
    }

    var serverState by remember { mutableStateOf<SubtitleFontServerState?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var running: SubtitleFontUploadServer? = null
        fun startServer() {
            if (running != null) return
            val started = startSubtitleFontServer(context) { result -> status.value = describe(result) }
            running = started.server
            serverState = started
        }
        fun stopServer() {
            running?.stop()
            running = null
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startServer()
                Lifecycle.Event.ON_STOP -> stopServer()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopServer()
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.subtitle_font_title),
        subtitle = stringResource(R.string.subtitle_font_dialog_description),
        width = 760.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            serverState?.qr?.let { qr ->
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_qr_code),
                    modifier = Modifier.size(168.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            ) {
                Text(
                    text = stringResource(
                        R.string.subtitle_font_current,
                        font?.familyName ?: stringResource(R.string.subtitle_font_default),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                )
                Text(
                    text = serverState?.error ?: stringResource(R.string.subtitle_font_phone_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                )
                serverState?.url?.let { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextTertiary,
                    )
                }
                statusText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End),
        ) {
            SubtitleFontDialogButton(
                text = stringResource(R.string.subtitle_font_choose_file),
                modifier = Modifier.focusRequester(firstFocus),
                onClick = {
                    if (!importing) {
                        try {
                            picker.launch(arrayOf("*/*"))
                        } catch (_: ActivityNotFoundException) {
                            status.value = noPickerText
                            Toast.makeText(context, noPickerText, Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
            SubtitleFontDialogButton(
                text = stringResource(R.string.subtitle_font_from_url),
                onClick = { if (!importing) showUrlDialog = true },
            )
            if (font != null) {
                SubtitleFontDialogButton(
                    text = stringResource(R.string.subtitle_font_reset),
                    onClick = {
                        if (!importing) {
                            SubtitleFontStore.clear(context)
                            status.value = null
                        }
                    },
                )
            }
            SubtitleFontDialogButton(
                text = stringResource(R.string.subtitle_font_done),
                onClick = onDismiss,
            )
        }
    }

    if (showUrlDialog) {
        SubtitleFontUrlDialog(
            onDismiss = { showUrlDialog = false },
            onImport = { url ->
                showUrlDialog = false
                runImport { SubtitleFontStore.importFromUrl(context, url) }
            },
        )
    }
}

@Composable
private fun SubtitleFontDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            contentColor = NuvioTheme.colors.TextPrimary,
        ),
    ) {
        Text(text)
    }
}

@Composable
private fun SubtitleFontUrlDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val cardFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { cardFocusRequester.requestFocus() } }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.subtitle_font_url_title),
        subtitle = stringResource(R.string.subtitle_font_url_description),
        width = 700.dp,
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(cardFocusRequester)
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated,
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(10.dp),
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(10.dp),
                ),
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f),
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            val isCenterDown = event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                            if (isCenterDown) keyboardController?.show()
                            isCenterDown
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(if (isInputFocused) NuvioTheme.colors.Primary else Color.Transparent),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = "https://",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary,
                            )
                        }
                        innerTextField()
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End),
        ) {
            SubtitleFontDialogButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
            SubtitleFontDialogButton(
                text = stringResource(R.string.subtitle_font_url_import),
                onClick = { if (value.isNotBlank()) onImport(value.trim()) },
            )
        }
    }
}
