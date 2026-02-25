@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.SubtitleOrganizationMode
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

// Subtitle text color options (matching mobile app)
private val SUBTITLE_TEXT_COLORS = listOf(
    Color.White,
    Color(0xFFD9D9D9),  // Light gray
    Color(0xFFFFD700),  // Yellow/Gold
    Color(0xFF00E5FF),  // Cyan
    Color(0xFFFF5C5C),  // Red
    Color(0xFF00FF88),  // Green
)

// Subtitle outline color options
private val SUBTITLE_OUTLINE_COLORS = listOf(
    Color.Black,
    Color.White,
    Color(0xFF00E5FF),  // Cyan
    Color(0xFFFF5C5C),  // Red
)

@Composable
internal fun SubtitleSelectionDialog(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    preferredLanguage: String,
    subtitleOrganizationMode: SubtitleOrganizationMode,
    isLoadingAddons: Boolean,
    onInternalTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit,
    onDisableSubtitles: () -> Unit,
    onOpenStylePanel: () -> Unit,
    onOpenDelayOverlay: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val uniqueLanguageCount = remember(addonSubtitles) {
        addonSubtitles
            .map { normalizeSubtitleLanguageCode(it.lang) }
            .distinct()
            .size
    }
    val strAddons = stringResource(R.string.subtitle_tab_addons)
    val strLanguages = stringResource(R.string.subtitle_tab_languages)
    val addonsTabTitle = when (subtitleOrganizationMode) {
        SubtitleOrganizationMode.BY_LANGUAGE -> strLanguages
        SubtitleOrganizationMode.NONE,
        SubtitleOrganizationMode.BY_ADDON -> strAddons
    }
    val tabs = listOf(
        stringResource(R.string.subtitle_tab_builtin),
        addonsTabTitle,
        stringResource(R.string.subtitle_tab_style),
        stringResource(R.string.subtitle_tab_delay)
    )
    val tabFocusRequesters = remember { tabs.map { FocusRequester() } }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(450.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0F0F0F))
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.subtitle_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Tab row
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    tabs.forEachIndexed { index, _ ->
                        val onTabClick = when (index) {
                            2 -> {
                                { onOpenStylePanel() }
                            }
                            3 -> {
                                { onOpenDelayOverlay() }
                            }
                            else -> {
                                { selectedTabIndex = index }
                            }
                        }
                        SubtitleTab(
                            title = tabs[index],
                            isSelected = selectedTabIndex == index,
                            badgeCount = if (index == 1) uniqueLanguageCount else null,
                            focusRequester = tabFocusRequesters[index],
                            onClick = onTabClick
                        )
                        if (index < tabs.lastIndex) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }

                // Content based on selected tab
                when (selectedTabIndex) {
                    0 -> InternalSubtitlesContent(
                        tracks = internalTracks,
                        selectedIndex = selectedInternalIndex,
                        selectedAddonSubtitle = selectedAddonSubtitle,
                        preferredLanguage = preferredLanguage,
                        onTrackSelected = onInternalTrackSelected,
                        onDisableSubtitles = onDisableSubtitles
                    )
                    1 -> AddonSubtitlesContent(
                        subtitles = addonSubtitles,
                        selectedSubtitle = selectedAddonSubtitle,
                        preferredLanguage = preferredLanguage,
                        subtitleOrganizationMode = subtitleOrganizationMode,
                        isLoading = isLoadingAddons,
                        onSubtitleSelected = onAddonSubtitleSelected
                    )
                    2 -> Unit
                    3 -> Unit
                }
            }
        }
    }

    // Request focus on the first tab when dialog opens
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            tabFocusRequesters[0].requestFocus()
        } catch (_: Exception) {}
    }
}

