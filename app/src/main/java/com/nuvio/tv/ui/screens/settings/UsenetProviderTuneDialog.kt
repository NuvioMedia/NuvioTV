@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.usenet.ProviderTestResult
import com.nuvio.tv.core.usenet.TuneFailure
import com.nuvio.tv.core.usenet.TuneOutcome
import com.nuvio.tv.core.usenet.TuneProgress
import com.nuvio.tv.core.usenet.TuneReading
import com.nuvio.tv.core.usenet.UsenetProvider
import com.nuvio.tv.core.usenet.messageRes
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

/** Before, during and after a provider tuning run; [provider] is the saved one, for comparison. */
@Composable
internal fun ProviderTuneDialog(state: ProviderTuneUi, provider: UsenetProvider?, onStart: () -> Unit, onStop: () -> Unit,
    onApply: () -> Unit, onDismiss: () -> Unit) {
    val outcome = state.outcome
    val notStarted = !state.running && !state.stopped && outcome == null
    val canApply = provider != null && when (outcome) {
        is TuneOutcome.Success -> changes(outcome, provider)
        is TuneOutcome.Failed -> lowered(outcome, provider) != null
        null -> false
    }
    // The buttons change with the phase; the new main button takes the focus, or the remote has none.
    val main = remember { FocusRequester() }
    LaunchedEffect(state.running, state.stopped, outcome, canApply) {
        // After the frame that swaps the buttons: asked in the same frame, the focus is lost with the pressed button.
        repeat(2) { withFrameNanos { }; runCatching { main.requestFocus() } }
    }
    NuvioDialog(title = stringResource(R.string.usenet_provider_tune_title, state.providerName),
        subtitle = if (notStarted) stringResource(R.string.usenet_provider_tune_intro) else null,
        onDismiss = { if (state.running) onStop() else onDismiss() }) {
        val readings = (outcome as? TuneOutcome.Success)?.result?.readings ?: state.progress?.readings.orEmpty()
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.running) Text(progressText(state.progress), color = NuvioTheme.colors.TextPrimary)
            if (outcome is TuneOutcome.Success && provider != null) TuneSummary(outcome, provider)
            if (outcome is TuneOutcome.Failed) FailedSummary(outcome, provider)
            if (state.stopped && !state.running && outcome == null) {
                Text(stringResource(R.string.usenet_provider_tune_stopped), color = NuvioTheme.colors.TextSecondary)
            }
            readings.forEach { reading -> Text(readingText(reading), style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary) }
        }
        // One main button in one place for every phase, changing only its text and action: replacing
        // the pressed button with another one leaves the new one focused but not drawn as focused.
        val secondary: Pair<Int, () -> Unit>? = when {
            state.running -> null
            canApply -> R.string.action_cancel to onDismiss
            outcome != null || state.stopped -> R.string.usenet_provider_tune_again to onStart
            else -> R.string.action_cancel to onDismiss
        }
        val primary: Pair<Int, () -> Unit> = when {
            state.running -> R.string.usenet_provider_tune_stop to onStop
            canApply -> R.string.usenet_provider_tune_apply to onApply
            outcome != null || state.stopped -> R.string.usenet_provider_tune_close to onDismiss
            else -> R.string.usenet_provider_tune_start to onStart
        }
        SettingsDialogActionRow {
            secondary?.let { (text, action) -> SettingsDialogActionButton(text = stringResource(text), onClick = action) }
            SettingsDialogActionButton(text = stringResource(primary.first), primary = true, onClick = primary.second,
                modifier = Modifier.focusRequester(main))
        }
    }
}

private fun changes(outcome: TuneOutcome.Success, provider: UsenetProvider) = outcome.result.let {
    it.port != provider.port || it.tls != provider.tls || it.connections != provider.connections
}

/** The allowance a failed run still recommends: under the limit it found, when the saved one is above. */
internal fun lowered(outcome: TuneOutcome.Failed, provider: UsenetProvider) =
    outcome.allowance?.takeIf { it < provider.connections }

