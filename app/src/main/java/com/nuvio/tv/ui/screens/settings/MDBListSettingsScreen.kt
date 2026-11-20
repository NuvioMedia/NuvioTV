@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material3.Card as Material3Card
import androidx.compose.material3.CardDefaults as Material3CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.TvReorderList
import com.nuvio.tv.ui.components.tvReorderDisabledFocusBorder
import com.nuvio.tv.ui.theme.NuvioMotion

private data class RatingOrderItem(
    val provider: String,
    val checked: Boolean,
    val enabled: Boolean
)

@Composable
fun MDBListSettingsContent(
    viewModel: MDBListSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showApiKeyDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.cancelRatingMove() }
    }

    MDBListSettingsBody(
        uiState = uiState,
        initialFocusRequester = initialFocusRequester,
        onEvent = viewModel::onEvent,
        onStartMove = viewModel::enterRatingMoveMode,
        onMoveBy = viewModel::moveRatingDuringDrag,
        onConfirmMove = { viewModel.confirmRatingMove() },
        onCancelMove = viewModel::cancelRatingMove,
        onOpenApiKeyDialog = { showApiKeyDialog = true }
    )

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
internal fun MDBListSettingsBody(
    uiState: MDBListSettingsUiState,
    initialFocusRequester: FocusRequester? = null,
    onEvent: (MDBListSettingsEvent) -> Unit,
    onStartMove: (String) -> Unit,
    onMoveBy: (String, Int) -> Int?,
    onConfirmMove: (String) -> Unit,
    onCancelMove: () -> Unit,
    onOpenApiKeyDialog: () -> Unit
) {
    val ratingItems = remember(uiState) {
        uiState.ratingOrder.map { provider ->
            RatingOrderItem(
                provider = provider,
                checked = isRatingProviderEnabled(uiState, provider),
                enabled = uiState.enabled
            )
        }
    }
    val moveButtonFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

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
            val mdbScrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize()) {
                TvReorderList(
                    items = ratingItems,
                    key = { it.provider },
                    canMove = { item ->
                        item.enabled &&
                            ratingItems.size > 1 &&
                            uiState.movingRatingKey == null
                    },
                    movingKey = uiState.movingRatingKey,
                    onStartMove = onStartMove,
                    onMoveBy = onMoveBy,
                    onConfirmMove = onConfirmMove,
                    onCancelMove = onCancelMove,
                    modifier = Modifier.fillMaxSize(),
                    scrollState = mdbScrollState,
                    rowSpacing = 10.dp,
                    contentPadding = PaddingValues(
                        top = NuvioTheme.spacing.xxs,
                        start = NuvioTheme.spacing.xxs,
                        end = NuvioTheme.spacing.xxs,
                        bottom = NuvioTheme.spacing.sm
                    ),
                    header = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SettingsToggleRow(
                                title = stringResource(R.string.mdblist_enable_title),
                                subtitle = stringResource(R.string.mdblist_enable_subtitle),
                                checked = uiState.enabled,
                                onToggle = {
                                    if (uiState.movingRatingKey == null) {
                                        onEvent(MDBListSettingsEvent.ToggleEnabled(!uiState.enabled))
                                    }
                                },
                                modifier = if (initialFocusRequester != null) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                }
                            )

                            SettingsActionRow(
                                title = stringResource(R.string.mdblist_api_key_title),
                                subtitle = stringResource(R.string.mdblist_ratings_credentials_subtitle),
                                value = maskApiKey(
                                    uiState.apiKey,
                                    stringResource(
                                        if (uiState.isConnected) R.string.mdblist_ratings_connected_account
                                        else R.string.mdblist_not_set
                                    )
                                ),
                                onClick = {
                                    if (uiState.movingRatingKey == null) {
                                        onOpenApiKeyDialog()
                                    }
                                },
                                enabled = uiState.enabled
                            )

                            SettingsToggleRow(
                                title = stringResource(R.string.mdblist_show_on_hero_title),
                                subtitle = stringResource(R.string.mdblist_show_on_hero_subtitle),
                                checked = uiState.showOnHero,
                                enabled = uiState.enabled,
                                onToggle = {
                                    if (uiState.movingRatingKey == null) {
                                        onEvent(MDBListSettingsEvent.ToggleShowOnHero(!uiState.showOnHero))
                                    }
                                },
                                modifier = Modifier.focusProperties {
                                    val firstMoveRequester = ratingItems.firstOrNull()
                                        ?.takeIf { it.enabled }
                                        ?.let { moveButtonFocusRequesters[it.provider] }
                                    if (firstMoveRequester != null) down = firstMoveRequester
                                }
                            )

                            Text(
                                text = stringResource(R.string.mdblist_rating_order_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(
                                    top = NuvioTheme.spacing.sm,
                                    bottom = NuvioTheme.spacing.md
                                )
                            )
                        }
                    }
                ) { item, isMoving, onItemStartMove, onItemConfirmMove ->
                    val toggleEvent = toggleEventForProvider(item.provider, !item.checked)
                    RatingOrderRow(
                        title = ratingProviderLabel(item.provider),
                        checked = item.checked,
                        enabled = item.enabled,
                        isMoving = isMoving,
                        canMove = item.enabled && ratingItems.size > 1,
                        actionsEnabled = item.enabled && (uiState.movingRatingKey == null || isMoving),
                        moveFocusRequester = moveButtonFocusRequesters.getOrPut(item.provider) { FocusRequester() },
                        onToggle = { toggleEvent?.let { onEvent(it) } },
                        onStartMove = onItemStartMove,
                        onConfirmMove = onItemConfirmMove
                    )
                }
                val movingIndex = uiState.movingRatingKey?.let { movingKey ->
                    ratingItems.indexOfFirst { it.provider == movingKey }
                } ?: -1
                SettingsVerticalScrollIndicators(
                    state = mdbScrollState,
                    suppressBackward = movingIndex == 0,
                    suppressForward = movingIndex >= 0 && movingIndex == ratingItems.lastIndex,
                    forceBackward = movingIndex > 0,
                    forceForward = movingIndex in 0 until ratingItems.lastIndex
                )
            }
        }
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
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary,
                                            textDirection = TextDirection.Content),
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
private fun RatingOrderRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    isMoving: Boolean,
    canMove: Boolean,
    actionsEnabled: Boolean,
    moveFocusRequester: FocusRequester,
    onToggle: () -> Unit,
    onStartMove: () -> Unit,
    onConfirmMove: () -> Unit
) {
    LaunchedEffect(isMoving) {
        if (isMoving) {
            withFrameNanos { }
            runCatching { moveFocusRequester.requestFocus() }
        }
    }
    val flat = isFlatSettingsStyle()
    val contentAlpha = if (enabled) 1f else 0.4f
    val rowShape = settingsRowShape()
    val toggleShape = RoundedCornerShape(NuvioTheme.radii.md)
    val moveButtonShape = RoundedCornerShape(NuvioTheme.radii.md)
    val canToggle = enabled && actionsEnabled && !isMoving
    val moveButtonEnabled = isMoving || (canMove && actionsEnabled)

    val containerColor by animateColorAsState(
        targetValue = when {
            isMoving -> NuvioTheme.colors.FocusBackground
            flat -> Color.Transparent
            else -> NuvioTheme.colors.Background
        },
        animationSpec = NuvioMotion.focusTween(),
        label = "ratingOrderRowColor"
    )
    val containerBorder = if (isMoving) {
        BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing)
    } else {
        null
    }

    Material3Card(
        modifier = Modifier.fillMaxWidth(),
        colors = Material3CardDefaults.cardColors(containerColor = containerColor),
        elevation = Material3CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = rowShape,
        border = containerBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        if (canToggle) onToggle()
                    },
                    modifier = Modifier
                        .focusProperties { canFocus = enabled }
                        .semantics {
                            if (!canToggle) disabled()
                            role = Role.Switch
                            contentDescription = title
                            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                        },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = toggleShape
                        )
                    ),
                    shape = ClickableSurfaceDefaults.shape(toggleShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SettingsTogglePill(
                            checked = checked,
                            enabled = enabled
                        )
                    }
                }

                Button(
                    onClick = if (isMoving) onConfirmMove else onStartMove,
                    enabled = moveButtonEnabled,
                    modifier = Modifier
                        .focusRequester(moveFocusRequester)
                        .focusProperties { canFocus = enabled }
                        .semantics { role = Role.Button },
                    colors = if (isMoving) {
                        ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.Secondary,
                            contentColor = NuvioTheme.colors.OnSecondary,
                            focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                            focusedContentColor = NuvioTheme.colors.OnSecondary
                        )
                    } else {
                        ButtonDefaults.colors(
                            containerColor = if (flat) {
                                NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.7f)
                            } else {
                                NuvioTheme.colors.BackgroundCard
                            },
                            contentColor = NuvioTheme.colors.TextSecondary.copy(alpha = contentAlpha),
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary,
                            disabledContainerColor = if (flat) {
                                NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.25f)
                            } else {
                                NuvioTheme.colors.BackgroundCard.copy(alpha = 0.4f)
                            },
                            disabledContentColor = NuvioTheme.colors.TextTertiary.copy(alpha = 0.4f)
                        )
                    },
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = moveButtonShape
                        ),
                        focusedDisabledBorder = tvReorderDisabledFocusBorder(moveButtonShape)
                    ),
                    shape = ButtonDefaults.shape(moveButtonShape),
                    scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Reorder,
                        contentDescription = stringResource(R.string.cd_reorder)
                    )
                }
            }
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
