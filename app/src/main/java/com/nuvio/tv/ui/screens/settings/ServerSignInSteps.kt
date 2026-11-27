@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

/** A sign-in code approved on another device: the instructions, a QR code for [link], the code and the page to type it on. */
@Composable
internal fun ColumnScope.ServerSignInCode(instructions: String, link: String, code: String, page: String, waiting: String) {
    val qr = remember(link) { runCatching { QrCodeGenerator.generate(link, 420, margin = 1) }.getOrNull() }
    Text(
        text = instructions,
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    if (qr != null) {
        Image(
            bitmap = qr.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_qr_code),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(196.dp),
            contentScale = ContentScale.Fit
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = page,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary,
            textAlign = TextAlign.Center
        )
    }
    ServerSignInStatus(waiting, loading = true)
}

/** A list to pick one entry from (servers, users, profiles) that scrolls when long; focus starts on [focusIndex], none when negative. */
@Composable
internal fun <T> ColumnScope.ServerSignInPicker(
    items: List<T>,
    focusIndex: Int = 0,
    footer: @Composable () -> Unit = {},
    row: @Composable (item: T, modifier: Modifier) -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusIndex) { if (focusIndex >= 0) focus.requestFocusAfterFrames() }
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        items.forEachIndexed { index, item ->
            row(item, if (index == focusIndex) Modifier.focusRequester(focus) else Modifier)
        }
        footer()
    }
}

/** A PIN entry with the back (or cancel) and connect buttons; the field empties after a wrong PIN or a new [key]. */
@Composable
internal fun ServerSignInPin(
    key: Any?,
    hint: String,
    maxLength: Int,
    wrongText: String?,
    busy: Boolean,
    backText: String,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    error: @Composable () -> Unit
) {
    var pin by remember(key) { mutableStateOf("") }
    val submit = { if (!busy && pin.isNotBlank()) onSubmit(pin) }
    LaunchedEffect(wrongText) { if (wrongText != null) pin = "" }
    InputField(
        value = pin,
        onValueChange = { value -> pin = value.filter(Char::isDigit).take(maxLength) },
        placeholder = hint,
        keyboardType = KeyboardType.NumberPassword,
        isPassword = true,
        imeAction = ImeAction.Done,
        onImeAction = submit,
        modifier = Modifier.fillMaxWidth()
    )
    if (wrongText != null) ServerSignInStatus(wrongText, error = true)
    error()
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = backText, onClick = onBack, enabled = !busy)
        SettingsDialogActionButton(
            text = stringResource(if (busy) R.string.servers_connecting else R.string.servers_connect),
            onClick = submit,
            primary = true,
            enabled = !busy && pin.isNotBlank()
        )
    }
}

@Composable
internal fun ServerSignInStatus(text: String, loading: Boolean = false, error: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) LoadingIndicator(modifier = Modifier.size(NuvioTheme.spacing.lg))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}
