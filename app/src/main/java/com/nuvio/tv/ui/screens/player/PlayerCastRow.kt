@file:OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaCastMember

@Composable
internal fun PlayerCastRow(
    castMembers: List<MetaCastMember>,
    firstCastItemFocusRequester: FocusRequester,
    onCastMemberClick: (MetaCastMember) -> Unit,
    onDownKey: () -> Unit,
    upFocusRequester: FocusRequester,
    onFocused: () -> Unit,
    onUpKey: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val displayMembers = remember(castMembers) { castMembers.take(10) }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.pause_cast_label),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.focusRestorer { firstCastItemFocusRequester }
        ) {
            items(
                items = displayMembers,
                key = { it.tmdbId ?: it.name.hashCode() }
            ) { member ->
                val isFirst = member == displayMembers.firstOrNull()
                PlayerCastMemberItem(
                    member = member,
                    focusRequester = if (isFirst) firstCastItemFocusRequester else null,
                    upFocusRequester = upFocusRequester,
                    onDownKey = onDownKey,
                    onUpKey = onUpKey,
                    onFocused = onFocused,
                    onClick = { onCastMemberClick(member) }
                )
            }
        }
    }
}

@Composable
private fun PlayerCastMemberItem(
    member: MetaCastMember,
    focusRequester: FocusRequester?,
    upFocusRequester: FocusRequester,
    onDownKey: () -> Unit,
    onUpKey: (() -> Unit)?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cardSizePx = remember(density) { with(density) { 64.dp.roundToPx() } }
    var isFocused by remember { mutableStateOf(false) }

    val photoModel = remember(context, member.photo, cardSizePx) {
        member.photo?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(width = cardSizePx, height = cardSizePx)
                .build()
        }
    }

    Column(
        modifier = Modifier.width(90.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .size(64.dp)
                .align(Alignment.Start)
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                )
                .then(
                    if (onUpKey == null) Modifier.focusProperties { up = upFocusRequester }
                    else Modifier
                )
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    ) {
                        onDownKey()
                        true
                    } else if (onUpKey != null &&
                        keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                    ) {
                        onUpKey()
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused) onFocused()
                },
            shape = CardDefaults.shape(shape = CircleShape),
            colors = CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, Color.White),
                    shape = CircleShape
                )
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val bgColor = if (isFocused) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f)
                val bgPainter = remember(bgColor) { ColorPainter(bgColor) }

                if (photoModel != null) {
                    AsyncImage(
                        model = photoModel,
                        contentDescription = member.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = bgPainter,
                        error = bgPainter,
                        fallback = bgPainter
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(bgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = member.name.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = member.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        val character = member.character
        if (!character.isNullOrBlank()) {
            val displayCharacter = when {
                character.equals("Creator", ignoreCase = true) -> stringResource(R.string.cast_role_creator)
                character.equals("Director", ignoreCase = true) -> stringResource(R.string.cast_role_director)
                character.equals("Writer", ignoreCase = true) -> stringResource(R.string.cast_role_writer)
                else -> character
            }
            Text(
                text = displayCharacter,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
