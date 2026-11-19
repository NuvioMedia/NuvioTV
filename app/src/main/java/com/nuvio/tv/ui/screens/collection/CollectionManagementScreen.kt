package com.nuvio.tv.ui.screens.collection

import android.os.Build
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.TvReorderList
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionManagementScreen(
    viewModel: CollectionManagementViewModel = hiltViewModel(),
    onNavigateToEditor: (String?) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lastFocusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    var collectionToDelete by remember { mutableStateOf<Collection?>(null) }
    val deleteDialogFocusRequester = remember { FocusRequester() }

    LaunchedEffect(collectionToDelete) {
        if (collectionToDelete != null) {
            repeat(3) { withFrameNanos { } }
            try {
                deleteDialogFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
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
            onPaste = {},
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
            .padding(top = NuvioTheme.spacing.xxxl, start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.collections_header),
                style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content),
                color = NuvioTheme.colors.TextPrimary,
            )
            val newButtonFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                repeat(3) { withFrameNanos { } }
                val targetRequester = lastFocusedId?.let { itemFocusRequesters[it] } ?: newButtonFocusRequester
                try { targetRequester.requestFocus() } catch (_: Exception) {}
            }
            val headerActionsEnabled = uiState.movingCollectionKey == null
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                if (uiState.collections.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    NuvioButton(
                        onClick = {
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
                                    exportMessage = "saved"
                                } catch (_: Exception) {
                                    exportMessage = "failed"
                                }
                            }
                        },
                        enabled = headerActionsEnabled
                    ) {
                        Text(exportMessage?.let {
                            when (it) {
                                "saved" -> stringResource(R.string.collections_saved_downloads)
                                "failed" -> stringResource(R.string.collections_export_failed)
                                else -> it
                            }
                        } ?: stringResource(R.string.collections_export_file))
                    }
                }
                NuvioButton(
                    onClick = { viewModel.showImportDialog() },
                    enabled = headerActionsEnabled
                ) {
                    Text(stringResource(R.string.collections_import))
                }
                NuvioButton(
                    onClick = {
                        lastFocusedId = null
                        onNavigateToEditor(null)
                    },
                    modifier = Modifier.focusRequester(newButtonFocusRequester),
                    enabled = headerActionsEnabled
                ) {
                    Text(stringResource(R.string.collections_new))
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

        if (uiState.collections.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.collections_empty),
                    color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            TvReorderList(
                items = uiState.collections,
                key = { it.id },
                canMove = { uiState.collections.size > 1 && uiState.movingCollectionKey == null },
                movingKey = uiState.movingCollectionKey,
                onStartMove = viewModel::enterCollectionMoveMode,
                onMoveBy = viewModel::moveCollectionDuringDrag,
                onConfirmMove = { viewModel.confirmCollectionMove() },
                onCancelMove = viewModel::cancelCollectionMove,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { collection, isMoving, onStartMove, onConfirmMove ->
                val editFocusRequester = itemFocusRequesters.getOrPut(collection.id) { FocusRequester() }
                CollectionListItem(
                    collection = collection,
                    isMoving = isMoving,
                    canMove = uiState.collections.size > 1,
                    actionsEnabled = uiState.movingCollectionKey == null || isMoving,
                    editFocusRequester = editFocusRequester,
                    onEdit = {
                        lastFocusedId = collection.id
                        onNavigateToEditor(collection.id)
                    },
                    onDelete = { collectionToDelete = collection },
                    onStartMove = onStartMove,
                    onConfirmMove = onConfirmMove
                )
            }
        }
    }

    collectionToDelete?.let { collection ->
        NuvioDialog(
            onDismiss = { collectionToDelete = null },
            title = stringResource(R.string.collections_delete_confirm_title),
            subtitle = stringResource(R.string.collections_delete_confirm_subtitle, collection.title),
            suppressFirstKeyUp = false
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md, Alignment.End)
            ) {
                Button(
                    onClick = { collectionToDelete = null },
                    modifier = Modifier
                        .focusRequester(deleteDialogFocusRequester)
                        .semantics { role = Role.Button }
                ) {
                    Text(stringResource(R.string.collections_cancel))
                }
                Button(
                    onClick = {
                        viewModel.deleteCollection(collection.id)
                        collectionToDelete = null
                    },
                    modifier = Modifier.semantics { role = Role.Button }
                ) {
                    Text(stringResource(R.string.collections_delete_confirm))
                }
            }
        }
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
    val fileTabFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(3) { withFrameNanos { } }
        fileTabFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = NuvioTheme.spacing.xxxl, start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.collections_import_header),
                style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content),
                color = NuvioTheme.colors.TextPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_cancel)) }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.none)
        ) {
            item {
                Text(
                    text = stringResource(R.string.collections_import_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

                // Mode tabs
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    ImportMode.entries.filter { it != ImportMode.PASTE }.forEach { mode ->
                        val label = when (mode) {
                            ImportMode.PASTE -> stringResource(R.string.collections_mode_paste)
                            ImportMode.FILE -> stringResource(R.string.collections_mode_file)
                            ImportMode.URL -> stringResource(R.string.collections_mode_url)
                        }
                        val tabModifier = if (mode == ImportMode.FILE) {
                            Modifier.focusRequester(fileTabFocusRequester)
                        } else {
                            Modifier
                        }
                        if (mode == importMode) {
                            Button(
                                onClick = { onModeChange(mode) },
                                modifier = tabModifier.semantics {
                                    role = Role.Tab
                                    this.selected = true
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioTheme.colors.Primary,
                                    contentColor = NuvioTheme.colors.Background,
                                    focusedContainerColor = NuvioTheme.colors.Primary,
                                    focusedContentColor = NuvioTheme.colors.Background
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                            ) {
                                Text(label)
                            }
                        } else {
                            NuvioButton(
                                onClick = { onModeChange(mode) },
                                modifier = tabModifier.semantics {
                                    role = Role.Tab
                                    this.selected = false
                                }
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            }

            item {
                when (importMode) {
                    ImportMode.PASTE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                            NuvioButton(onClick = onPaste) { Text(stringResource(R.string.collections_paste_clipboard)) }
                            if (importText.isNotBlank()) {
                                NuvioButton(onClick = onValidate) { Text(stringResource(R.string.collections_validate)) }
                            }
                        }

                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

                        Surface(
                            shape = RoundedCornerShape(NuvioTheme.radii.md),
                            colors = SurfaceDefaults.colors(containerColor = NuvioTheme.colors.BackgroundElevated),
                            border = Border(
                                border = BorderStroke(NuvioTheme.spacing.hairline, if (importError != null) NuvioTheme.colors.Error else NuvioTheme.colors.Border),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            Box(modifier = Modifier.padding(NuvioTheme.spacing.md)) {
                                BasicTextField(
                                    value = importText,
                                    onValueChange = onTextChange,
                                    modifier = Modifier.fillMaxSize(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = NuvioTheme.colors.TextPrimary,
                                            textDirection = TextDirection.Content
                                    ),
                                    cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    decorationBox = { innerTextField ->
                                        if (importText.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.collections_paste_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = NuvioTheme.colors.TextTertiary
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
                            text = stringResource(R.string.collections_file_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary
                        )
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        NuvioButton(onClick = onPickFile) { Text(stringResource(R.string.collections_load_file)) }

                        if (importText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                            Text(
                                text = stringResource(R.string.collections_file_loaded, importText.length),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }

                    ImportMode.URL -> {
                        Surface(
                            shape = RoundedCornerShape(NuvioTheme.radii.md),
                            colors = SurfaceDefaults.colors(containerColor = NuvioTheme.colors.BackgroundElevated),
                            border = Border(
                                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(NuvioTheme.spacing.huge)
                        ) {
                            Box(modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md), contentAlignment = Alignment.CenterStart) {
                                BasicTextField(
                                    value = importUrl,
                                    onValueChange = onUrlChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = NuvioTheme.colors.TextPrimary,
                                            textDirection = TextDirection.Content
                                    ),
                                    cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    decorationBox = { innerTextField ->
                                        if (importUrl.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.collections_import_url_placeholder),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = NuvioTheme.colors.TextTertiary
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

                        NuvioButton(onClick = onFetchUrl) {
                            Text(if (isLoadingImport) stringResource(R.string.collections_fetching) else stringResource(R.string.collections_fetch_url))
                        }

                        if (isLoadingImport) {
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                            LoadingIndicator()
                        }
                    }
                }
            }

            // Validation result preview
            if (validationResult != null && validationResult.valid) {
                item {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    Surface(
                        shape = RoundedCornerShape(NuvioTheme.radii.md),
                        colors = SurfaceDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
                        border = Border(
                            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Primary),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(NuvioTheme.spacing.lg)) {
                            Text(
                                text = stringResource(R.string.collections_valid_json),
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioTheme.colors.Primary
                            )
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                            Text(
                                text = stringResource(R.string.collections_valid_summary, validationResult.collectionCount, validationResult.folderCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary
                            )
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                            NuvioButton(onClick = onConfirmImport) {
                                Text(stringResource(R.string.collections_confirm_import))
                            }
                        }
                    }
                }
            }

            if (importError != null) {
                item {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    Text(
                        text = importError,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error
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
    isMoving: Boolean,
    canMove: Boolean,
    actionsEnabled: Boolean,
    editFocusRequester: FocusRequester? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStartMove: () -> Unit,
    onConfirmMove: () -> Unit
) {
    val cardColor by animateColorAsState(
        targetValue = if (isMoving) {
            NuvioTheme.colors.FocusBackground
        } else {
            NuvioTheme.colors.BackgroundCard
        },
        animationSpec = com.nuvio.tv.ui.theme.NuvioMotion.focusTween(),
        label = "collectionCardColor",
    )
    val cardBorder = if (isMoving) {
        Border(
            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
            shape = RoundedCornerShape(NuvioTheme.radii.md)
        )
    } else {
        Border.None
    }
    Surface(
        shape = RoundedCornerShape(NuvioTheme.radii.md),
        colors = SurfaceDefaults.colors(containerColor = cardColor),
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = collection.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDirection = collection.title.contentTextDirection()
                    ),
                    color = NuvioTheme.colors.TextPrimary,
                )
                val summary = buildString {
                    append(stringResource(R.string.collections_folder_count, collection.folders.size))
                    if (collection.pinToTop) {
                        append(" · ")
                        append(stringResource(R.string.collections_pinned))
                    }
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Button(
                    onClick = if (isMoving) onConfirmMove else onStartMove,
                    enabled = isMoving || (canMove && actionsEnabled),
                    colors = ButtonDefaults.colors(
                        containerColor = if (isMoving) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
                        contentColor = if (isMoving) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextSecondary,
                        focusedContainerColor = if (isMoving) NuvioTheme.colors.SecondaryVariant else NuvioTheme.colors.FocusBackground,
                        focusedContentColor = if (isMoving) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Text(
                        text = stringResource(
                            if (isMoving) R.string.catalog_order_done else R.string.catalog_order_move,
                        ),
                    )
                }

                Button(
                    onClick = onEdit,
                    enabled = actionsEnabled && !isMoving,
                    modifier = if (editFocusRequester != null) Modifier.focusRequester(editFocusRequester) else Modifier,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextSecondary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Icon(Icons.Default.Edit, stringResource(R.string.cd_edit))
                }

                Button(
                    onClick = onDelete,
                    enabled = actionsEnabled && !isMoving,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextSecondary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.Error
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Icon(Icons.Default.Delete, stringResource(R.string.cd_delete))
                }
            }
        }
    }
}
