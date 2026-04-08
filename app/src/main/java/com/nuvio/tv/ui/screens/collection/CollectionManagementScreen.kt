package com.nuvio.tv.ui.screens.collection

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.ValidationResult
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NuvioButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            contentColor = NuvioColors.TextPrimary,
            focusedContainerColor = NuvioColors.FocusBackground,
            focusedContentColor = NuvioColors.Primary
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
        content = { content() }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionManagementScreen(
    viewModel: CollectionManagementViewModel = hiltViewModel(),
    onNavigateToEditor: (String?) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { viewModel.stopQrMode() }
    }

    var exportMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(exportMessage) {
        if (exportMessage != null) {
            kotlinx.coroutines.delay(3000)
            exportMessage = null
        }
    }

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (uiState.showImportDialog) {
        ImportContent(
            importText = uiState.importText,
            importError = uiState.importError,
            onTextChange = { viewModel.updateImportText(it) },
            onImport = { viewModel.importCollections() },
            onPaste = {
                val clip = clipboardManager.getText()?.text ?: ""
                viewModel.updateImportText(clip)
            },
            onBack = { viewModel.hideImportDialog() },
            importMode = uiState.importMode,
            onModeChange = { viewModel.setImportMode(it) },
            importUrl = uiState.importUrl,
            onUrlChange = { viewModel.updateImportUrl(it) },
            onFetchUrl = { viewModel.fetchUrl() },
            validationResult = uiState.validationResult,
            isLoadingImport = uiState.isLoadingImport,
            onValidate = { viewModel.validateCurrentText() },
            onPickFile = {
                scope.launch {
                    viewModel.loadFromFile(context)
                }
            },
            onConfirmImport = { viewModel.confirmImport() }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Collections",
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            val newButtonFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                repeat(3) { withFrameNanos { } }
                try { newButtonFocusRequester.requestFocus() } catch (_: Exception) {}
            }
            var showCopied by remember { mutableStateOf(false) }
            LaunchedEffect(showCopied) {
                if (showCopied) {
                    kotlinx.coroutines.delay(2000)
                    showCopied = false
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uiState.collections.isNotEmpty()) {
                    NuvioButton(onClick = {
                        scope.launch {
                            try {
                                val json = viewModel.getExportJson()
                                withContext(Dispatchers.IO) {
                                    val resolver = context.contentResolver
                                    // Delete existing file first
                                    val existingUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                                    resolver.delete(
                                        existingUri,
                                        "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
                                        arrayOf("nuvio-collections.json")
                                    )
                                    // Write new file
                                    val values = android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "nuvio-collections.json")
                                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                                    }
                                    val uri = resolver.insert(existingUri, values)
                                    uri?.let { resolver.openOutputStream(it)?.use { os -> os.write(json.toByteArray()) } }
                                }
                                exportMessage = "Saved to Downloads"
                            } catch (_: Exception) {
                                exportMessage = "Export failed"
                            }
                        }
                    }) {
                        Text(exportMessage ?: "Export File")
                    }
                    NuvioButton(onClick = {
                        val json = viewModel.exportCollections()
                        clipboardManager.setText(AnnotatedString(json))
                        showCopied = true
                    }) {
                        Text(if (showCopied) "Copied!" else "Copy JSON")
                    }
                }
                NuvioButton(onClick = { viewModel.showImportDialog() }) {
                    Text("Import")
                }
                NuvioButton(
                    onClick = { onNavigateToEditor(null) },
                    modifier = Modifier.focusRequester(newButtonFocusRequester)
                ) {
                    Text("New Collection")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ManageFromPhoneCard(onClick = { viewModel.startQrMode() })

        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.collections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No collections yet. Create one to organize your catalogs.",
                    color = NuvioColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = uiState.collections,
                    key = { _, item -> item.id }
                ) { index, collection ->
                    CollectionListItem(
                        collection = collection,
                        isFirst = index == 0,
                        isLast = index == uiState.collections.size - 1,
                        onEdit = { onNavigateToEditor(collection.id) },
                        onDelete = { viewModel.deleteCollection(collection.id) },
                        onMoveUp = { viewModel.moveUp(index) },
                        onMoveDown = { viewModel.moveDown(index) }
                    )
                }
            }
        }
    }

    if (uiState.isQrModeActive) {
        QrCodeOverlay(
            qrBitmap = uiState.qrCodeBitmap,
            serverUrl = uiState.serverUrl,
            onClose = { viewModel.stopQrMode() },
            hasPendingChange = uiState.pendingCollectionChange != null
        )
    }

    if (uiState.pendingCollectionChange != null) {
        ConfirmCollectionChangesDialog(
            pending = uiState.pendingCollectionChange!!,
            onConfirm = { viewModel.confirmPendingChange() },
            onReject = { viewModel.rejectPendingChange() }
        )
    }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ImportContent(
    importText: String,
    importError: String?,
    onTextChange: (String) -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onBack: () -> Unit,
    importMode: ImportMode,
    onModeChange: (ImportMode) -> Unit,
    importUrl: String,
    onUrlChange: (String) -> Unit,
    onFetchUrl: () -> Unit,
    validationResult: ValidationResult?,
    isLoadingImport: Boolean,
    onValidate: () -> Unit,
    onPickFile: () -> Unit,
    onConfirmImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 48.dp, end = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Import Collections",
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NuvioButton(onClick = onBack) { Text("Cancel") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Text(
                    text = "Import collections from JSON. Catalogs from addons you don't have installed will show a warning.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Mode tabs
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportMode.entries.forEach { mode ->
                        val label = when (mode) {
                            ImportMode.PASTE -> "Paste JSON"
                            ImportMode.FILE -> "From File"
                            ImportMode.URL -> "From URL"
                        }
                        if (mode == importMode) {
                            Button(
                                onClick = { onModeChange(mode) },
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioColors.Primary,
                                    contentColor = NuvioColors.Background,
                                    focusedContainerColor = NuvioColors.Primary,
                                    focusedContentColor = NuvioColors.Background
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                            ) {
                                Text(label)
                            }
                        } else {
                            NuvioButton(onClick = { onModeChange(mode) }) {
                                Text(label)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                when (importMode) {
                    ImportMode.PASTE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NuvioButton(onClick = onPaste) { Text("Paste from Clipboard") }
                            if (importText.isNotBlank()) {
                                NuvioButton(onClick = onValidate) { Text("Validate") }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            colors = SurfaceDefaults.colors(containerColor = NuvioColors.BackgroundElevated),
                            border = Border(
                                border = BorderStroke(1.dp, if (importError != null) NuvioColors.Error else NuvioColors.Border),
                                shape = RoundedCornerShape(12.dp)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            Box(modifier = Modifier.padding(12.dp)) {
                                BasicTextField(
                                    value = importText,
                                    onValueChange = onTextChange,
                                    modifier = Modifier.fillMaxSize(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = NuvioColors.TextPrimary
                                    ),
                                    cursorBrush = SolidColor(NuvioColors.Primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    decorationBox = { innerTextField ->
                                        if (importText.isEmpty()) {
                                            Text(
                                                text = "Paste collections JSON here...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = NuvioColors.TextTertiary
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }
                    }

                    ImportMode.FILE -> {
                        Text(
                            text = "Reads nuvio-collections.json from the Downloads folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextTertiary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        NuvioButton(onClick = onPickFile) { Text("Load File") }

                        if (importText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "File loaded (${importText.length} characters)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextSecondary
                            )
                        }
                    }

                    ImportMode.URL -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            colors = SurfaceDefaults.colors(containerColor = NuvioColors.BackgroundElevated),
                            border = Border(
                                border = BorderStroke(1.dp, NuvioColors.Border),
                                shape = RoundedCornerShape(12.dp)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                                BasicTextField(
                                    value = importUrl,
                                    onValueChange = onUrlChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = NuvioColors.TextPrimary
                                    ),
                                    cursorBrush = SolidColor(NuvioColors.Primary),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    decorationBox = { innerTextField ->
                                        if (importUrl.isEmpty()) {
                                            Text(
                                                text = "https://example.com/collections.json",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = NuvioColors.TextTertiary
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        NuvioButton(onClick = onFetchUrl) {
                            Text(if (isLoadingImport) "Fetching..." else "Fetch URL")
                        }

                        if (isLoadingImport) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LoadingIndicator()
                        }
                    }
                }
            }

            // Validation result preview
            if (validationResult != null && validationResult.valid) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        colors = SurfaceDefaults.colors(containerColor = NuvioColors.BackgroundCard),
                        border = Border(
                            border = BorderStroke(1.dp, NuvioColors.Primary),
                            shape = RoundedCornerShape(12.dp)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Valid JSON",
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioColors.Primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${validationResult.collectionCount} collection${if (validationResult.collectionCount != 1) "s" else ""}, " +
                                        "${validationResult.folderCount} folder${if (validationResult.folderCount != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextSecondary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            NuvioButton(onClick = onConfirmImport) {
                                Text("Confirm Import")
                            }
                        }
                    }
                }
            }

            if (importError != null) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = importError,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.Error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionListItem(
    collection: Collection,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(containerColor = NuvioColors.BackgroundCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = collection.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = "${collection.folders.size} folder${if (collection.folders.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = onMoveUp,
                    enabled = !isFirst,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, "Move Up")
                }

                Button(
                    onClick = onMoveDown,
                    enabled = !isLast,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, "Move Down")
                }

                Button(
                    onClick = onEdit,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.Edit, "Edit")
                }

                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.Error
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.Delete, "Delete")
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageFromPhoneCard(onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(18.dp)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) NuvioColors.Secondary else NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Manage from Phone",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioColors.TextPrimary
                    )
                    Text(
                        text = "Scan QR code to manage collections from your phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioColors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QrCodeOverlay(
    qrBitmap: Bitmap?,
    serverUrl: String?,
    onClose: () -> Unit,
    hasPendingChange: Boolean = false
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(hasPendingChange) {
        if (!hasPendingChange) {
            focusRequester.requestFocus()
        }
    }

    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Scan with your phone to manage collections",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(220.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (serverUrl != null) {
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                onClick = onClose,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuvioColors.Surface,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(50)
                    )
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = NuvioColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Close",
                        color = NuvioColors.TextPrimary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmCollectionChangesDialog(
    pending: PendingCollectionChangeInfo,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BackHandler { onReject() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = { },
            modifier = Modifier.width(480.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioColors.SurfaceVariant
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Apply Collection Changes?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Changes from your phone are ready to apply.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = NuvioColors.Surface,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        text = "${pending.proposedCollectionCount} collection${if (pending.proposedCollectionCount != 1) "s" else ""} will replace your current collections.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (pending.isApplying) {
                    LoadingIndicator(modifier = Modifier.size(36.dp))
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            onClick = onReject,
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = NuvioColors.Surface,
                                focusedContainerColor = NuvioColors.FocusBackground
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = RoundedCornerShape(50)
                                )
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = NuvioColors.TextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Reject",
                                    color = NuvioColors.TextPrimary
                                )
                            }
                        }

                        Surface(
                            onClick = onConfirm,
                            modifier = Modifier.focusRequester(focusRequester),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = NuvioColors.Secondary,
                                focusedContainerColor = NuvioColors.SecondaryVariant
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = RoundedCornerShape(50)
                                )
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Text(
                                text = "Apply",
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                color = NuvioColors.OnSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
