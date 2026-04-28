package com.nuvio.tv.ui.screens.collection

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
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
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
                val canSaveFolder = folder.sources.isNotEmpty()
                NuvioButton(onClick = { viewModel.saveFolderEdit() }, enabled = canSaveFolder) {
                    Text(stringResource(R.string.collections_editor_save))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

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

                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.collections_editor_hero_backdrop), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                                .width(56.dp)
                                .height(56.dp),
                            shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = CardDefaults.colors(containerColor = NuvioColors.BackgroundCard),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            AsyncImage(
                                model = folder.heroBackdropUrl,
                                contentDescription = stringResource(R.string.cd_preview),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.collections_editor_title_logo), style = MaterialTheme.typography.labelLarge, color = NuvioColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                                .height(56.dp),
                            shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = CardDefaults.colors(containerColor = NuvioColors.BackgroundCard),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            AsyncImage(
                                model = folder.titleLogoUrl,
                                contentDescription = stringResource(R.string.cd_preview),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
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
                        "${folder.sources.size} ${stringResource(R.string.collections_editor_sources).lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextTertiary
                    )
                }
            }

            itemsIndexed(
                items = folder.sources,
                key = { _, source -> collectionSourceKey(source) }
            ) { index, source ->
                val addonSource = source as? AddonCatalogCollectionSource
                val tmdbSource = source as? TmdbCollectionSource
                val catalog = addonSource?.let { addon ->
                    uiState.availableCatalogs.find {
                        it.addonId == addon.addonId && it.type == addon.type && it.catalogId == addon.catalogId
                    }
                }
                val isMissing = addonSource != null && catalog == null
                val sourceKey = collectionSourceKey(source)
                val removeFocusRequester = catalogFocusRequesters.getOrPut(sourceKey) { FocusRequester() }
                val genreLabel = addonSource?.genre ?: if (catalog?.genreRequired == true) {
                    stringResource(R.string.collections_editor_select_genre)
                } else {
                    stringResource(R.string.collections_editor_all_genres)
                }
                val hasGenreOptions = addonSource != null && catalog?.genreOptions?.isNotEmpty() == true
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
                                text = catalog?.catalogName?.replaceFirstChar { it.uppercase() }
                                    ?: tmdbSource?.title
                                    ?: addonSource?.catalogId
                                    ?: stringResource(R.string.collections_editor_source),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isMissing) NuvioColors.Error else NuvioColors.TextPrimary
                            )
                            Text(
                                text = when {
                                    isMissing -> stringResource(R.string.collections_editor_addon_missing, addonSource.addonId)
                                    addonSource != null && catalog != null -> "${addonSource.type} - ${catalog.addonName}"
                                    tmdbSource != null -> tmdbSourceSubtitle(tmdbSource)
                                    else -> stringResource(R.string.collections_editor_source)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMissing) NuvioColors.Error.copy(alpha = 0.7f) else NuvioColors.TextTertiary
                            )
                            if (hasGenreOptions) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(NuvioColors.BackgroundElevated)
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.collections_editor_genre_filter),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NuvioColors.TextSecondary
                                        )
                                    }
                                    Text(
                                        text = genreLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NuvioColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Button(
                                        onClick = { viewModel.showGenrePicker(index) },
                                        colors = ButtonDefaults.colors(
                                            containerColor = NuvioColors.BackgroundElevated,
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
                                        Text(stringResource(R.string.collections_editor_choose_genre))
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (tmdbSource != null) {
                                Button(
                                    onClick = { viewModel.editTmdbSource(index) },
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
                                    Icon(Icons.Default.Edit, stringResource(R.string.cd_edit))
                                }
                            }
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
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.cd_move_down), tint = if (index < folder.sources.size - 1) NuvioColors.TextSecondary else NuvioColors.TextTertiary)
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NuvioButton(onClick = { viewModel.showCatalogPicker() }) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.collections_editor_add_catalog))
                    }
                    NuvioButton(onClick = { viewModel.showTmdbSourcePicker() }) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.collections_editor_add_tmdb_source))
                    }
                }
            }
        }
    }
}
