package com.nuvio.tv.ui.screens.collection

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
import androidx.compose.foundation.layout.width
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

    var lastFocusedFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    val folderFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    var folderToDelete by remember { mutableStateOf<CollectionFolder?>(null) }
    val deleteDialogFocusRequester = remember { FocusRequester() }

    LaunchedEffect(folderToDelete) {
        if (folderToDelete != null) {
            repeat(3) { withFrameNanos { } }
            try {
                deleteDialogFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(uiState.showFolderEditor) {
        if (!uiState.showFolderEditor && lastFocusedFolderId != null) {
            val targetId = lastFocusedFolderId!!
            repeat(3) { withFrameNanos { } }

            try {
                folderFocusRequesters[targetId]?.requestFocus()
            } catch (_: Exception) {}
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NuvioTextField(
                        value = uiState.title,
                        onValueChange = { viewModel.setTitle(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.collections_editor_placeholder_name)
                    )
                    val canSaveCollection = uiState.title.isNotBlank() && uiState.folders.isNotEmpty() && uiState.movingFolderKey == null
                    NuvioButton(onClick = { viewModel.save { onBack() } }, enabled = canSaveCollection) {
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
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.collections_editor_placeholder_backdrop)
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                Card(
                    onClick = { viewModel.setPinToTop(!uiState.pinToTop) },
                    modifier = Modifier
                        .fillMaxWidth()
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            modifier = Modifier.semantics {
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
            val addFolderFocusRequester = folderFocusRequesters.getOrPut("add_folder") { FocusRequester() }
            Box(
                modifier = Modifier.padding(
                    start = NuvioTheme.spacing.sm,
                    end = NuvioTheme.spacing.sm,
                    top = if (uiState.folders.isEmpty()) NuvioTheme.spacing.xs else NuvioTheme.spacing.lg
                )
            ) {
                NuvioButton(
                    onClick = {
                        lastFocusedFolderId = "add_folder"
                        viewModel.addFolder()
                    },
                    enabled = uiState.movingFolderKey == null,
                    modifier = Modifier.focusRequester(addFolderFocusRequester)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                    Text(stringResource(R.string.collections_editor_add_folder))
                }
            }
        },
    ) { folder, isMoving, onStartMove, onConfirmMove ->
        val editFocusRequester = folderFocusRequesters.getOrPut(folder.id) { FocusRequester() }
        Box(modifier = Modifier.padding(start = NuvioTheme.spacing.sm, end = NuvioTheme.spacing.sm)) {
            FolderListItem(
                folder = folder,
                isMoving = isMoving,
                canMove = uiState.folders.size > 1,
                actionsEnabled = uiState.movingFolderKey == null || isMoving,
                editFocusRequester = editFocusRequester,
                onEdit = {
                    lastFocusedFolderId = folder.id
                    viewModel.editFolder(folder.id)
                },
                onDelete = { folderToDelete = folder },
                onStartMove = onStartMove,
                onConfirmMove = onConfirmMove
            )
        }
    }

    folderToDelete?.let { folder ->
        NuvioDialog(
            onDismiss = { folderToDelete = null },
            title = stringResource(R.string.collections_editor_delete_folder_title),
            subtitle = stringResource(R.string.collections_editor_delete_folder_subtitle, folder.title),
            suppressFirstKeyUp = false
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md, Alignment.End)
            ) {
                NuvioButton(
                    onClick = { folderToDelete = null },
                    modifier = Modifier.focusRequester(deleteDialogFocusRequester)
                ) {
                    Text(stringResource(R.string.collections_cancel))
                }
                NuvioButton(
                    onClick = {
                        viewModel.removeFolder(folder.id)
                        folderToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.cd_delete))
                }
            }
        }
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

            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
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
                NuvioButton(
                    onClick = onEdit,
                    enabled = actionsEnabled && !isMoving,
                    modifier = if (editFocusRequester != null) Modifier.focusRequester(editFocusRequester) else Modifier
                ) {
                    Icon(Icons.Default.Edit, stringResource(R.string.cd_edit), tint = NuvioTheme.colors.TextSecondary)
                }
                NuvioButton(onClick = onDelete, enabled = actionsEnabled && !isMoving) {
                    Icon(Icons.Default.Delete, stringResource(R.string.cd_delete), tint = NuvioTheme.colors.TextSecondary)
                }
            }
        }
    }
}
