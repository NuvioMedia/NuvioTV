package com.nuvio.tv.ui.screens.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class BrowserEntry(
    val file: File?,
    val label: String,
    val isDirectory: Boolean,
    val isParent: Boolean = false
)

private fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

/** Internal storage first, then any other mounted volume (USB drives, SD cards). */
private fun storageRoots(): List<File> {
    val internal = Environment.getExternalStorageDirectory()
    val others = File("/storage").listFiles().orEmpty()
        .filter { it.isDirectory && it.name != "self" && it.name != "emulated" && it.canRead() }
        .sortedBy { it.name }
    return listOf(internal) + others
}

private fun listBrowserEntries(dir: File?, roots: List<File>, upLabel: String): List<BrowserEntry> {
    if (dir == null) {
        return roots.map { BrowserEntry(it, it.absolutePath, isDirectory = true) }
    }
    val children = dir.listFiles().orEmpty()
        .filter { !it.name.startsWith(".") }
        .filter { it.isDirectory || it.extension.lowercase() in LOCAL_SUBTITLE_EXTENSIONS }
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .map { BrowserEntry(it, it.name, it.isDirectory) }
    val parent = if (dir in roots) {
        if (roots.size > 1) BrowserEntry(null, upLabel, isDirectory = true, isParent = true) else null
    } else {
        BrowserEntry(dir.parentFile, upLabel, isDirectory = true, isParent = true)
    }
    return listOfNotNull(parent) + children
}

/**
 * D-pad friendly file browser for picking a subtitle file from device storage. Android TV boxes
 * often ship without a system file picker, so this does not depend on one.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LocalSubtitleBrowser(
    visible: Boolean,
    onFileChosen: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasAccess by remember(visible) { mutableStateOf(hasStorageAccess(context)) }

    DisposableEffect(lifecycleOwner, visible) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasAccess = hasStorageAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAccess = granted }

    val upLabel = stringResource(R.string.subtitle_browser_up)
    val roots = remember(visible, hasAccess) { if (hasAccess) storageRoots() else emptyList() }
    var currentDir by remember(visible, hasAccess) { mutableStateOf<File?>(roots.firstOrNull()) }
    var entries by remember(visible) { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    val listState = rememberLazyListState()
    val firstItemRequester = remember { FocusRequester() }

    LaunchedEffect(visible, hasAccess, currentDir, roots) {
        if (!visible || !hasAccess) return@LaunchedEffect
        entries = withContext(Dispatchers.IO) { listBrowserEntries(currentDir, roots, upLabel) }
        listState.scrollToItem(0)
    }
    LaunchedEffect(visible, hasAccess, entries) {
        if (visible) firstItemRequester.requestFocusAfterFrames()
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 52.dp, end = 52.dp, top = 36.dp, bottom = 76.dp
        )
    ) {
        Column(
            modifier = Modifier.width(560.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.subtitle_browser_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Text(
                text = currentDir?.absolutePath.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!hasAccess) {
                Text(
                    text = stringResource(R.string.subtitle_browser_permission_needed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
                BrowserCard(
                    label = stringResource(R.string.subtitle_browser_grant_access),
                    modifier = Modifier.focusRequester(firstItemRequester),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            requestAllFilesAccess(context)
                        } else {
                            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }
                )
            } else if (entries.none { !it.isDirectory && !it.isParent }) {
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.subtitle_browser_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                }
            }

            if (hasAccess && entries.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        vertical = NuvioTheme.spacing.sm
                    ),
                    modifier = Modifier.fillMaxWidth().padding(end = 0.dp)
                ) {
                    items(items = entries, key = { it.file?.absolutePath + "|" + it.isParent }) { entry ->
                        val isFirst = entry === (entries.firstOrNull { !it.isParent } ?: entries.first())
                        BrowserCard(
                            label = if (entry.isDirectory && !entry.isParent) "${entry.label}/" else entry.label,
                            modifier = if (isFirst) Modifier.focusRequester(firstItemRequester) else Modifier,
                            onClick = {
                                when {
                                    entry.isParent -> currentDir = entry.file
                                    entry.isDirectory -> currentDir = entry.file
                                    else -> entry.file?.let { onFileChosen(it.absolutePath) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun requestAllFilesAccess(context: Context) {
    val perApp = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val general = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(perApp) }
        .recoverCatching { context.startActivity(general) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowserCard(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = NuvioTheme.spacing.sm)
        )
    }
}
