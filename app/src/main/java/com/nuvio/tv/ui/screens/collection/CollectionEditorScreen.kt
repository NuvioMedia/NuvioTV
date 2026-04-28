package com.nuvio.tv.ui.screens.collection

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.R
import androidx.compose.ui.res.stringResource

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
                    text = "${folder.tileShape.name.lowercase().replaceFirstChar { it.uppercase() }} - ${stringResource(R.string.collections_editor_source_count, folder.sources.size)}",
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
