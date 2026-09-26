package com.nuvio.tv.ui.screens.player.autosync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The add-on subtitle whose on-screen timing AutoSync corrected or confirmed. Cleared whenever
 * the sidecar starts or stops a subtitle, since that renders the original timing again.
 */
internal object AutoSyncSyncedSubtitle {
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    fun mark(subtitleUrl: String) {
        _url.value = subtitleUrl
    }

    fun clear() {
        _url.value = null
    }
}

/** "Auto synced" chip for the selected add-on subtitle in the subtitle list; empty otherwise. */
@Composable
internal fun AutoSyncedChip(subtitleUrl: String, selected: Boolean) {
    val syncedUrl by AutoSyncSyncedSubtitle.url.collectAsState()
    if (!selected || syncedUrl != subtitleUrl) return

    val contentColor = NuvioTheme.colors.OnSecondary.copy(alpha = 0.9f)
    Row(
        modifier = Modifier
            .background(NuvioTheme.colors.OnSecondary.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(R.string.autosync_label_synced),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
