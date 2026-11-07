package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog

/** Shown before built-in Usenet is first turned on in a profile: where it connects, and what that means. */
@Composable
internal fun BuiltInUsenetNoticeDialog(onTurnOn: () -> Unit, onDismiss: () -> Unit) {
    NuvioDialog(title = stringResource(R.string.usenet_builtin_addon_title),
        subtitle = stringResource(R.string.usenet_notice_body), onDismiss = onDismiss) {
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(text = stringResource(R.string.usenet_notice_turn_on), primary = true, onClick = onTurnOn)
        }
    }
}
