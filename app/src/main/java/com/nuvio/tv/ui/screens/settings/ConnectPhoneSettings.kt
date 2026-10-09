package com.nuvio.tv.ui.screens.settings

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.remote.TvRemoteServer
import kotlinx.coroutines.delay

@Composable
internal fun ConnectPhoneSettings(initialFocusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Starting secure connection…") }
    var generation by remember { mutableIntStateOf(0) }
    var paired by remember { mutableStateOf(false) }
    LaunchedEffect(generation) {
        code = null
        message = "Starting secure connection…"
        TvRemoteServer.start(context)
        repeat(50) { if (TvRemoteServer.instance == null) delay(100) }
        val server = TvRemoteServer.instance
        if (server == null) { message = "Could not start the connection. Try again."; return@LaunchedEffect }
        paired = server.hasPairedPhone()
        if (paired) server.closePairing() else {
            runCatching { server.beginPairing() }.onSuccess {
                code = it
                message = "In Nuvio on your phone, open Settings → TV connection and scan this code. Both devices must use your home network."
            }.onFailure { message = it.message ?: "Unable to create pairing code" }
        }
        val expiresAt = SystemClock.elapsedRealtime() + 120_000
        while (true) {
            delay(1_000)
            val authorized = server.hasPairedPhone()
            if (paired && !authorized) {
                paired = false
                generation++ // A phone can also revoke its authorization remotely.
                return@LaunchedEffect
            }
            paired = authorized
            if (paired) {
                server.closePairing()
                code = null
            } else if (code != null && SystemClock.elapsedRealtime() >= expiresAt) {
                server.closePairing()
                code = null
                message = "Code expired. Generate a new code to connect a phone."
            }
        }
    }
    DisposableEffect(Unit) { onDispose { TvRemoteServer.instance?.closePairing() } }
    val bitmap = remember(code) { code?.let { QrCodeGenerator.generate(it, 640) } }
    ConnectPhoneContent(
        bitmap = bitmap?.asImageBitmap(),
        message = message,
        paired = paired,
        onNewCode = { generation++ },
        onForget = { TvRemoteServer.instance?.revoke(); paired = false; generation++ },
        initialFocusRequester = initialFocusRequester,
    )
}

@Composable
internal fun ConnectPhoneContent(
    bitmap: ImageBitmap?,
    message: String,
    paired: Boolean,
    onNewCode: () -> Unit,
    onForget: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsDetailHeader(title = "Connect phone", subtitle = "Control TV playback from Nuvio Mobile")
        // Keep the QR beside the action so it fits within the TV settings pane.
        // The scroll container also brings focused buttons into view at larger font sizes.
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            bitmap?.takeUnless { paired }?.let { Image(it, "Pairing QR code", Modifier.size(220.dp)) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (paired) "Phone connected. You can control TV playback from Nuvio Mobile." else message)
                // Retain the same focus node when pairing completes or is forgotten.
                Button(onClick = if (paired) onForget else onNewCode, modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier) {
                    Text(if (paired) "Forget phone" else "New pairing code")
                }
            }
        }
    }
}
