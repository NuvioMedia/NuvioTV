package com.nuvio.tv.ui.screens.party

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.party.formatPartyCode
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun PartySettingsContent(
    onOpen: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
    viewModel: PartyViewModel = hiltViewModel(),
) {
    val state by viewModel.party.state.collectAsStateWithLifecycle()
    val canUse by viewModel.party.canUse.collectAsStateWithLifecycle()
    val party = state.party
    val rowModifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.party_title),
            subtitle = stringResource(R.string.party_settings_description),
        )
        SettingsGroupCard {
            if (party != null) {
                SettingsActionRow(
                    title = party.media.displayTitle(),
                    subtitle = stringResource(R.string.party_seats, party.members.size, party.maxMembers),
                    value = formatPartyCode(party.code),
                    onClick = onOpen,
                    leadingIcon = Icons.Rounded.Groups,
                    modifier = rowModifier,
                )
            } else {
                SettingsActionRow(
                    title = stringResource(R.string.party_join_title),
                    subtitle = stringResource(if (canUse) R.string.party_join_description else R.string.party_sign_in_required),
                    onClick = onOpen,
                    leadingIcon = Icons.Rounded.Groups,
                    modifier = rowModifier,
                )
            }
        }
    }
}
