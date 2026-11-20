package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Reorder
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.TvReorderList
import com.nuvio.tv.ui.components.tvReorderDisabledFocusBorder
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionEditorScreen(
    viewModel: CollectionEditorViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val foldersScrollState = rememberScrollState()
    val folderRowHeightCache = remember { mutableMapOf<String, Float>() }
    var lastFocusedFolderTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFocusTarget by remember { mutableStateOf<String?>(null) }
    var hasAppliedInitialFocus by remember { mutableStateOf(false) }

    val titleFocusRequester = remember { FocusRequester() }
    val addFolderFocusRequester = remember { FocusRequester() }
    val folderFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val folderDeleteFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    var folderToDelete by remember { mutableStateOf<CollectionFolder?>(null) }
    val deleteDialogFocusRequester = remember { FocusRequester() }
    var showUnsavedCollectionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.folders) {
        val validIds = uiState.folders.mapTo(mutableSetOf()) { it.id }
        folderFocusRequesters.keys.retainAll(validIds)
        folderDeleteFocusRequesters.keys.retainAll(validIds)
    }

    LaunchedEffect(folderToDelete) {
        if (folderToDelete != null) {
            requestFocusWhenReady(deleteDialogFocusRequester)
        }
    }

    val activeFocusTarget = pendingFocusTarget ?: if (!hasAppliedInitialFocus) {
        lastFocusedFolderTarget ?: "title"
    } else {
        null
    }
    val ensureComposedFolderId = when {
        activeFocusTarget == null -> null
        activeFocusTarget.startsWith("folder_edit:") -> activeFocusTarget.removePrefix("folder_edit:")
        activeFocusTarget.startsWith("folder_delete:") -> activeFocusTarget.removePrefix("folder_delete:")
        else -> null
    }

    LaunchedEffect(uiState.isLoading, uiState.showFolderEditor, folderToDelete, showUnsavedCollectionDialog, pendingFocusTarget, uiState.folders.size) {
        if (uiState.isLoading || uiState.showFolderEditor || folderToDelete != null || showUnsavedCollectionDialog) {
            return@LaunchedEffect
        }
        val target = pendingFocusTarget ?: if (!hasAppliedInitialFocus) {
            lastFocusedFolderTarget ?: "title"
        } else {
            return@LaunchedEffect
        }
        val requester = when {
            target == "title" -> titleFocusRequester
            target == "add_folder" -> addFolderFocusRequester
            target.startsWith("folder_edit:") -> {
                val id = target.removePrefix("folder_edit:")
                if (uiState.folders.any { it.id == id }) {
                    folderFocusRequesters.getOrPut(id) { FocusRequester() }
                } else {
                    addFolderFocusRequester
                }
            }
            target.startsWith("folder_delete:") -> {
                val id = target.removePrefix("folder_delete:")
                if (uiState.folders.any { it.id == id }) {
                    folderDeleteFocusRequesters.getOrPut(id) { FocusRequester() }
                } else {
                    addFolderFocusRequester
                }
            }
            else -> titleFocusRequester
        }
        val focused = requestFocusWhenReady(requester)
        if (!focused && requester !== titleFocusRequester) {
            requestFocusWhenReady(addFolderFocusRequester) || requestFocusWhenReady(titleFocusRequester)
        }
        hasAppliedInitialFocus = true
        if (pendingFocusTarget == target) {
            pendingFocusTarget = null
        }
    }

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

    BackHandler(
        enabled = (uiState.hasUnsavedCollectionChanges || uiState.isSaving) &&
            uiState.movingFolderKey == null &&
            folderToDelete == null &&
            !showUnsavedCollectionDialog
    ) {
        if (!uiState.isSaving) {
            showUnsavedCollectionDialog = true
        }
    }

    val foldersMovingIndex = uiState.folders.indexOfFirst { it.id == uiState.movingFolderKey }
    Box(modifier = Modifier.fillMaxSize()) {
    TvReorderList(
        items = uiState.folders,
        key = { it.id },
        canMove = { uiState.folders.size > 1 && uiState.movingFolderKey == null },
        movingKey = uiState.movingFolderKey,
        onStartMove = viewModel::enterFolderMoveMode,
        onMoveBy = viewModel::moveFolderDuringDrag,
        onConfirmMove = { viewModel.confirmFolderMove() },
        onCancelMove = viewModel::cancelFolderMove,
        modifier = Modifier.fillMaxSize(),
        scrollState = foldersScrollState,
        ensureComposedKey = ensureComposedFolderId,
        rowHeightCache = folderRowHeightCache,
        contentPadding = PaddingValues(
            top = NuvioTheme.spacing.xxxl,
            start = NuvioTheme.spacing.xxxl,
            end = NuvioTheme.spacing.xxxl,
            bottom = NuvioTheme.spacing.xxxl
        ),
        header = {
            Column {
                Text(
                    text = if (uiState.isNew) stringResource(R.string.collections_new) else stringResource(R.string.collections_editor_edit_collection),
                    style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content),
                    color = NuvioTheme.colors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

                Text(
                    text = stringResource(R.string.collections_editor_row_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusEntryTarget(titleFocusRequester),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NuvioTextField(
                        value = uiState.title,
                        onValueChange = { viewModel.setTitle(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.collections_editor_placeholder_name),
                        focusRequester = titleFocusRequester
                    )
                    NuvioButton(onClick = { viewModel.save { onBack() } }, enabled = uiState.canSaveCollection) {
                        Text(stringResource(R.string.collections_editor_save))
                    }
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                Text(
                    text = stringResource(R.string.collections_editor_backdrop),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                NuvioTextField(
                    value = uiState.backdropImageUrl,
                    onValueChange = { viewModel.setBackdropImageUrl(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .blockHorizontalFocus(),
                    placeholder = stringResource(R.string.collections_editor_placeholder_backdrop)
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                Card(
                    onClick = { viewModel.setPinToTop(!uiState.pinToTop) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .blockHorizontalFocus()
                        .semantics {
                            role = Role.Switch
                            toggleableState = if (uiState.pinToTop) ToggleableState.On else ToggleableState.Off
                        },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
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
                                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                                color = NuvioTheme.colors.TextPrimary,
                            )
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                            Text(
                                text = stringResource(R.string.collections_editor_pin_above_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                        Switch(
                            checked = uiState.pinToTop,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NuvioTheme.colors.Secondary,
                                checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f),
                                uncheckedThumbColor = NuvioTheme.colors.TextSecondary,
                                uncheckedTrackColor = NuvioTheme.colors.BackgroundCard
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                Card(
                    onClick = { viewModel.setFocusGlowEnabled(!uiState.focusGlowEnabled) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .blockHorizontalFocus()
                        .semantics {
                            role = Role.Switch
                            toggleableState = if (uiState.focusGlowEnabled) ToggleableState.On else ToggleableState.Off
                        },
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
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
                                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                                color = NuvioTheme.colors.TextPrimary,
                            )
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                            Text(
                                text = stringResource(R.string.collections_editor_focus_glow_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                        Switch(
                            checked = uiState.focusGlowEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NuvioTheme.colors.Secondary,
                                checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f),
                                uncheckedThumbColor = NuvioTheme.colors.TextSecondary,
                                uncheckedTrackColor = NuvioTheme.colors.BackgroundCard
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                Text(
                    text = stringResource(R.string.collections_editor_view_mode),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                val viewModeFocusRequesters = remember {
                    FolderViewMode.entries.associateWith { FocusRequester() }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusEntryTarget(viewModeFocusRequesters[uiState.viewMode] ?: FocusRequester.Default),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
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
                            modifier = Modifier
                                .focusRequester(viewModeFocusRequesters.getValue(mode))
                                .semantics {
                                    role = Role.RadioButton
                                    selected = isSelected
                                },
                            colors = ButtonDefaults.colors(
                                containerColor = if (isSelected) NuvioTheme.colors.Secondary.copy(alpha = 0.3f) else NuvioTheme.colors.BackgroundCard,
                                contentColor = if (isSelected) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                focusedContentColor = NuvioTheme.colors.Primary
                            ),
                            border = ButtonDefaults.border(
                                border = if (isSelected) Border(
                                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.Secondary),
                                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                                ) else Border.None,
                                focusedBorder = Border(
                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                                )
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                        ) {
                            Text(label)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

            if (uiState.viewMode == FolderViewMode.TABBED_GRID) {
                    Card(
                        onClick = { viewModel.setShowAllTab(!uiState.showAllTab) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .blockHorizontalFocus()
                            .semantics {
                                role = Role.Switch
                                toggleableState = if (uiState.showAllTab) ToggleableState.On else ToggleableState.Off
                            },
                        colors = CardDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
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
                                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                                    color = NuvioTheme.colors.TextPrimary,
                                )
                                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                                Text(
                                    text = stringResource(R.string.collections_editor_show_all_tab_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                            Switch(
                                checked = uiState.showAllTab,
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NuvioTheme.colors.Secondary,
                                    checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f),
                                    uncheckedThumbColor = NuvioTheme.colors.TextSecondary,
                                    uncheckedTrackColor = NuvioTheme.colors.BackgroundCard
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.collections_editor_folders),
                        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                        color = NuvioTheme.colors.TextPrimary,
                    )
                    Text(
                        text = stringResource(
                            if (uiState.folders.size == 1) R.string.collection_editor_folder_count_one
                            else R.string.collection_editor_folder_count_other,
                            uiState.folders.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            }
        },
        footer = {
            Box(
                modifier = Modifier.padding(
                    start = NuvioTheme.spacing.sm,
                    end = NuvioTheme.spacing.sm,
                    top = if (uiState.folders.isEmpty()) NuvioTheme.spacing.xs else NuvioTheme.spacing.lg
                )
            ) {
                NuvioButton(
                    onClick = {
                        lastFocusedFolderTarget = "add_folder"
                        pendingFocusTarget = "add_folder"
                        viewModel.addFolder()
                    },
                    enabled = uiState.movingFolderKey == null,
                    modifier = Modifier
                        .focusRequester(addFolderFocusRequester)
                        .blockHorizontalFocus()
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                    Text(stringResource(R.string.collections_editor_add_folder))
                }
            }
        },
    ) { folder, isMoving, onStartMove, onConfirmMove ->
        val editFocusRequester = folderFocusRequesters.getOrPut(folder.id) { FocusRequester() }
        val deleteFocusRequester = folderDeleteFocusRequesters.getOrPut(folder.id) { FocusRequester() }
        Box(modifier = Modifier.padding(start = NuvioTheme.spacing.sm, end = NuvioTheme.spacing.sm)) {
            FolderListItem(
                folder = folder,
                isMoving = isMoving,
                canMove = uiState.folders.size > 1,
                actionsEnabled = uiState.movingFolderKey == null || isMoving,
                editFocusRequester = editFocusRequester,
                deleteFocusRequester = deleteFocusRequester,
                onEdit = {
                    lastFocusedFolderTarget = "folder_edit:${folder.id}"
                    pendingFocusTarget = "folder_edit:${folder.id}"
                    viewModel.editFolder(folder.id)
                },
                onDelete = { folderToDelete = folder },
                onStartMove = onStartMove,
                onConfirmMove = onConfirmMove
            )
        }
    }
    SettingsVerticalScrollIndicators(
        state = foldersScrollState,
        suppressBackward = foldersMovingIndex == 0,
        suppressForward = foldersMovingIndex >= 0 &&
            foldersMovingIndex == uiState.folders.lastIndex,
        forceBackward = foldersMovingIndex > 0,
        forceForward = foldersMovingIndex in 0 until uiState.folders.lastIndex
    )
    }

    folderToDelete?.let { folder ->
        NuvioDialog(
            onDismiss = {
                folderToDelete = null
                pendingFocusTarget = "folder_delete:${folder.id}"
            },
            title = stringResource(R.string.collections_editor_delete_folder_title),
            subtitle = stringResource(R.string.collections_editor_delete_folder_subtitle, folder.title),
            suppressFirstKeyUp = false
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md, Alignment.End)
            ) {
                Button(
                    onClick = {
                        folderToDelete = null
                        pendingFocusTarget = "folder_delete:${folder.id}"
                    },
                    modifier = Modifier
                        .focusRequester(deleteDialogFocusRequester)
                        .semantics { role = Role.Button }
                ) {
                    Text(stringResource(R.string.collections_cancel))
                }
                Button(
                    onClick = {
                        val currentIndex = uiState.folders.indexOfFirst { it.id == folder.id }
                        val nextTargetId = uiState.folders
                            .filterNot { it.id == folder.id }
                            .let { remaining ->
                                if (remaining.isEmpty()) null
                                else remaining[currentIndex.coerceIn(0, remaining.lastIndex)].id
                            }
                        viewModel.removeFolder(folder.id)
                        folderToDelete = null
                        val nextTarget = nextTargetId?.let { "folder_edit:$it" } ?: "add_folder"
                        lastFocusedFolderTarget = nextTarget
                        pendingFocusTarget = nextTarget
                    },
                    modifier = Modifier.semantics { role = Role.Button }
                ) {
                    Text(stringResource(R.string.cd_delete))
                }
            }
        }
    }

    if (showUnsavedCollectionDialog) {
        val canSave = uiState.canSaveCollection
        val dialogTitle = stringResource(
            if (canSave) R.string.collections_editor_unsaved_title
            else R.string.collections_editor_unsaved_incomplete_title
        )
        val dialogSubtitle = stringResource(
            when {
                canSave -> R.string.collections_editor_unsaved_subtitle
                uiState.title.isBlank() && uiState.folders.isEmpty() ->
                    R.string.collections_editor_unsaved_missing_title_and_folder
                uiState.title.isBlank() -> R.string.collections_editor_unsaved_missing_title
                else -> R.string.collections_editor_unsaved_missing_folder
            }
        )
        UnsavedChangesDialog(
            title = dialogTitle,
            subtitle = dialogSubtitle,
            canSave = canSave,
            isSaving = uiState.isSaving,
            onSave = {
                showUnsavedCollectionDialog = false
                viewModel.save { onBack() }
            },
            onDiscard = {
                showUnsavedCollectionDialog = false
                onBack()
            },
            onDismiss = { showUnsavedCollectionDialog = false }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FolderListItem(
    folder: CollectionFolder,
    isMoving: Boolean,
    canMove: Boolean,
    actionsEnabled: Boolean,
    editFocusRequester: FocusRequester? = null,
    deleteFocusRequester: FocusRequester? = null,
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
        label = "folderCardColor",
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
                .padding(NuvioTheme.spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        textDirection = folder.title.contentTextDirection()
                    ),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val shapeLabel = stringResource(
                    when (folder.tileShape) {
                        com.nuvio.tv.domain.model.PosterShape.POSTER -> R.string.collections_editor_shape_poster
                        com.nuvio.tv.domain.model.PosterShape.LANDSCAPE -> R.string.collections_editor_shape_wide
                        com.nuvio.tv.domain.model.PosterShape.SQUARE -> R.string.collections_editor_shape_square
                    }
                )
                Text(
                    text = "$shapeLabel - ${stringResource(R.string.collections_editor_source_count, folder.sources.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                modifier = Modifier.trapHorizontalFocus()
            ) {
                Button(
                    onClick = if (isMoving) onConfirmMove else onStartMove,
                    enabled = isMoving || (canMove && actionsEnabled),
                    modifier = Modifier.semantics {
                        role = Role.Button
                    },
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
                        ),
                        focusedDisabledBorder = tvReorderDisabledFocusBorder(RoundedCornerShape(NuvioTheme.radii.md))
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Icon(
                        imageVector = Icons.Default.Reorder,
                        contentDescription = stringResource(R.string.cd_reorder)
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
                        ),
                        focusedDisabledBorder = tvReorderDisabledFocusBorder(RoundedCornerShape(NuvioTheme.radii.md))
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Icon(Icons.Default.Edit, stringResource(R.string.cd_edit))
                }
                Button(
                    onClick = onDelete,
                    enabled = actionsEnabled && !isMoving,
                    modifier = if (deleteFocusRequester != null) Modifier.focusRequester(deleteFocusRequester) else Modifier,
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
                        ),
                        focusedDisabledBorder = tvReorderDisabledFocusBorder(RoundedCornerShape(NuvioTheme.radii.md))
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Icon(Icons.Default.Delete, stringResource(R.string.cd_delete))
                }
            }
        }
    }
}
