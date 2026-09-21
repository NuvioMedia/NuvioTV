@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppIconOption
import com.nuvio.tv.launcher.AppIconSettingsState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun AppIconPickerDialog(
    state: AppIconSettingsState,
    onSelected: (AppIconOption) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedFocusRequester = remember { FocusRequester() }

    LaunchedEffect(state.selected) {
        selectedFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.appearance_app_icon_picker_title),
        subtitle = stringResource(
            if (state.changeFailed) {
                R.string.appearance_app_icon_change_failed
            } else {
                R.string.appearance_app_icon_picker_subtitle
            }
        ),
        width = 760.dp,
        suppressFirstKeyUp = false,
        usePlatformDefaultWidth = false,
        contentPadding = 18.dp,
        contentSpacing = 12.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIconOption.entries.chunked(3).forEach { options ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    options.forEach { option ->
                        AppIconOptionCard(
                            option = option,
                            selected = state.selected == option,
                            enabled = state.pending == null,
                            onClick = { onSelected(option) },
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (state.selected == option) {
                                        Modifier.focusRequester(selectedFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppIconChangeConfirmationDialog(
    option: AppIconOption,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val confirmFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        confirmFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.appearance_app_icon_confirmation_title),
        subtitle = stringResource(
            R.string.appearance_app_icon_confirmation_message,
            option.localizedName()
        ),
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(confirmFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

@Composable
private fun AppIconOptionCard(
    option: AppIconOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier.alpha(if (enabled) 1f else 0.55f),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = if (selected) {
                Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                    shape = shape
                )
            } else {
                Border.None
            },
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Image(
                painter = painterResource(option.bannerResource),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(option.iconResource),
                    contentDescription = null,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(7.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = option.localizedName(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (selected) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = NuvioTheme.colors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

internal val AppIconOption.iconResource: Int
    get() = when (this) {
        AppIconOption.ORIGINAL -> R.mipmap.ic_launcher
        AppIconOption.ARCTIC_BLUE -> R.mipmap.ic_launcher_arctic_blue
        AppIconOption.EMERALD -> R.mipmap.ic_launcher_emerald
        AppIconOption.ROSE_GOLD -> R.mipmap.ic_launcher_rose_gold
        AppIconOption.COPPER -> R.mipmap.ic_launcher_copper
        AppIconOption.GRAPHITE -> R.mipmap.ic_launcher_graphite
    }

internal val AppIconOption.bannerResource: Int
    get() = when (this) {
        AppIconOption.ORIGINAL -> R.mipmap.banner
        AppIconOption.ARCTIC_BLUE -> R.mipmap.banner_arctic_blue
        AppIconOption.EMERALD -> R.mipmap.banner_emerald
        AppIconOption.ROSE_GOLD -> R.mipmap.banner_rose_gold
        AppIconOption.COPPER -> R.mipmap.banner_copper
        AppIconOption.GRAPHITE -> R.mipmap.banner_graphite
    }

@Composable
internal fun AppIconOption.localizedName(): String = when (this) {
    AppIconOption.ORIGINAL -> stringResource(R.string.appearance_app_icon_original)
    AppIconOption.ARCTIC_BLUE -> stringResource(R.string.appearance_app_icon_arctic_blue)
    AppIconOption.EMERALD -> stringResource(R.string.appearance_app_icon_emerald)
    AppIconOption.ROSE_GOLD -> stringResource(R.string.appearance_app_icon_rose_gold)
    AppIconOption.COPPER -> stringResource(R.string.appearance_app_icon_copper)
    AppIconOption.GRAPHITE -> stringResource(R.string.appearance_app_icon_graphite)
}