@Composable
private fun SubtitleTab(
    title: String,
    isSelected: Boolean,
    badgeCount: Int?,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = when {
                isSelected -> Color.White.copy(alpha = 0.18f)
                isFocused -> Color.White.copy(alpha = 0.12f)
                else -> Color.White.copy(alpha = 0.06f)
            },
            focusedContainerColor = if (isSelected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f)
            )

            if (badgeCount != null && badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Color.White.copy(alpha = 0.2f) else NuvioColors.Secondary)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.White else NuvioColors.OnSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun InternalSubtitlesContent(
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    selectedAddonSubtitle: Subtitle?,
    preferredLanguage: String,
    onTrackSelected: (Int) -> Unit,
    onDisableSubtitles: () -> Unit
) {
    val orderedTracks = remember(tracks, preferredLanguage) {
        sortByPreferredLanguage(tracks, preferredLanguage) { it.language }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 4.dp),
        modifier = Modifier.height(300.dp)
    ) {
        item {
            TrackItem(
                track = TrackInfo(index = -1, name = stringResource(R.string.subtitle_none), language = null),
                isSelected = selectedIndex == -1 && selectedAddonSubtitle == null,
                onClick = onDisableSubtitles
            )
        }

        if (tracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.subtitle_no_builtin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            items(orderedTracks) { track ->
                TrackItem(
                    track = track,
                    isSelected = track.index == selectedIndex && selectedAddonSubtitle == null,
                    onClick = { onTrackSelected(track.index) }
                )
            }
        }
    }
}

@Composable
private fun AddonSubtitlesContent(
    subtitles: List<Subtitle>,
    selectedSubtitle: Subtitle?,
    preferredLanguage: String,
    subtitleOrganizationMode: SubtitleOrganizationMode,
    isLoading: Boolean,
    onSubtitleSelected: (Subtitle) -> Unit
) {
    val strAll = stringResource(R.string.subtitle_all)
    val strLanguages = stringResource(R.string.subtitle_tab_languages)
    val strAddons = stringResource(R.string.subtitle_tab_addons)
    val viewData = remember(subtitles, preferredLanguage, subtitleOrganizationMode, strAll, strLanguages, strAddons) {
        buildAddonSubtitleViewData(
            subtitles = subtitles,
            preferredLanguage = preferredLanguage,
            mode = subtitleOrganizationMode,
            strAll = strAll,
            strLanguages = strLanguages,
            strAddons = strAddons
        )
    }

    var selectedFilterKey by remember(subtitles, preferredLanguage, subtitleOrganizationMode) {
        mutableStateOf(viewData.filters.firstOrNull()?.key)
    }
    val currentFilter = selectedFilterKey?.let { key ->
        viewData.filters.firstOrNull { it.key == key }
    } ?: viewData.filters.firstOrNull()
    val firstSubtitleFocusRequester = remember(currentFilter?.key) { FocusRequester() }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 4.dp),
        modifier = Modifier.height(300.dp)
    ) {
        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LoadingIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = stringResource(R.string.subtitle_loading_addon),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        } else if (subtitles.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.subtitle_no_addon),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            if (viewData.filters.isNotEmpty() && viewData.filterTitle != null) {
                item(key = "filter_selector") {
                    FilterSelector(
                        title = viewData.filterTitle,
                        filters = viewData.filters,
                        selectedKey = currentFilter?.key,
                        onFilterSelected = { selectedFilterKey = it },
                        downFocusRequester = if (currentFilter?.items?.isNotEmpty() == true) firstSubtitleFocusRequester else null
                    )
                }
            }

            items(
                items = currentFilter?.items.orEmpty(),
                key = { subtitle -> "${subtitle.addonName}:${subtitle.id}:${subtitle.url}" }
            ) { subtitle ->
                AddonSubtitleItem(
                    subtitle = subtitle,
                    isSelected = selectedSubtitle?.id == subtitle.id,
                    onClick = { onSubtitleSelected(subtitle) },
                    focusRequester = if (subtitle == currentFilter?.items?.firstOrNull()) firstSubtitleFocusRequester else null
                )
            }
        }
    }
}

