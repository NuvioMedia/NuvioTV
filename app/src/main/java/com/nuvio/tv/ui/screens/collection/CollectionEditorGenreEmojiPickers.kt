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
fun GenrePickerContent(
    title: String,
    selectedGenre: String?,
    genreOptions: List<String>,
    allowAll: Boolean,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit
) {
    val firstOptionFocusRequester = remember { FocusRequester() }

    LaunchedEffect(title, selectedGenre, genreOptions) {
        repeat(5) { androidx.compose.runtime.withFrameNanos { } }
        try { firstOptionFocusRequester.requestFocus() } catch (_: Exception) {}
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.collections_editor_genre_filter),
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
            NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_back)) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var optionIndex = 0
            if (allowAll) {
                item(key = "genre_all") {
                    GenrePickerOptionCard(
                        title = stringResource(R.string.collections_editor_all_genres),
                        selected = selectedGenre == null,
                        onClick = { onSelect(null) },
                        modifier = Modifier.focusRequester(firstOptionFocusRequester)
                    )
                }
                optionIndex += 1
            }

            itemsIndexed(
                items = genreOptions,
                key = { _, genre -> genre }
            ) { index, genre ->
                val useFirstRequester = optionIndex == 0 && index == 0
                GenrePickerOptionCard(
                    title = genre,
                    selected = selectedGenre == genre,
                    onClick = { onSelect(genre) },
                    modifier = if (useFirstRequester) Modifier.focusRequester(firstOptionFocusRequester) else Modifier
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GenrePickerOptionCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioColors.Secondary.copy(alpha = 0.15f) else NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            border = if (selected) Border(
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = NuvioColors.TextPrimary
            )
            if (selected) {
                Text(
                    text = stringResource(R.string.cd_selected),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioColors.Secondary
                )
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
fun EmojiPickerContent(
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
