package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R

@Composable
internal fun ShuffleButton(
    active: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit
) {
    Button(
        onClick = { if (!pending) onClick() },
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { up = FocusRequester.Cancel }
            .onFocusChanged { if (it.isFocused) onFocused() }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (active) Icons.Default.Stop else Icons.Default.Shuffle, null, Modifier.size(18.dp))
            Text(stringResource(if (active) R.string.shuffle_stop else R.string.random_episode_title))
        }
    }
}