@Composable
private fun FilterSelector(
    title: String,
    filters: List<SubtitleFilter>,
    selectedKey: String?,
    onFilterSelected: (String) -> Unit,
    downFocusRequester: FocusRequester?
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.65f)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filters, key = { it.key }) { filter ->
                FilterChip(
                    label = filter.label,
                    isSelected = selectedKey == filter.key,
                    onClick = { onFilterSelected(filter.key) },
                    onMoveDown = { downFocusRequester?.requestFocus() }
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onMoveDown: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    onMoveDown()
                    true
                } else {
                    false
                }
            },
        colors = CardDefaults.colors(
            containerColor = when {
                isSelected -> Color.White.copy(alpha = 0.2f)
                isFocused -> Color.White.copy(alpha = 0.12f)
                else -> Color.White.copy(alpha = 0.06f)
            },
            focusedContainerColor = if (isSelected) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.12f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

private data class SubtitleFilter(
    val key: String,
    val label: String,
    val items: List<Subtitle>
)

private data class AddonSubtitleViewData(
    val filterTitle: String?,
    val filters: List<SubtitleFilter>
)

private fun buildAddonSubtitleViewData(
    subtitles: List<Subtitle>,
    preferredLanguage: String,
    mode: SubtitleOrganizationMode,
    strAll: String,
    strLanguages: String,
    strAddons: String
): AddonSubtitleViewData {
    val preferredFirst = sortByPreferredLanguage(subtitles, preferredLanguage) { it.lang }

    return when (mode) {
        SubtitleOrganizationMode.NONE -> {
            AddonSubtitleViewData(
                filterTitle = null,
                filters = listOf(
                    SubtitleFilter(
                        key = "all",
                        label = strAll,
                        items = preferredFirst
                    )
                )
            )
        }

        SubtitleOrganizationMode.BY_LANGUAGE -> {
            AddonSubtitleViewData(
                filterTitle = strLanguages,
                filters = preferredFirst
                    .groupBy { subtitleLanguageGroupKey(it.lang) }
                    .map { (languageKey, items) ->
                        val label = Subtitle.languageCodeToName(languageKey)
                        SubtitleFilter(
                            key = "lang:$languageKey",
                            label = label,
                            items = items
                        )
                    }
            )
        }

        SubtitleOrganizationMode.BY_ADDON -> {
            AddonSubtitleViewData(
                filterTitle = strAddons,
                filters = preferredFirst
                    .groupBy { it.addonName }
                    .map { (addon, items) ->
                        SubtitleFilter(
                            key = "addon:$addon",
                            label = addon,
                            items = sortByPreferredLanguage(items, preferredLanguage) { it.lang }
                        )
                    }
            )
        }
    }
}

private fun subtitleLanguageGroupKey(language: String): String {
    val normalized = normalizeSubtitleLanguageCode(language)
    if (normalized == "pt-br") {
        return "pt-br"
    }
    return normalized
        .substringBefore('-')
        .substringBefore('_')
}

private fun normalizeSubtitleLanguageCode(lang: String): String {
    return when (lang.trim().lowercase()) {
        "pt-br", "pt_br", "br", "pob" -> "pt-br"
        "pt", "pt-pt", "pt_pt", "por" -> "pt"
        "eng" -> "en"
        "spa" -> "es"
        "fre", "fra" -> "fr"
        "ger", "deu" -> "de"
        "ita" -> "it"
        "rus" -> "ru"
        "jpn" -> "ja"
        "kor" -> "ko"
        "chi", "zho" -> "zh"
        "ara" -> "ar"
        "hin" -> "hi"
        "nld", "dut" -> "nl"
        "pol" -> "pl"
        "swe" -> "sv"
        "nor" -> "no"
        "dan" -> "da"
        "fin" -> "fi"
        "tur" -> "tr"
        "ell", "gre" -> "el"
        "heb" -> "he"
        "tha" -> "th"
        "vie" -> "vi"
        "ind" -> "id"
        "msa", "may" -> "ms"
        "ces", "cze" -> "cs"
        "hun" -> "hu"
        "ron", "rum" -> "ro"
        "ukr" -> "uk"
        "bul" -> "bg"
        "hrv" -> "hr"
        "srp" -> "sr"
        "slk", "slo" -> "sk"
        "slv" -> "sl"
        else -> lang.trim().lowercase()
    }
}

private fun matchesPreferredLanguage(language: String?, preferredLanguage: String): Boolean {
    if (language.isNullOrBlank()) return false
    val preferred = preferredLanguage.trim().lowercase()
    if (preferred.isBlank() || preferred == "none") return false

    val normalizedLanguage = normalizeSubtitleLanguageCode(language)
    val normalizedPreferred = normalizeSubtitleLanguageCode(preferred)

    if (normalizedPreferred == "pt") {
        return normalizedLanguage == "pt"
    }

    return normalizedLanguage == normalizedPreferred ||
        normalizedLanguage.startsWith("$normalizedPreferred-") ||
        normalizedLanguage.startsWith("${normalizedPreferred}_")
}

private fun <T> sortByPreferredLanguage(
    items: List<T>,
    preferredLanguage: String,
    languageSelector: (T) -> String?
): List<T> {
    val (preferred, others) = items.partition { item ->
        matchesPreferredLanguage(languageSelector(item), preferredLanguage)
    }
    return preferred + others
}

@Composable
private fun AddonSubtitleItem(
    subtitle: Subtitle,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = when {
                isSelected -> Color.White.copy(alpha = 0.12f)
                else -> Color.White.copy(alpha = 0.05f)
            },
            focusedContainerColor = Color.White.copy(alpha = 0.15f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Subtitle.languageCodeToName(normalizeSubtitleLanguageCode(subtitle.lang)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                Text(
                    text = subtitle.addonName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = NuvioColors.Secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// -- Style Tab (matching mobile grouped section layout) --

@Composable
private fun SubtitleStyleContent(
    subtitleStyle: SubtitleStyleSettings,
    onEvent: (PlayerEvent) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 4.dp),
        modifier = Modifier.height(340.dp)
    ) {
        // Core section
        item {
            StyleSection(
                title = stringResource(R.string.subtitle_section_core),
                icon = Icons.Default.FormatSize
            ) {
                // Font Size
                StyleSettingRow(label = stringResource(R.string.subtitle_font_size)) {
                    StyleStepperButton(
                        icon = Icons.Default.Remove,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size - 10)) }
                    )
                    StyleValueDisplay(text = "${subtitleStyle.size}%")
                    StyleStepperButton(
                        icon = Icons.Default.Add,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size + 10)) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bold
                StyleSettingRow(label = stringResource(R.string.subtitle_bold)) {
                    StyleToggleButton(
                        isEnabled = subtitleStyle.bold,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleBold(!subtitleStyle.bold)) }
                    )
                }
            }
        }

        // Advanced section
        item {
            StyleSection(
                title = stringResource(R.string.subtitle_section_advanced),
                icon = Icons.Default.Tune
            ) {
                // Text Color
                Column {
                    Text(
                        text = stringResource(R.string.subtitle_text_color),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUBTITLE_TEXT_COLORS.forEach { color ->
                            StyleColorChip(
                                color = color,
                                isSelected = subtitleStyle.textColor == color.toArgb(),
                                onClick = { onEvent(PlayerEvent.OnSetSubtitleTextColor(color.toArgb())) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Outline
                StyleSettingRow(label = stringResource(R.string.subtitle_outline)) {
                    StyleToggleButton(
                        isEnabled = subtitleStyle.outlineEnabled,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(!subtitleStyle.outlineEnabled)) }
                    )
                }

                // Outline Color (only when outline enabled)
                if (subtitleStyle.outlineEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Column {
                        Text(
                            text = stringResource(R.string.subtitle_outline_color),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SUBTITLE_OUTLINE_COLORS.forEach { color ->
                                StyleColorChip(
                                    color = color,
                                    isSelected = subtitleStyle.outlineColor == color.toArgb(),
                                    onClick = { onEvent(PlayerEvent.OnSetSubtitleOutlineColor(color.toArgb())) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Offset
                StyleSettingRow(label = stringResource(R.string.subtitle_bottom_offset)) {
                    StyleStepperButton(
                        icon = Icons.Default.Remove,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset - 5)) }
                    )
                    StyleValueDisplay(text = "${subtitleStyle.verticalOffset}")
                    StyleStepperButton(
                        icon = Icons.Default.Add,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset + 5)) }
                    )
                }
            }
        }

        // Reset Defaults
        item {
            var isFocused by remember { mutableStateOf(false) }
            Card(
                onClick = { onEvent(PlayerEvent.OnResetSubtitleDefaults) },
                modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
                colors = CardDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    focusedContainerColor = Color.White.copy(alpha = 0.18f)
                ),
                shape = CardDefaults.shape(RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = stringResource(R.string.subtitle_reset_defaults),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun StyleSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawWithCache {
                val radius = 16.dp.toPx()
                val bgColor = Color.White.copy(alpha = 0.05f)
                onDrawBehind {
                    drawRoundRect(
                        color = bgColor,
                        cornerRadius = CornerRadius(radius, radius)
                    )
                }
            }
            .padding(16.dp)
    ) {
        // Section header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        content()
    }
}

@Composable
private fun StyleSettingRow(
    label: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
private fun StyleStepperButton(
    icon: ImageVector,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.18f),
            focusedContainerColor = Color.White.copy(alpha = 0.3f),
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun StyleValueDisplay(text: String) {
    Box(
        modifier = Modifier
            .drawWithCache {
                val radius = 10.dp.toPx()
                val bgColor = Color.White.copy(alpha = 0.12f)
                onDrawBehind {
                    drawRoundRect(
                        color = bgColor,
                        cornerRadius = CornerRadius(radius, radius)
                    )
                }
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

@Composable
private fun StyleColorChip(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isLight = (color.red + color.green + color.blue) / 3f > 0.5f
    var isFocused by remember { mutableStateOf(false) }
    val ringColor = when {
        isFocused -> NuvioColors.FocusRing
        isSelected -> Color.White
        else -> Color.Transparent
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .drawWithCache {
                val strokeWidth = 2.dp.toPx()
                onDrawWithContent {
                    drawContent()
                    if (ringColor.alpha > 0f) {
                        drawCircle(
                            color = ringColor,
                            radius = (size.minDimension / 2f) - (strokeWidth / 2f),
                            style = Stroke(width = strokeWidth)
                        )
                    }
                }
            }
            .onFocusChanged { isFocused = it.isFocused },
        colors = IconButtonDefaults.colors(
            containerColor = color,
            focusedContainerColor = color,
            contentColor = if (isLight) Color.Black else Color.White,
            focusedContentColor = if (isLight) Color.Black else Color.White
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        if (isSelected) {
            Icon(Icons.Default.Check, "Selected", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StyleToggleButton(
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isEnabled) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.25f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp))
    ) {
        Text(
            text = if (isEnabled) stringResource(R.string.subtitle_on) else stringResource(R.string.subtitle_off),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

// Shared TrackItem composable (used by both audio and subtitle dialogs)
@Composable
internal fun TrackItem(
    track: TrackInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
            focusedContainerColor = Color.White.copy(alpha = 0.15f)
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.9f)
                )
                if (track.language != null) {
                    Text(
                        text = track.language.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = NuvioColors.Secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
