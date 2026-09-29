@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import com.nuvio.tv.R
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
fun MDBListSettingsContent(
    viewModel: MDBListSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showApiKeyDialog by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.mdblist_title),
            subtitle = stringResource(R.string.mdblist_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val mdbListState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = mdbListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "mdblist_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_enable_title),
                        subtitle = stringResource(R.string.mdblist_enable_subtitle),
                        checked = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                        modifier = Modifier
                            .padding(top = NuvioTheme.spacing.xxs)
                            .then(
                                if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        })
                    )
                }

                item(key = "mdblist_api_key") {
                    SettingsActionRow(
                        title = stringResource(R.string.mdblist_api_key_title),
                        subtitle = stringResource(R.string.mdblist_ratings_credentials_subtitle),
                        value = maskApiKey(uiState.apiKey, stringResource(
                            if (uiState.isConnected) R.string.mdblist_ratings_connected_account
                            else R.string.mdblist_not_set
                        )),
                        onClick = { showApiKeyDialog = true },
                        enabled = uiState.enabled
                    )
                }

                item(key = "mdblist_show_on_hero") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_show_on_hero_title),
                        subtitle = stringResource(R.string.mdblist_show_on_hero_subtitle),
                        checked = uiState.showOnHero,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleShowOnHero(!uiState.showOnHero)) }
                    )
                }

                item(key = "mdblist_rating_order_header") {
                    Text(
                        text = stringResource(R.string.mdblist_rating_order_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                    )
                }

                items(
                    count = uiState.ratingOrder.size,
                    key = { index -> "mdblist_rating_${uiState.ratingOrder[index]}" }
                ) { index ->
                    val provider = uiState.ratingOrder[index]
                    val isFirst = index == 0
                    val isLast = index == uiState.ratingOrder.lastIndex
                    val isProviderEnabled = isRatingProviderEnabled(uiState, provider)
                    val toggleEvent = toggleEventForProvider(provider, !isProviderEnabled)
                    RatingOrderToggleRow(
                        title = ratingProviderLabel(provider),
                        checked = isProviderEnabled,
                        enabled = uiState.enabled,
                        onToggle = { toggleEvent?.let { viewModel.onEvent(it) } },
                        onMoveUp = if (!isFirst && uiState.enabled) {
                            { viewModel.onEvent(MDBListSettingsEvent.MoveRatingUp(provider)) }
                        } else null,
                        onMoveDown = if (!isLast && uiState.enabled) {
                            { viewModel.onEvent(MDBListSettingsEvent.MoveRatingDown(provider)) }
                        } else null
                    )
                }
            }
            SettingsVerticalScrollIndicators(state = mdbListState)
            }
        }
    }

    if (showApiKeyDialog) {
        MDBListApiKeyDialog(
            currentValue = uiState.apiKey,
            viewModel = viewModel,
            onSaved = { showApiKeyDialog = false },
            onClear = { viewModel.validateAndSaveApiKey("") {}; showApiKeyDialog = false },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

@Composable
private fun MDBListApiKeyDialog(
    currentValue: String,
    viewModel: MDBListSettingsViewModel,
    onSaved: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val validating by viewModel.validating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val invalidApiKeyMsg = stringResource(R.string.mdblist_invalid_api_key)

    LaunchedEffect(Unit) {
        viewModel.validationError.collect {
            Toast.makeText(context, invalidApiKeyMsg, Toast.LENGTH_SHORT).show()
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.mdblist_dialog_title),
        subtitle = stringResource(R.string.mdblist_ratings_key_override_subtitle),
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioTheme.colors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.mdblist_dialog_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_clear))
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { if (!validating) viewModel.validateAndSaveApiKey(value, onSaved) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(if (validating) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}

private fun maskApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}

@Composable
private fun RatingOrderToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        SettingsToggleRow(
            title = title,
            subtitle = null,
            checked = checked,
            enabled = enabled,
            onToggle = onToggle,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = { onMoveUp?.invoke() },
            enabled = onMoveUp != null,
            modifier = Modifier.width(40.dp),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                contentColor = NuvioTheme.colors.TextPrimary,
                disabledContainerColor = NuvioTheme.colors.BackgroundElevated,
                disabledContentColor = NuvioTheme.colors.TextTertiary
            ),
            scale = ButtonDefaults.scale(focusedScale = 1.05f),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("▲", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { onMoveDown?.invoke() },
            enabled = onMoveDown != null,
            modifier = Modifier.width(40.dp),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                contentColor = NuvioTheme.colors.TextPrimary,
                disabledContainerColor = NuvioTheme.colors.BackgroundElevated,
                disabledContentColor = NuvioTheme.colors.TextTertiary
            ),
            scale = ButtonDefaults.scale(focusedScale = 1.05f),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("▼", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun ratingProviderLabel(provider: String): String = when (provider) {
    "trakt" -> "Trakt"
    "imdb" -> "IMDb"
    "tmdb" -> "TMDB"
    "tomatoes" -> "Rotten Tomatoes"
    "audience" -> "Audience Score"
    "letterboxd" -> "Letterboxd"
    "metacritic" -> "Metacritic"
    "mal" -> "MyAnimeList"
    else -> provider.replaceFirstChar { it.uppercase() }
}

private fun isRatingProviderEnabled(state: MDBListSettingsUiState, provider: String): Boolean = when (provider) {
    "trakt" -> state.showTrakt
    "imdb" -> state.showImdb
    "tmdb" -> state.showTmdb
    "tomatoes" -> state.showTomatoes
    "audience" -> state.showAudience
    "letterboxd" -> state.showLetterboxd
    "metacritic" -> state.showMetacritic
    "mal" -> state.showMal
    else -> false
}

private fun toggleEventForProvider(provider: String, enabled: Boolean): MDBListSettingsEvent? = when (provider) {
    "trakt" -> MDBListSettingsEvent.ToggleTrakt(enabled)
    "imdb" -> MDBListSettingsEvent.ToggleImdb(enabled)
    "tmdb" -> MDBListSettingsEvent.ToggleTmdb(enabled)
    "tomatoes" -> MDBListSettingsEvent.ToggleTomatoes(enabled)
    "audience" -> MDBListSettingsEvent.ToggleAudience(enabled)
    "letterboxd" -> MDBListSettingsEvent.ToggleLetterboxd(enabled)
    "metacritic" -> MDBListSettingsEvent.ToggleMetacritic(enabled)
    "mal" -> MDBListSettingsEvent.ToggleMal(enabled)
    else -> null
}
