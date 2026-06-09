package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
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
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FolderEditorContent(
    viewModel: CollectionEditorViewModel,
    uiState: CollectionEditorUiState
) {
    val folder = uiState.editingFolder ?: return

    if (uiState.showCatalogPicker) {
        BackHandler { viewModel.hideCatalogPicker() }
        CatalogPickerContent(
            catalogs = uiState.availableCatalogs,
            alreadyAdded = folder.sources,
            onToggle = { viewModel.toggleCatalogSource(it) },
            onBack = { viewModel.hideCatalogPicker() }
        )
        return
    }

    if (uiState.showTmdbSourcePicker) {
        BackHandler { viewModel.hideTmdbSourcePicker() }
        TmdbSourcePickerContent(
            uiState = uiState,
            presets = viewModel.tmdbPresets(),
            isEditing = uiState.editingTmdbSourceIndex != null,
            onModeChange = { viewModel.setTmdbBuilderMode(it) },
            onInputChange = { viewModel.setTmdbInput(it) },
            onTitleChange = { viewModel.setTmdbTitleInput(it) },
            onMediaTypeChange = { viewModel.setTmdbMediaType(it) },
            onMediaBothChange = { viewModel.setTmdbMediaBoth(it) },
            onSortChange = { viewModel.setTmdbSortBy(it) },
            onFiltersChange = { viewModel.setTmdbFilters(it) },
            onSearchCompanies = { viewModel.searchTmdbCompanies() },
            onSearchCollections = { viewModel.searchTmdbCollections() },
            onAddSource = { viewModel.addTmdbSource(it) },
            onAddSources = { viewModel.addTmdbSources(it) },
            onAddFromInput = { viewModel.addTmdbSourceFromInput() },
            onAddDiscover = { viewModel.addDiscoverSource() },
            onBack = { viewModel.hideTmdbSourcePicker() }
        )
        return
    }

    if (uiState.showTraktSourcePicker) {
        BackHandler { viewModel.hideTraktSourcePicker() }
        TraktSourcePickerContent(
            uiState = uiState,
            isEditing = uiState.editingTraktSourceIndex != null,
            onInputChange = { viewModel.setTraktInput(it) },
            onTitleChange = { viewModel.setTraktTitleInput(it) },
            onMediaTypeChange = { viewModel.setTraktMediaType(it) },
            onMediaBothChange = { viewModel.setTraktMediaBoth(it) },
            onSortChange = { viewModel.setTraktSortBy(it) },
            onSortHowChange = { viewModel.setTraktSortHow(it) },
            onSearch = { viewModel.searchTraktLists() },
            onAddFromInput = { viewModel.addTraktSourceFromInput() },
            onAddResult = { viewModel.addTraktSourceFromResult(it) },
            onBack = { viewModel.hideTraktSourcePicker() }
        )
        return
    }

    if (uiState.showEmojiPicker) {
        BackHandler { viewModel.hideEmojiPicker() }
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

    val genrePickerIndex = uiState.genrePickerSourceIndex
    val genrePickerSource = genrePickerIndex?.let { folder.sources.getOrNull(it) as? AddonCatalogCollectionSource }
    val genrePickerCatalog = genrePickerSource?.let { source ->
        uiState.availableCatalogs.find {
            it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId
        }
    }

    if (
        genrePickerIndex != null &&
        genrePickerSource != null &&
        genrePickerCatalog != null &&
        genrePickerCatalog.genreOptions.isNotEmpty()
    ) {
        BackHandler { viewModel.hideGenrePicker() }
        GenrePickerContent(
            title = genrePickerCatalog.catalogName,
            selectedGenre = genrePickerSource.genre,
            genreOptions = genrePickerCatalog.genreOptions,
            allowAll = !genrePickerCatalog.genreRequired,
            onSelect = { genre ->
                viewModel.updateCatalogSourceGenre(genrePickerIndex, genre)
                viewModel.hideGenrePicker()
            },
            onBack = { viewModel.hideGenrePicker() }
        )
        return
    }

    BackHandler { viewModel.cancelFolderEdit() }

    val titleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(5) { androidx.compose.runtime.withFrameNanos { } }
        try { titleFocusRequester.requestFocus() } catch (_: Exception) {}
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
                text = stringResource(R.string.collections_editor_edit_folder),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                NuvioButton(onClick = { viewModel.cancelFolderEdit() }) {
                    Text(stringResource(R.string.collections_cancel))
                }
                val canSaveFolder = folder.sources.isNotEmpty()
                NuvioButton(onClick = { viewModel.saveFolderEdit() }, enabled = canSaveFolder) {
                    Text(stringResource(R.string.collections_editor_save))
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

        val catalogFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
        var pendingFocusIndex by remember { mutableStateOf(-1) }

        LaunchedEffect(pendingFocusIndex, folder.sources.size) {
            if (pendingFocusIndex >= 0) {
                val targetIndex = pendingFocusIndex.coerceAtMost(folder.sources.lastIndex)
                if (targetIndex >= 0) {
                    val targetSource = folder.sources[targetIndex]
                    val targetKey = collectionSourceKey(targetSource)
                    repeat(3) { androidx.compose.runtime.withFrameNanos { } }
                    try { catalogFocusRequesters[targetKey]?.requestFocus() } catch (_: Exception) {}
                }
                pendingFocusIndex = -1
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = NuvioTheme.spacing.xs, end = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            item {
                Text(stringResource(R.string.collections_editor_folder_title), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
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

                Text(stringResource(R.string.collections_editor_cover), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                    Button(
                        onClick = { viewModel.clearFolderCover() },
                        colors = ButtonDefaults.colors(
                            containerColor = if (coverMode == "none") NuvioTheme.colors.Secondary.copy(alpha = 0.3f) else NuvioTheme.colors.BackgroundCard,
                            contentColor = if (coverMode == "none") NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (coverMode == "none") Border(
                                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.Secondary),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) { Text(stringResource(R.string.collections_editor_cover_none)) }

                    Button(
                        onClick = { viewModel.showEmojiPicker() },
                        colors = ButtonDefaults.colors(
                            containerColor = if (coverMode == "emoji") NuvioTheme.colors.Secondary.copy(alpha = 0.3f) else NuvioTheme.colors.BackgroundCard,
                            contentColor = if (coverMode == "emoji") NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (coverMode == "emoji") Border(
                                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.Secondary),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
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
                            containerColor = if (coverMode == "image") NuvioTheme.colors.Secondary.copy(alpha = 0.3f) else NuvioTheme.colors.BackgroundCard,
                            contentColor = if (coverMode == "image") NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        border = ButtonDefaults.border(
                            border = if (coverMode == "image") Border(
                                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.Secondary),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            ) else Border.None,
                            focusedBorder = Border(
                                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) { Text(stringResource(R.string.collections_editor_cover_image_url)) }
                }

                if (coverMode == "image") {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                    ) {
                        NuvioTextField(
                            value = folder.coverImageUrl ?: "",
                            onValueChange = { viewModel.updateFolderCoverImage(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = stringResource(R.string.collections_editor_url_short_placeholder)
                        )
                        if (!folder.coverImageUrl.isNullOrBlank()) {
                            Card(
                                onClick = {},
                                modifier = Modifier
                                    .width(NuvioTheme.spacing.huge)
                                    .height(NuvioTheme.spacing.huge),
                                shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                                colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
                                scale = CardDefaults.scale(focusedScale = 1f)
                            ) {
                                AsyncImage(
                                    model = folder.coverImageUrl,
                                    contentDescription = stringResource(R.string.cd_preview),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(NuvioTheme.radii.md)),
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                Text(stringResource(R.string.collections_editor_focus_gif), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                NuvioTextField(
                    value = folder.focusGifUrl.orEmpty(),
                    onValueChange = { viewModel.updateFolderFocusGifUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.collections_editor_placeholder_gif)
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                Card(
                    onClick = { viewModel.updateFolderFocusGifEnabled(!folder.focusGifEnabled) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    scale = CardDefaults.scale(focusedScale = 1f),
                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.collections_editor_play_gif), style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextPrimary)
                        Switch(
                            checked = folder.focusGifEnabled,
                            onCheckedChange = { viewModel.updateFolderFocusGifEnabled(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                Text(stringResource(R.string.collections_editor_hero_backdrop), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    NuvioTextField(
                        value = folder.heroBackdropUrl.orEmpty(),
                        onValueChange = { viewModel.updateFolderHeroBackdropUrl(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.collections_editor_placeholder_hero_backdrop)
                    )
                    if (!folder.heroBackdropUrl.isNullOrBlank()) {
                        Card(
                            onClick = {},
                            modifier = Modifier
                                .width(NuvioTheme.spacing.huge)
                                .height(NuvioTheme.spacing.huge),
                            shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                            colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            AsyncImage(
                                model = folder.heroBackdropUrl,
                                contentDescription = stringResource(R.string.cd_preview),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(NuvioTheme.radii.md)),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                Text(stringResource(R.string.collections_editor_hero_video), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                NuvioTextField(
                    value = folder.heroVideoUrl.orEmpty(),
                    onValueChange = { viewModel.updateFolderHeroVideoUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.collections_editor_placeholder_hero_video)
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                Text(stringResource(R.string.collections_editor_title_logo), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    NuvioTextField(
                        value = folder.titleLogoUrl.orEmpty(),
                        onValueChange = { viewModel.updateFolderTitleLogoUrl(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.collections_editor_placeholder_title_logo)
                    )
                    if (!folder.titleLogoUrl.isNullOrBlank()) {
                        Card(
                            onClick = {},
                            modifier = Modifier
                                .width(100.dp)
                                .height(NuvioTheme.spacing.huge),
                            shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                            colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            AsyncImage(
                                model = folder.titleLogoUrl,
                                contentDescription = stringResource(R.string.cd_preview),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(NuvioTheme.radii.md)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }

            item {
                Text(stringResource(R.string.collections_editor_tile_shape), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                val shapeFocusRequesters = remember { PosterShape.entries.associateWith { FocusRequester() } }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
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
                                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                                )
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
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
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
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
                                text = stringResource(R.string.collections_editor_hide_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = NuvioTheme.colors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                            Text(
                                text = stringResource(R.string.collections_editor_hide_title_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                        Switch(
                            checked = folder.hideTitle,
                            onCheckedChange = { viewModel.updateFolderHideTitle(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NuvioTheme.colors.Secondary,
                                checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f),
                                uncheckedThumbColor = NuvioTheme.colors.TextSecondary,
                                uncheckedTrackColor = NuvioTheme.colors.BackgroundCard
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
                    Text(stringResource(R.string.collections_editor_catalogs), style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
                    Text(
                        "${folder.sources.size} ${stringResource(R.string.collections_editor_sources).lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
            }

            itemsIndexed(
                items = folder.sources,
                key = { _, source -> collectionSourceKey(source) }
            ) { index, source ->
                val addonSource = source as? AddonCatalogCollectionSource
                val tmdbSource = source as? TmdbCollectionSource
                val traktSource = source as? TraktCollectionSource
                val catalog = addonSource?.let { addon ->
                    uiState.availableCatalogs.find {
                        it.addonId == addon.addonId && it.type == addon.type && it.catalogId == addon.catalogId
                    }
                }
                val addonCatalogInfo = addonSource?.let { src ->
                    val exactKey = "${src.addonId}|${src.type}|${src.catalogId}"
                    uiState.addonCatalogInfoByKey[exactKey]
                        ?: uiState.addonCatalogInfoByKey["${src.addonId}|${src.type}|${src.catalogId.substringBefore(",")}"]
                }
                val isMissing = addonSource != null && catalog == null && addonCatalogInfo == null
                val sourceKey = collectionSourceKey(source)
                val removeFocusRequester = catalogFocusRequesters.getOrPut(sourceKey) { FocusRequester() }
                val genreLabel = addonSource?.genre ?: if (catalog?.genreRequired == true) {
                    stringResource(R.string.collections_editor_select_genre)
                } else {
                    stringResource(R.string.collections_editor_all_genres)
                }
                val addonTypeLabel = when (addonSource?.type?.lowercase()) {
                    "movie" -> stringResource(R.string.type_movie)
                    "series", "tv" -> stringResource(R.string.type_series)
                    else -> addonSource?.type.orEmpty()
                }
                val hasGenreOptions = addonSource != null && catalog?.genreOptions?.isNotEmpty() == true
                Surface(
                    shape = RoundedCornerShape(NuvioTheme.radii.md),
                    colors = SurfaceDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
                    border = if (isMissing) Border(
                        border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Error.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    ) else Border.None,
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
                                text = catalog?.catalogName?.replaceFirstChar { it.uppercase() }
                                    ?: tmdbSource?.title
                                    ?: traktSource?.title
                                    ?: addonCatalogInfo?.catalogName?.replaceFirstChar { it.uppercase() }
                                    ?: addonSource?.catalogId
                                    ?: stringResource(R.string.collections_editor_source),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isMissing) NuvioTheme.colors.Error else NuvioTheme.colors.TextPrimary
                            )
                            Text(
                                text = when {
                                    isMissing -> stringResource(R.string.collections_editor_addon_missing, addonSource.addonId)
                                    addonSource != null && catalog != null -> "$addonTypeLabel - ${catalog.addonName}"
                                    addonSource != null && addonCatalogInfo != null -> "$addonTypeLabel - ${addonCatalogInfo.addonName}"
                                    tmdbSource != null -> tmdbSourceSubtitle(tmdbSource)
                                    traktSource != null -> traktSourceSubtitle(traktSource)
                                    else -> stringResource(R.string.collections_editor_source)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMissing) NuvioTheme.colors.Error.copy(alpha = 0.7f) else NuvioTheme.colors.TextTertiary
                            )
                            if (hasGenreOptions) {
                                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(NuvioTheme.colors.BackgroundElevated)
                                            .padding(horizontal = 10.dp, vertical = NuvioTheme.spacing.xs)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.collections_editor_genre_filter),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NuvioTheme.colors.TextSecondary
                                        )
                                    }
                                    Text(
                                        text = genreLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NuvioTheme.colors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Button(
                                        onClick = { viewModel.showGenrePicker(index) },
                                        colors = ButtonDefaults.colors(
                                            containerColor = NuvioTheme.colors.BackgroundElevated,
                                            contentColor = NuvioTheme.colors.TextSecondary,
                                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                            focusedContentColor = NuvioTheme.colors.Primary
                                        ),
                                        border = ButtonDefaults.border(
                                            focusedBorder = Border(
                                                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                                            )
                                        ),
                                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                                    ) {
                                        Text(stringResource(R.string.collections_editor_choose_genre))
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                            if (tmdbSource != null) {
                                Button(
                                    onClick = { viewModel.editTmdbSource(index) },
                                    colors = ButtonDefaults.colors(
                                        containerColor = NuvioTheme.colors.BackgroundCard,
                                        contentColor = NuvioTheme.colors.TextSecondary,
                                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                        focusedContentColor = NuvioTheme.colors.TextPrimary
                                    ),
                                    border = ButtonDefaults.border(
                                        focusedBorder = Border(
                                            border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                                        )
                                    ),
                                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                                ) {
                                    Icon(Icons.Default.Edit, stringResource(R.string.cd_edit))
                                }
                            }
                            if (traktSource != null) {
                                Button(
                                    onClick = { viewModel.editTraktSource(index) },
                                    colors = ButtonDefaults.colors(
                                        containerColor = NuvioTheme.colors.BackgroundCard,
                                        contentColor = NuvioTheme.colors.TextSecondary,
                                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                        focusedContentColor = NuvioTheme.colors.TextPrimary
                                    ),
                                    border = ButtonDefaults.border(
                                        focusedBorder = Border(
                                            border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                                        )
                                    ),
                                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                                ) {
                                    Icon(Icons.Default.Edit, stringResource(R.string.cd_edit))
                                }
                            }
                            Button(
                                onClick = { viewModel.moveCatalogSourceUp(index) },
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioTheme.colors.BackgroundCard,
                                    contentColor = NuvioTheme.colors.TextSecondary,
                                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                    focusedContentColor = NuvioTheme.colors.TextPrimary
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.cd_move_up), tint = if (index > 0) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextTertiary)
                            }
                            Button(
                                onClick = { viewModel.moveCatalogSourceDown(index) },
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioTheme.colors.BackgroundCard,
                                    contentColor = NuvioTheme.colors.TextSecondary,
                                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                    focusedContentColor = NuvioTheme.colors.TextPrimary
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.cd_move_down), tint = if (index < folder.sources.size - 1) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextTertiary)
                            }
                            Button(
                                onClick = {
                                    pendingFocusIndex = index
                                    viewModel.removeCatalogSource(index)
                                },
                                modifier = Modifier.focusRequester(removeFocusRequester),
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioTheme.colors.BackgroundCard,
                                    contentColor = NuvioTheme.colors.TextSecondary,
                                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                    focusedContentColor = NuvioTheme.colors.Error
                                ),
                                border = ButtonDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                                    )
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                            ) {
                                Icon(Icons.Default.Close, stringResource(R.string.cd_remove))
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                    NuvioButton(onClick = { viewModel.showCatalogPicker() }) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add))
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                        Text(stringResource(R.string.collections_editor_add_catalog))
                    }
                    NuvioButton(onClick = { viewModel.showTmdbSourcePicker() }) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add))
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                        Text(stringResource(R.string.collections_editor_add_tmdb_source))
                    }
                    NuvioButton(onClick = { viewModel.showTraktSourcePicker() }) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add))
                        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                        Text(stringResource(R.string.collections_editor_add_trakt_source))
                    }
                }
            }
        }
    }
}
