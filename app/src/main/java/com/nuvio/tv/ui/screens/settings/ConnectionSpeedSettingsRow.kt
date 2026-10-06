package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.connection.ConnectionSpeedEstimator
import kotlin.math.roundToInt

/**
 * "Match streams to connection" row; state lives in ConnectionSpeedEstimator.
 */
@Composable
internal fun ConnectionSpeedSettingsRow() {
    val context = LocalContext.current
    ConnectionSpeedEstimator.ensureLoaded(context)
    val checked by ConnectionSpeedEstimator.enabled.collectAsStateWithLifecycle()
    val revision by ConnectionSpeedEstimator.revision.collectAsStateWithLifecycle()
    val estimateMbps = remember(revision) { ConnectionSpeedEstimator.estimateMbps(context) }
    val status = if (estimateMbps == null) {
        stringResource(R.string.settings_stream_connection_fit_learning)
    } else {
        stringResource(R.string.settings_stream_connection_fit_measured, estimateMbps.roundToInt())
    }

    SettingsToggleRow(
        title = stringResource(R.string.settings_stream_connection_fit_title),
        subtitle = stringResource(R.string.settings_stream_connection_fit_description) + "\n" + status,
        checked = checked,
        onToggle = { ConnectionSpeedEstimator.setEnabled(context, it) }
    )
}