/** Why nothing was measured, and what the limit check found before that. */
@Composable
private fun FailedSummary(outcome: TuneOutcome.Failed, provider: UsenetProvider?) {
    Text(stringResource(failureRes(outcome)), color = NuvioTheme.colors.TextPrimary)
    val limit = outcome.limit ?: return
    Text(stringResource(R.string.usenet_provider_tune_limit, limit), color = NuvioTheme.colors.TextPrimary)
    if (provider == null) return
    if (provider.connections > limit) {
        Text(stringResource(R.string.usenet_provider_tune_over_limit, provider.connections), color = NuvioTheme.colors.TextPrimary)
    }
    lowered(outcome, provider)?.let { allowance ->
        Text(stringResource(R.string.usenet_provider_tune_lower, allowance, provider.connections), color = NuvioTheme.colors.TextPrimary)
    }
}

@Composable
private fun TuneSummary(outcome: TuneOutcome.Success, provider: UsenetProvider) {
    val result = outcome.result
    val context = LocalContext.current
    Text(if (result.limit != null) stringResource(R.string.usenet_provider_tune_limit, result.limit)
        else stringResource(R.string.usenet_provider_tune_no_limit, result.tried), color = NuvioTheme.colors.TextPrimary)
    if (result.limit != null && provider.connections > result.limit) {
        Text(stringResource(R.string.usenet_provider_tune_over_limit, provider.connections), color = NuvioTheme.colors.TextPrimary)
    }
    Text(stringResource(R.string.usenet_provider_tune_fastest, result.fastestConnections, mbps(result.readings
        .filter { it.port == result.port && it.tls == result.tls && it.connections == result.fastestConnections }
        .map { it.mbps }.average())), color = NuvioTheme.colors.TextPrimary)
    Text(if (changes(outcome, provider)) stringResource(R.string.usenet_provider_tune_recommended, result.port, result.connections,
            provider.port, provider.connections)
        else stringResource(R.string.usenet_provider_tune_unchanged), color = NuvioTheme.colors.TextPrimary)
    Text(stringResource(R.string.usenet_provider_tune_data, Formatter.formatShortFileSize(context, result.downloadedBytes)),
        color = NuvioTheme.colors.TextSecondary)
}

@Composable
private fun progressText(progress: TuneProgress?): String = when (progress?.phase) {
    null -> stringResource(R.string.usenet_provider_tune_preparing)
    TuneProgress.Phase.LIMIT -> stringResource(R.string.usenet_provider_tune_limit_running, progress.connections)
    TuneProgress.Phase.PORT -> stringResource(R.string.usenet_provider_tune_port_running, progress.port, progress.connections)
    TuneProgress.Phase.CONNECTIONS -> stringResource(R.string.usenet_provider_tune_connections_running, progress.connections)
    TuneProgress.Phase.WAITING -> stringResource(R.string.usenet_provider_tune_waiting, ((progress.waitMs + 999) / 1000).toInt())
}

@Composable
private fun readingText(reading: TuneReading) =
    stringResource(R.string.usenet_provider_tune_reading, reading.port, reading.connections, mbps(reading.mbps))

private fun mbps(value: Double) = String.format(Locale.getDefault(), "%.0f", value)

private fun failureRes(failure: TuneOutcome.Failed) = when (failure.reason) {
    TuneFailure.CONNECT -> (failure.connect ?: ProviderTestResult.UNREACHABLE).messageRes(anonymous = false)
    TuneFailure.TEST_DATA -> R.string.usenet_provider_tune_test_data
    TuneFailure.MISSING_ARTICLES -> R.string.usenet_provider_tune_missing
    TuneFailure.TOO_SLOW -> R.string.usenet_provider_tune_too_slow
    TuneFailure.BUSY -> R.string.usenet_provider_tune_busy
}
