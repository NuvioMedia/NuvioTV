package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NuvioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester? = null
) {
    var isEditing by remember { mutableStateOf(false) }
    val textFieldFocusRequester = remember { FocusRequester() }
    val surfaceFocusRequester = focusRequester ?: remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isEditing) {
        if (isEditing) {
            repeat(3) { androidx.compose.runtime.withFrameNanos { } }
            try { textFieldFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Surface(
        onClick = { isEditing = true },
        modifier = modifier.focusRequester(surfaceFocusRequester),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, NuvioColors.Border),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(textFieldFocusRequester)
                    .onFocusChanged {
                        if (!it.isFocused && isEditing) {
                            isEditing = false
                            keyboardController?.hide()
                        }
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        isEditing = false
                        keyboardController?.hide()
                        surfaceFocusRequester.requestFocus()
                    }
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = NuvioColors.TextPrimary
                ),
                cursorBrush = SolidColor(if (isEditing) NuvioColors.Primary else Color.Transparent),
                decorationBox = { innerTextField ->
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextTertiary
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NuvioButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.then(if (!enabled) Modifier.alpha(0.35f) else Modifier),
        enabled = enabled,
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
fun CollectionEditorScreen(
    viewModel: CollectionEditorViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    if (uiState.showFolderEditor) {
        FolderEditorContent(
            viewModel = viewModel,
            uiState = uiState
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item(key = "header") {
            Text(
                text = if (uiState.isNew) stringResource(R.string.collections_new) else stringResource(R.string.collections_editor_edit_collection),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        item(key = "title") {
            Text(
                text = stringResource(R.string.collections_editor_row_title),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NuvioTextField(
                    value = uiState.title,
                    onValueChange = { viewModel.setTitle(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.collections_editor_placeholder_name)
                )
                val canSaveCollection = uiState.title.isNotBlank() && uiState.folders.isNotEmpty()
                NuvioButton(onClick = { viewModel.save { onBack() } }, enabled = canSaveCollection) {
                    Text(stringResource(R.string.collections_editor_save))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item(key = "backdrop") {
            Text(
                text = stringResource(R.string.collections_editor_backdrop),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            NuvioTextField(
                value = uiState.backdropImageUrl,
                onValueChange = { viewModel.setBackdropImageUrl(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.collections_editor_placeholder_backdrop)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        item(key = "pin_to_top") {
            Card(
                onClick = { viewModel.setPinToTop(!uiState.pinToTop) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(12.dp)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                scale = CardDefaults.scale(focusedScale = 1.02f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.collections_editor_pin_above),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.collections_editor_pin_above_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = uiState.pinToTop,
                        onCheckedChange = { viewModel.setPinToTop(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NuvioColors.Secondary,
                            checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f),
                            uncheckedThumbColor = NuvioColors.TextSecondary,
                            uncheckedTrackColor = NuvioColors.BackgroundCard
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item(key = "focus_glow") {
            Card(
                onClick = { viewModel.setFocusGlowEnabled(!uiState.focusGlowEnabled) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(12.dp)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                scale = CardDefaults.scale(focusedScale = 1.02f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.collections_editor_focus_glow),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.collections_editor_focus_glow_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = uiState.focusGlowEnabled,
                        onCheckedChange = { viewModel.setFocusGlowEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NuvioColors.Secondary,
                            checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f),
                            uncheckedThumbColor = NuvioColors.TextSecondary,
                            uncheckedTrackColor = NuvioColors.BackgroundCard
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item(key = "view_mode") {
            Text(
                text = stringResource(R.string.collections_editor_view_mode),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val viewModes = listOf(
                    FolderViewMode.TABBED_GRID to stringResource(R.string.collections_editor_view_mode_tabs),
                    FolderViewMode.ROWS to stringResource(R.string.collections_editor_view_mode_rows),
                    FolderViewMode.FOLLOW_LAYOUT to stringResource(R.string.collections_editor_view_mode_follow)
                )
                viewModes.forEach { (mode, label) ->
                    val isSelected = uiState.viewMode == mode
                    Button(
                        onClick = { viewModel.setViewMode(mode) },
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                            contentColor = if (isSelected) NuvioColors.Secondary else NuvioColors.TextSecondary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (isSelected) Border(
                                border = BorderStroke(2.dp, NuvioColors.Secondary),
                                shape = RoundedCornerShape(12.dp)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        Text(label)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (uiState.viewMode == FolderViewMode.TABBED_GRID) {
            item(key = "show_all_tab") {
                Card(
                    onClick = { viewModel.setShowAllTab(!uiState.showAllTab) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        focusedContainerColor = NuvioColors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.02f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.collections_editor_show_all_tab),
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.collections_editor_show_all_tab_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.showAllTab,
                            onCheckedChange = { viewModel.setShowAllTab(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NuvioColors.Secondary,
                                checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f),
                                uncheckedThumbColor = NuvioColors.TextSecondary,
                                uncheckedTrackColor = NuvioColors.BackgroundCard
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        item(key = "folders_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.collections_editor_folders),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = "${uiState.folders.size} item${if (uiState.folders.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        itemsIndexed(
            items = uiState.folders,
            key = { _, folder -> folder.id }
        ) { index, folder ->
            Box(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                FolderListItem(
                    folder = folder,
                    isFirst = index == 0,
                    isLast = index == uiState.folders.size - 1,
                    onEdit = { viewModel.editFolder(folder.id) },
                    onDelete = { viewModel.removeFolder(folder.id) },
                    onMoveUp = { viewModel.moveFolderUp(index) },
                    onMoveDown = { viewModel.moveFolderDown(index) }
                )
            }
        }

        item(key = "add_folder") {
            Box(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp)) {
                NuvioButton(onClick = { viewModel.addFolder() }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.collections_editor_add_folder))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FolderListItem(
    folder: CollectionFolder,
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
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${folder.tileShape.name.lowercase().replaceFirstChar { it.uppercase() }} - ${stringResource(R.string.collections_editor_catalog_count, folder.catalogSources.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                NuvioButton(onClick = onMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.cd_move_up), tint = if (!isFirst) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
                }
                NuvioButton(onClick = onMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.cd_move_down), tint = if (!isLast) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
                }
                NuvioButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, stringResource(R.string.cd_edit), tint = NuvioColors.TextSecondary)
                }
                NuvioButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, stringResource(R.string.cd_delete), tint = NuvioColors.TextSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FolderEditorContent(
    viewModel: CollectionEditorViewModel,
    uiState: CollectionEditorUiState
) {
    val folder = uiState.editingFolder ?: return

    if (uiState.showCatalogPicker) {
        CatalogPickerContent(
            catalogs = uiState.availableCatalogs,
            alreadyAdded = folder.catalogSources,
            onToggle = { viewModel.toggleCatalogSource(it) },
            onBack = { viewModel.hideCatalogPicker() }
        )
        return
    }

    if (uiState.showEmojiPicker) {
        EmojiPickerContent(
            selectedEmoji = folder.coverEmoji,
            onSelect = { emoji ->
                viewModel.updateFolderCoverEmoji(emoji)
                viewModel.hideEmojiPicker()
            },
            onBack = { viewModel.hideEmojiPicker() }
        )
        return
    }

    val titleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(5) { androidx.compose.runtime.withFrameNanos { } }
        try { titleFocusRequester.requestFocus() } catch (_: Exception) {}
    }

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
                text = stringResource(R.string.collections_editor_edit_folder),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NuvioButton(onClick = { viewModel.cancelFolderEdit() }) {
                    Text(stringResource(R.string.collections_cancel))
                }
                val canSaveFolder = (uiState.editingFolder?.catalogSources?.isNotEmpty() == true)
                NuvioButton(onClick = { viewModel.saveFolderEdit() }, enabled = canSaveFolder) {
                    Text(stringResource(R.string.collections_editor_save))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val catalogFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
        var pendingFocusIndex by remember { mutableStateOf(-1) }

        LaunchedEffect(pendingFocusIndex, folder.catalogSources.size) {
            if (pendingFocusIndex >= 0) {
                val targetIndex = pendingFocusIndex.coerceAtMost(folder.catalogSources.lastIndex)
                if (targetIndex >= 0) {
                    val targetSource = folder.catalogSources[targetIndex]
                    val targetKey = "${targetSource.addonId}_${targetSource.type}_${targetSource.catalogId}"
                    repeat(3) { androidx.compose.runtime.withFrameNanos { } }
                    try { catalogFocusRequesters[targetKey]?.requestFocus() } catch (_: Exception) {}
                }
                pendingFocusIndex = -1
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(stringResource(R.string.collections_editor_folder_title), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                NuvioTextField(
                    value = folder.title,
                    onValueChange = { viewModel.updateFolderTitle(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.collections_editor_placeholder_folder),
                    focusRequester = titleFocusRequester
                )
            }

            item {
                val hasEmoji = !folder.coverEmoji.isNullOrBlank()
                val coverMode = when {
                    folder.coverImageUrl != null -> "image"
                    hasEmoji -> "emoji"
                    else -> "none"
                }

                Text(stringResource(R.string.collections_editor_cover), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.clearFolderCover() },
                        colors = ButtonDefaults.colors(
                            containerColor = if (coverMode == "none") NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                            contentColor = if (coverMode == "none") NuvioColors.Secondary else NuvioColors.TextSecondary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (coverMode == "none") Border(
                                border = BorderStroke(2.dp, NuvioColors.Secondary),
                                shape = RoundedCornerShape(12.dp)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) { Text(stringResource(R.string.collections_editor_cover_none)) }

                    Button(
                        onClick = { viewModel.showEmojiPicker() },
                        colors = ButtonDefaults.colors(
                            containerColor = if (coverMode == "emoji") NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                            contentColor = if (coverMode == "emoji") NuvioColors.Secondary else NuvioColors.TextSecondary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (coverMode == "emoji") Border(
                                border = BorderStroke(2.dp, NuvioColors.Secondary),
                                shape = RoundedCornerShape(12.dp)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        if (hasEmoji) {
                            Text("${folder.coverEmoji}  ${stringResource(R.string.collections_editor_cover_emoji)}")
                        } else {
                            Text(stringResource(R.string.collections_editor_cover_emoji))
                        }
                    }

                    Button(
                        onClick = { viewModel.switchToImageMode() },
                        colors = ButtonDefaults.colors(
                            containerColor = if (coverMode == "image") NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                            contentColor = if (coverMode == "image") NuvioColors.Secondary else NuvioColors.TextSecondary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (coverMode == "image") Border(
                                border = BorderStroke(2.dp, NuvioColors.Secondary),
                                shape = RoundedCornerShape(12.dp)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) { Text(stringResource(R.string.collections_editor_cover_image_url)) }
                }

                if (coverMode == "image") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NuvioTextField(
                            value = folder.coverImageUrl ?: "",
                            onValueChange = { viewModel.updateFolderCoverImage(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = "https://..."
                        )
                        if (!folder.coverImageUrl.isNullOrBlank()) {
                            Card(
                                onClick = {},
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(56.dp),
                                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                                colors = CardDefaults.colors(containerColor = NuvioColors.BackgroundCard),
                                scale = CardDefaults.scale(focusedScale = 1f)
                            ) {
                                AsyncImage(
                                    model = folder.coverImageUrl,
                                    contentDescription = stringResource(R.string.cd_preview),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.collections_editor_focus_gif), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                NuvioTextField(
                    value = folder.focusGifUrl.orEmpty(),
                    onValueChange = { viewModel.updateFolderFocusGifUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.collections_editor_placeholder_gif)
                )

                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    onClick = { viewModel.updateFolderFocusGifEnabled(!folder.focusGifEnabled) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        focusedContainerColor = NuvioColors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    scale = CardDefaults.scale(focusedScale = 1f),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.collections_editor_play_gif), style = MaterialTheme.typography.bodyLarge, color = NuvioColors.TextPrimary)
                        Switch(
                            checked = folder.focusGifEnabled,
                            onCheckedChange = { viewModel.updateFolderFocusGifEnabled(it) }
                        )
                    }
                }
            }

            item {
                Text(stringResource(R.string.collections_editor_tile_shape), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                val shapeFocusRequesters = remember { PosterShape.entries.associateWith { FocusRequester() } }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.focusRestorer {
                        shapeFocusRequesters[folder.tileShape] ?: FocusRequester.Default
                    }
                ) {
                    PosterShape.entries.forEach { shape ->
                        val label = when (shape) {
                            PosterShape.POSTER -> stringResource(R.string.collections_editor_shape_poster)
                            PosterShape.LANDSCAPE -> stringResource(R.string.collections_editor_shape_wide)
                            PosterShape.SQUARE -> stringResource(R.string.collections_editor_shape_square)
                        }
                        val isSelected = folder.tileShape == shape
                        Button(
                            onClick = { viewModel.updateFolderTileShape(shape) },
                            modifier = Modifier.focusRequester(shapeFocusRequesters[shape]!!),
                            colors = ButtonDefaults.colors(
                                containerColor = if (isSelected) NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                                contentColor = if (isSelected) NuvioColors.Secondary else NuvioColors.TextSecondary,
                                focusedContainerColor = NuvioColors.FocusBackground,
                                focusedContentColor = NuvioColors.Primary
                            ),
                            border = ButtonDefaults.border(
                                border = if (isSelected) Border(
                                    border = BorderStroke(2.dp, NuvioColors.Secondary),
                                    shape = RoundedCornerShape(12.dp)
                                ) else Border.None,
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                        ) {
                            Text(label)
                        }
                    }
                }
            }

            item {
                Card(
                    onClick = { viewModel.updateFolderHideTitle(!folder.hideTitle) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        focusedContainerColor = NuvioColors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.02f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.collections_editor_hide_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.collections_editor_hide_title_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = folder.hideTitle,
                            onCheckedChange = { viewModel.updateFolderHideTitle(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NuvioColors.Secondary,
                                checkedTrackColor = NuvioColors.Secondary.copy(alpha = 0.3f),
                                uncheckedThumbColor = NuvioColors.TextSecondary,
                                uncheckedTrackColor = NuvioColors.BackgroundCard
                            )
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.collections_editor_catalogs), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                    Text(
                        "${folder.catalogSources.size} ${stringResource(R.string.collections_editor_catalogs).lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextTertiary
                    )
                }
            }

            itemsIndexed(
                items = folder.catalogSources,
                key = { _, source -> "${source.addonId}_${source.type}_${source.catalogId}" }
            ) { index, source ->
                val catalog = uiState.availableCatalogs.find {
                    it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId
                }
                val isMissing = catalog == null
                val sourceKey = "${source.addonId}_${source.type}_${source.catalogId}"
                val removeFocusRequester = catalogFocusRequesters.getOrPut(sourceKey) { FocusRequester() }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    colors = SurfaceDefaults.colors(containerColor = NuvioColors.BackgroundCard),
                    border = if (isMissing) Border(
                        border = BorderStroke(1.dp, NuvioColors.Error.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) else Border.None,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = catalog?.catalogName?.replaceFirstChar { it.uppercase() } ?: source.catalogId,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isMissing) NuvioColors.Error else NuvioColors.TextPrimary
                            )
                            Text(
                                text = if (isMissing) stringResource(R.string.collections_editor_addon_missing, source.addonId) else "${source.type} - ${catalog.addonName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMissing) NuvioColors.Error.copy(alpha = 0.7f) else NuvioColors.TextTertiary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Button(
                                onClick = { viewModel.moveCatalogSourceUp(index) },
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioColors.BackgroundCard,
                                    contentColor = NuvioColors.TextSecondary,
                                    focusedContainerColor = NuvioColors.FocusBackground,
                                    focusedContentColor = NuvioColors.TextPrimary
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.cd_move_up), tint = if (index > 0) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
                            }
                            Button(
                                onClick = { viewModel.moveCatalogSourceDown(index) },
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioColors.BackgroundCard,
                                    contentColor = NuvioColors.TextSecondary,
                                    focusedContainerColor = NuvioColors.FocusBackground,
                                    focusedContentColor = NuvioColors.TextPrimary
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.cd_move_down), tint = if (index < folder.catalogSources.size - 1) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
                            }
                            Button(
                                onClick = {
                                    pendingFocusIndex = index
                                    viewModel.removeCatalogSource(index)
                                },
                                modifier = Modifier.focusRequester(removeFocusRequester),
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
                                Icon(Icons.Default.Close, stringResource(R.string.cd_remove))
                            }
                        }
                    }
                }
            }

            item {
                NuvioButton(onClick = { viewModel.showCatalogPicker() }) {
                    Icon(Icons.Default.Add, stringResource(R.string.cd_add))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.collections_editor_add_catalog))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatalogPickerContent(
    catalogs: List<AvailableCatalog>,
    alreadyAdded: List<com.nuvio.tv.domain.model.CollectionCatalogSource>,
    onToggle: (AvailableCatalog) -> Unit,
    onBack: () -> Unit
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
                text = stringResource(R.string.collections_editor_select_catalogs),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_done)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = catalogs,
                key = { _, c -> "${c.addonId}_${c.type}_${c.catalogId}" }
            ) { _, catalog ->
                val isAdded = alreadyAdded.any {
                    it.addonId == catalog.addonId && it.type == catalog.type && it.catalogId == catalog.catalogId
                }
                Card(
                    onClick = { onToggle(catalog) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = if (isAdded) NuvioColors.Secondary.copy(alpha = 0.15f) else NuvioColors.BackgroundCard,
                        focusedContainerColor = NuvioColors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        border = if (isAdded) Border(
                            border = BorderStroke(1.dp, NuvioColors.Secondary.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) else Border.None,
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.01f)
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
                                text = catalog.catalogName.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioColors.TextPrimary
                            )
                            Text(
                                text = "${catalog.type} - ${catalog.addonName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.TextTertiary
                            )
                        }
                        if (isAdded) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = NuvioColors.TextSecondary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.cd_add),
                                tint = NuvioColors.TextTertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

private val emojiCategories = linkedMapOf(
    "Streaming" to listOf("🎬", "🎭", "🎥", "📺", "🍿", "🎞️", "📽️", "🎦", "📡", "📻"),
    "Genres" to listOf("💀", "👻", "🔪", "💣", "🚀", "🛸", "🧙", "🦸", "🧟", "🤖", "💘", "😂", "😱", "🤯", "🥺", "😈"),
    "Sports" to listOf("⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏒", "🥊", "🏎️", "🏆", "🎯", "🏋️"),
    "Music" to listOf("🎵", "🎶", "🎤", "🎸", "🥁", "🎹", "🎷", "🎺", "🎻", "🪗"),
    "Nature" to listOf("🌍", "🌊", "🏔️", "🌋", "🌅", "🌙", "⭐", "🔥", "❄️", "🌈", "🌸", "🍀"),
    "Animals" to listOf("🐕", "🐈", "🦁", "🐻", "🦊", "🐺", "🦅", "🐉", "🦋", "🐬", "🦈", "🐙"),
    "Food" to listOf("🍕", "🍔", "🍣", "🍜", "🍩", "🍰", "🍷", "🍺", "☕", "🧁", "🌮", "🥗"),
    "Travel" to listOf("✈️", "🚂", "🚗", "⛵", "🏖️", "🗼", "🏰", "🗽", "🎡", "🏕️", "🌆", "🛣️"),
    "People" to listOf("👨‍👩‍👧‍👦", "👫", "👶", "🧒", "👩", "👨", "🧓", "💃", "🕺", "🥷", "🧑‍🚀", "🧑‍🎨"),
    "Objects" to listOf("📱", "💻", "🎮", "🕹️", "📷", "🔮", "💡", "🔑", "💎", "🎁", "📚", "✏️"),
    "Flags" to listOf(
        "🏳️‍🌈", "🏴‍☠️",
        "🇦🇫", "🇦🇱", "🇩🇿", "🇦🇸", "🇦🇩", "🇦🇴", "🇦🇮", "🇦🇬", "🇦🇷", "🇦🇲", "🇦🇼", "🇦🇺",
        "🇦🇹", "🇦🇿", "🇧🇸", "🇧🇭", "🇧🇩", "🇧🇧", "🇧🇾", "🇧🇪", "🇧🇿", "🇧🇯", "🇧🇲", "🇧🇹",
        "🇧🇴", "🇧🇦", "🇧🇼", "🇧🇷", "🇧🇳", "🇧🇬", "🇧🇫", "🇧🇮", "🇰🇭", "🇨🇲", "🇨🇦", "🇨🇻",
        "🇨🇫", "🇹🇩", "🇨🇱", "🇨🇳", "🇨🇴", "🇰🇲", "🇨🇬", "🇨🇩", "🇨🇷", "🇨🇮", "🇭🇷", "🇨🇺",
        "🇨🇼", "🇨🇾", "🇨🇿", "🇩🇰", "🇩🇯", "🇩🇲", "🇩🇴", "🇪🇨", "🇪🇬", "🇸🇻", "🇬🇶", "🇪🇷",
        "🇪🇪", "🇸🇿", "🇪🇹", "🇫🇯", "🇫🇮", "🇫🇷", "🇬🇦", "🇬🇲", "🇬🇪", "🇩🇪", "🇬🇭", "🇬🇷",
        "🇬🇩", "🇬🇹", "🇬🇳", "🇬🇼", "🇬🇾", "🇭🇹", "🇭🇳", "🇭🇰", "🇭🇺", "🇮🇸", "🇮🇳", "🇮🇩",
        "🇮🇷", "🇮🇶", "🇮🇪", "🇮🇱", "🇮🇹", "🇯🇲", "🇯🇵", "🇯🇴", "🇰🇿", "🇰🇪", "🇰🇮", "🇰🇼",
        "🇰🇬", "🇱🇦", "🇱🇻", "🇱🇧", "🇱🇸", "🇱🇷", "🇱🇾", "🇱🇮", "🇱🇹", "🇱🇺", "🇲🇴", "🇲🇬",
        "🇲🇼", "🇲🇾", "🇲🇻", "🇲🇱", "🇲🇹", "🇲🇷", "🇲🇺", "🇲🇽", "🇫🇲", "🇲🇩", "🇲🇨", "🇲🇳",
        "🇲🇪", "🇲🇦", "🇲🇿", "🇲🇲", "🇳🇦", "🇳🇷", "🇳🇵", "🇳🇱", "🇳🇿", "🇳🇮", "🇳🇪", "🇳🇬",
        "🇰🇵", "🇲🇰", "🇳🇴", "🇴🇲", "🇵🇰", "🇵🇼", "🇵🇸", "🇵🇦", "🇵🇬", "🇵🇾", "🇵🇪", "🇵🇭",
        "🇵🇱", "🇵🇹", "🇵🇷", "🇶🇦", "🇷🇴", "🇷🇺", "🇷🇼", "🇰🇳", "🇱🇨", "🇻🇨", "🇼🇸", "🇸🇲",
        "🇸🇹", "🇸🇦", "🇸🇳", "🇷🇸", "🇸🇨", "🇸🇱", "🇸🇬", "🇸🇰", "🇸🇮", "🇸🇧", "🇸🇴", "🇿🇦",
        "🇰🇷", "🇸🇸", "🇪🇸", "🇱🇰", "🇸🇩", "🇸🇷", "🇸🇪", "🇨🇭", "🇸🇾", "🇹🇼", "🇹🇯", "🇹🇿",
        "🇹🇭", "🇹🇱", "🇹🇬", "🇹🇴", "🇹🇹", "🇹🇳", "🇹🇷", "🇹🇲", "🇹🇻", "🇺🇬", "🇺🇦", "🇦🇪",
        "🇬🇧", "🇺🇸", "🇺🇾", "🇺🇿", "🇻🇺", "🇻🇪", "🇻🇳", "🇾🇪", "🇿🇲", "🇿🇼"
    ),
    "Symbols" to listOf("❤️", "💜", "💙", "💚", "💛", "🧡", "🖤", "🤍", "✅", "❌", "⚡", "💯")
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmojiPickerContent(
    selectedEmoji: String?,
    onSelect: (String) -> Unit,
    onBack: () -> Unit
) {
    val firstEmojiFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(5) { androidx.compose.runtime.withFrameNanos { } }
        try { firstEmojiFocusRequester.requestFocus() } catch (_: Exception) {}
    }

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
                text = stringResource(R.string.collections_editor_choose_emoji),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
            NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_back)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val firstCategory = emojiCategories.keys.first()
            emojiCategories.forEach { (category, emojis) ->
                item(key = "category_$category") {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        items(
                            count = emojis.size,
                            key = { "${category}_${emojis[it]}" }
                        ) { index ->
                            val emoji = emojis[index]
                            val isSelected = emoji == selectedEmoji
                            val isFirstEmoji = category == firstCategory && index == 0
                            Card(
                                onClick = { onSelect(emoji) },
                                modifier = (if (isFirstEmoji) Modifier.focusRequester(firstEmojiFocusRequester) else Modifier)
                                    .width(56.dp)
                                    .height(56.dp),
                                colors = CardDefaults.colors(
                                    containerColor = if (isSelected) NuvioColors.Secondary.copy(alpha = 0.3f) else NuvioColors.BackgroundCard,
                                    focusedContainerColor = NuvioColors.FocusBackground
                                ),
                                border = CardDefaults.border(
                                    border = if (isSelected) Border(
                                        border = BorderStroke(2.dp, NuvioColors.Secondary),
                                        shape = RoundedCornerShape(12.dp)
                                    ) else Border.None,
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                ),
                                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                                scale = CardDefaults.scale(focusedScale = 1.1f)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = emoji,
                                        fontSize = 28.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
