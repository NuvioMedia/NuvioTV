package com.nuvio.tv.ui.screens.player.autosync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

    /**
     * One AutoSync or audio sync run still searching for [url]. Compared by identity, so a run
     * that ends late can't clear the state of the run that replaced it.
     */
    class Syncing(val url: String, val audio: Boolean)

    private val _syncing = MutableStateFlow<Syncing?>(null)
    val syncing: StateFlow<Syncing?> = _syncing.asStateFlow()

    fun startSyncing(subtitleUrl: String, audio: Boolean): Syncing =
        Syncing(subtitleUrl, audio).also { _syncing.value = it }

    fun stopSyncing(run: Syncing?) {
        if (run != null) _syncing.compareAndSet(run, null)
    }

    /** Whether the audio sync, not embedded AutoSync, produced the timing marked in [url]. */
    private val _byAudio = MutableStateFlow(false)
    val byAudio: StateFlow<Boolean> = _byAudio.asStateFlow()

    fun mark(subtitleUrl: String, audio: Boolean = false) {
        _syncing.value = null
        _byAudio.value = audio
        _url.value = subtitleUrl
    }

    fun clear() {
        _url.value = null
    }
}

/**
 * Chip for the selected add-on subtitle in the subtitle list: a spinning cogwheel with
 * "Auto syncing" or "Audio syncing" while a sync runs, then "Auto synced" or "Audio synced";
 * empty otherwise.
 */
@Composable
internal fun AutoSyncedChip(subtitleUrl: String, selected: Boolean) {
    val syncedUrl by AutoSyncSyncedSubtitle.url.collectAsState()
    val byAudio by AutoSyncSyncedSubtitle.byAudio.collectAsState()
    val syncing by AutoSyncSyncedSubtitle.syncing.collectAsState()
    if (!selected) return
    val run = syncing?.takeIf { it.url == subtitleUrl }
    val label = when {
        run?.audio == true -> R.string.autosync_label_audio_syncing
        run != null -> R.string.autosync_label_syncing
        syncedUrl == subtitleUrl && byAudio -> R.string.autosync_label_audio_synced
        syncedUrl == subtitleUrl -> R.string.autosync_label_synced
        else -> return
    }

    val contentColor = NuvioTheme.colors.OnSecondary.copy(alpha = 0.9f)
    Row(
        modifier = Modifier
            .background(NuvioTheme.colors.OnSecondary.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (run != null) {
            SpinningCogwheel(contentColor)
        } else {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

@Composable
private fun SpinningCogwheel(tint: Color) {
    val angle by rememberInfiniteTransition(label = "autosync_cog").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_200, easing = LinearEasing)),
        label = "autosync_cog_angle",
    )
    Icon(
        imageVector = Icons.Default.Settings,
        contentDescription = null,
        tint = tint,
        // Rotated in the draw phase only, so the spin doesn't recompose the list.
        modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = angle },
    )
}
