package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.ui.theme.NuvioTheme

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.filled.ChevronRight
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.ui.components.LoadingIndicator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

private sealed interface AddonExperienceModeState {
    data object Loading : AddonExperienceModeState
    data class Loaded(val mode: ExperienceMode?) : AddonExperienceModeState
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AddonManagerScreen(
    viewModel: AddonManagerViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onBackPress: () -> Unit = {},
    onNavigateToCatalogOrder: () -> Unit = {},
    onNavigateToCollections: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val firstAddonToggleFocusRequester = remember { FocusRequester() }
    val experienceModeState by remember(viewModel) {
        viewModel.experienceMode.map<ExperienceMode?, AddonExperienceModeState> {
            AddonExperienceModeState.Loaded(it)
        }
    }.collectAsState(initial = AddonExperienceModeState.Loading)
    val experienceMode = (experienceModeState as? AddonExperienceModeState.Loaded)?.mode
    if (experienceModeState is AddonExperienceModeState.Loading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator()
        }
        return
    }
    val isEssential = experienceMode == ExperienceMode.ESSENTIAL
    val webConfigMode = viewModel.webConfigMode(experienceMode ?: ExperienceMode.ADVANCED)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val surfaceFocusRequester = remember { FocusRequester() }
    val installButtonFocusRequester = remember { FocusRequester() }
    val textFieldFocusRequester = remember { FocusRequester() }
    var isEditing by remember { mutableStateOf(false) }
    val hasHomeVisibleCatalogs = remember(uiState.installedAddons) {
        uiState.installedAddons.any { addon ->
            addon.enabled && addon.catalogs.any { catalog -> !catalog.isSearchOnlyCatalog() }
        }
    }
    val manageFromPhoneSubtitle = if (webConfigMode == com.nuvio.tv.core.server.AddonWebConfigMode.COLLECTIONS_ONLY) {
        stringResource(R.string.addon_manage_collections_from_phone_subtitle)
    } else {
        stringResource(R.string.addon_manage_from_phone_subtitle)
    }
    val qrInstruction = if (webConfigMode == com.nuvio.tv.core.server.AddonWebConfigMode.COLLECTIONS_ONLY) {
        stringResource(R.string.addon_qr_collections_scan_instruction)
    } else {
        stringResource(R.string.addon_qr_scan_instruction)
    }

    BackHandler { onBackPress() }

    val defaultRefreshAddonsSubtitle = stringResource(R.string.addon_refresh_default_subtitle)
    val refreshedAddonsSubtitle = stringResource(R.string.addon_refresh_done_subtitle)
    var refreshAddonsSubtitle by remember(defaultRefreshAddonsSubtitle) {
        mutableStateOf(defaultRefreshAddonsSubtitle)
    }

    LaunchedEffect(refreshAddonsSubtitle) {
        if (refreshAddonsSubtitle != defaultRefreshAddonsSubtitle) {
            delay(5_000)
            refreshAddonsSubtitle = defaultRefreshAddonsSubtitle
        }
    }

    // When isEditing changes to true, focus the text field and show keyboard
    LaunchedEffect(isEditing) {
        if (isEditing) {
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val requestInputBarFocus = {
        coroutineScope.launch {
            repeat(2) { withFrameNanos { } }
            runCatching { surfaceFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(uiState.isQrModeActive, uiState.pendingChange, isEditing) {
        if (!uiState.isQrModeActive && uiState.pendingChange == null && !isEditing) {
            requestInputBarFocus()
        }
    }

    LaunchedEffect(uiState.transientMessage) {
        if (uiState.transientMessage != null) {
            delay(3200)
            viewModel.clearTransientMessage()
        }
    }

    DisposableEffect(lifecycleOwner, uiState.isQrModeActive, uiState.pendingChange, isEditing) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                !uiState.isQrModeActive &&
                uiState.pendingChange == null &&
                !isEditing
            ) {
                requestInputBarFocus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopQrMode() }
    }

    val addonListScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.addon_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent
                )
            }

            if (viewModel.isReadOnly) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A3A5C)),
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    ) {
                        Text(
                            text = stringResource(R.string.addon_readonly_notice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            modifier = androidx.compose.ui.Modifier.padding(NuvioTheme.spacing.lg)
                        )
                    }
                }
            }

            if (!viewModel.isReadOnly) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        colors = CardDefaults.cardColors(containerColor = NuvioTheme.colors.BackgroundCard),
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = stringResource(R.string.addon_install_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = NuvioTheme.colors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Surface always stays in the tree for stable D-pad focus
                                Surface(
                                    onClick = { isEditing = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(surfaceFocusRequester),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = NuvioTheme.colors.BackgroundElevated,
                                        focusedContainerColor = NuvioTheme.colors.BackgroundElevated
                                    ),
                                    border = ClickableSurfaceDefaults.border(
                                        border = Border(
                                            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                                        ),
                                        focusedBorder = Border(
                                            border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                                        )
                                    ),
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                                ) {
                                    Box(modifier = Modifier.padding(NuvioTheme.spacing.md)) {
                                        BasicTextField(
                                            value = uiState.installUrl,
                                            onValueChange = viewModel::onInstallUrlChange,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(textFieldFocusRequester)
                                                .onFocusChanged {
                                                    if (!it.isFocused && isEditing) {
                                                        isEditing = false
                                                        keyboardController?.hide()
                                                    }
                                                },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Uri,
                                                imeAction = ImeAction.Done
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    viewModel.installAddon()
                                                    isEditing = false
                                                    keyboardController?.hide()
                                                    installButtonFocusRequester.requestFocus()
                                                }
                                            ),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = NuvioTheme.colors.TextPrimary
                                            ),
                                            cursorBrush = SolidColor(if (isEditing) NuvioTheme.colors.Primary else Color.Transparent),
                                            decorationBox = { innerTextField ->
                                                if (uiState.installUrl.isEmpty()) {
                                                    Text(
                                                        text = stringResource(R.string.addon_install_placeholder),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = NuvioTheme.colors.TextTertiary
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        viewModel.installAddon()
                                        isEditing = false
                                        keyboardController?.hide()
                                        installButtonFocusRequester.requestFocus()
                                    },
                                    enabled = !uiState.isInstalling,
                                    modifier = Modifier.focusRequester(installButtonFocusRequester),
                                    colors = ButtonDefaults.colors(
                                        containerColor = NuvioTheme.colors.BackgroundCard,
                                        contentColor = NuvioTheme.colors.TextPrimary,
                                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                        focusedContentColor = NuvioTheme.colors.Primary
                                    ),
                                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                                ) {
                                    Text(text = if (uiState.isInstalling) stringResource(R.string.addon_installing) else stringResource(R.string.addon_install_btn))
                                }
                            }

                            AnimatedVisibility(visible = uiState.error != null) {
                                Text(
                                    text = uiState.error.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioTheme.colors.Error,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                            }
                        }
                    }
                }

            }

            item {
                ManageFromPhoneCard(
                    subtitle = manageFromPhoneSubtitle,
                    onClick = { viewModel.startQrMode(webConfigMode) }
                )
            }

            if (!viewModel.isReadOnly && !isEssential && hasHomeVisibleCatalogs) {
                item {
                    CatalogOrderEntryCard(onClick = onNavigateToCatalogOrder)
                }
            }

            if (!isEssential) {
                item {
                    CollectionsEntryCard(onClick = onNavigateToCollections)
                }
            }

            item {
                RefreshAddonsEntryCard(
                    subtitle = refreshAddonsSubtitle,
                    onClick = {
                        viewModel.requestAddonSyncNow()
                        refreshAddonsSubtitle = refreshedAddonsSubtitle
                    },
                    modifier = Modifier.focusProperties {
                        down = firstAddonToggleFocusRequester
                    }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.addon_installed_section),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                    if (uiState.isLoading && uiState.installedAddons.isEmpty()) {
                        LoadingIndicator(modifier = Modifier.height(NuvioTheme.spacing.xl))
                    }
                }
            }

            if (uiState.installedAddons.isEmpty() && !uiState.isLoading) {
                item {
                    Text(
                        text = stringResource(R.string.addon_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            } else {
                itemsIndexed(
                    items = uiState.installedAddons,
                    key = { _, addon -> addon.baseUrl }
                ) { index, addon ->
                    val bringIntoViewRequester = remember { BringIntoViewRequester() }
                    AddonCard(
                        modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester),
                        addon = addon,
                        canMoveUp = index > 0,
                        canMoveDown = index < uiState.installedAddons.lastIndex,
                        onMoveUp = {
                            viewModel.moveAddonUp(addon.baseUrl)
                            addonListScope.launch {
                                kotlinx.coroutines.delay(50)
                                bringIntoViewRequester.bringIntoView()
                            }
                        },
                        onMoveDown = {
                            viewModel.moveAddonDown(addon.baseUrl)
                            addonListScope.launch {
                                kotlinx.coroutines.delay(50)
                                bringIntoViewRequester.bringIntoView()
                            }
                        },
                        onRemove = { viewModel.removeAddon(addon.baseUrl) },
                        onEnabledChange = { enabled -> viewModel.setAddonEnabled(addon.baseUrl, enabled) },
                        isReadOnly = viewModel.isReadOnly,
                        showReorder = !isEssential,
                        toggleFocusRequester = if (index == 0) firstAddonToggleFocusRequester else null
                    )
                }
            }
        }

        // QR Code overlay — Popup renders above the entire screen
        if (uiState.isQrModeActive) {
            Popup(properties = PopupProperties(focusable = true)) {
                QrCodeOverlay(
                    qrBitmap = uiState.qrCodeBitmap,
                    serverUrl = uiState.serverUrl,
                    instruction = qrInstruction,
                    onClose = viewModel::stopQrMode,
                    hasPendingChange = uiState.pendingChange != null
                )
            }
        }

        // Confirmation dialog overlay
        if (uiState.pendingChange != null) {
            Popup(properties = PopupProperties(focusable = true)) {
                uiState.pendingChange?.let { pending ->
                    ConfirmAddonChangesDialog(
                        pendingChange = pending,
                        onConfirm = viewModel::confirmPendingChange,
                        onReject = viewModel::rejectPendingChange
                    )
                }
            }
        }

        AddonMessageOverlay(
            message = uiState.transientMessage,
            isError = uiState.transientMessageIsError
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddonMessageOverlay(
    message: String?,
    isError: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(NuvioTheme.spacing.xl),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val visibleMessage = message ?: return@AnimatedVisibility
            Surface(
                onClick = { },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isError) {
                        Color(0xFFC62828).copy(alpha = 0.92f)
                    } else {
                        Color(0xFF2E7D32).copy(alpha = 0.92f)
                    }
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Close else Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Text(
                        text = visibleMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageFromPhoneCard(
    subtitle: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(18.dp)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
                Column {
                    Text(
                        text = stringResource(R.string.addon_manage_from_phone_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CatalogOrderEntryCard(onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(18.dp)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Reorder,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
                Column {
                    Text(
                        text = stringResource(R.string.addon_reorder_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.addon_reorder_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionsEntryCard(onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(18.dp)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
                Column {
                    Text(
                        text = stringResource(R.string.collections_card_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.collections_card_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RefreshAddonsEntryCard(
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(18.dp)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
                Column {
                    Text(
                        text = stringResource(R.string.addon_refresh_action),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun QrCodeOverlay(
    qrBitmap: Bitmap?,
    serverUrl: String?,
    instruction: String,
    onClose: () -> Unit,
    hasPendingChange: Boolean = false,
    qrSize: Dp = 220.dp
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(hasPendingChange) {
        if (!hasPendingChange) {
            focusRequester.requestFocus()
        }
    }

    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_qr_code),
                    modifier = Modifier.size(qrSize),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

            if (serverUrl != null) {
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

            Surface(
                onClick = onClose,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuvioTheme.colors.Surface,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                        shape = RoundedCornerShape(50)
                    )
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = NuvioTheme.colors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.addon_qr_close),
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ConfirmAddonChangesDialog(
    pendingChange: PendingChangeInfo,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BackHandler { onReject() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = { },
            modifier = Modifier
                .width(560.dp)
                .heightIn(max = 640.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioTheme.colors.SurfaceVariant
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.xl))
        ) {
            Column(
                modifier = Modifier.padding(NuvioTheme.spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.addon_confirm_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                Text(
                    text = stringResource(R.string.addon_confirm_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(
                            color = NuvioTheme.colors.Surface,
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(NuvioTheme.spacing.md)
                            .verticalScroll(scrollState)
                    ) {
                        if (pendingChange.addedUrls.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_added),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.Success,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = NuvioTheme.spacing.xs)
                            )
                            pendingChange.addedUrls.forEach { url ->
                                val displayName = pendingChange.addedNames[url] ?: url
                                Text(
                                    text = "+ $displayName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.Success,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        }

                        if (pendingChange.removedUrls.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_removed),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.Error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = NuvioTheme.spacing.xs)
                            )
                            pendingChange.removedUrls.forEach { url ->
                                val displayName = pendingChange.removedNames[url] ?: url
                                Text(
                                    text = "- $displayName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.Error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        }

                        if (pendingChange.catalogsReordered) {
                            Text(
                                text = stringResource(R.string.addon_confirm_catalog_reordered),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            )
                        }

                        if (pendingChange.disabledCatalogNames.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_catalogs_disabled),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.Error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = NuvioTheme.spacing.xs)
                            )
                            pendingChange.disabledCatalogNames.forEach { name ->
                                Text(
                                    text = "- $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.Error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        }

                        if (pendingChange.enabledCatalogNames.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.addon_confirm_catalogs_enabled),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.Success,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = NuvioTheme.spacing.xs)
                            )
                            pendingChange.enabledCatalogNames.forEach { name ->
                                Text(
                                    text = "+ $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.Success,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                                )
                            }
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        }

                        if (pendingChange.collectionsChanged) {
                            Text(
                                text = stringResource(R.string.addon_pending_collections_updated),
                                style = MaterialTheme.typography.titleSmall,
                                color = NuvioTheme.colors.TextPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = NuvioTheme.spacing.xs)
                            )
                            Text(
                                text = stringResource(R.string.addon_pending_collections_replace, pendingChange.proposedCollectionCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                            )
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                        }

                        if (
                            pendingChange.addedUrls.isEmpty() &&
                            pendingChange.removedUrls.isEmpty() &&
                            !pendingChange.catalogsReordered &&
                            pendingChange.disabledCatalogNames.isEmpty() &&
                            pendingChange.enabledCatalogNames.isEmpty() &&
                            !pendingChange.collectionsChanged
                        ) {
                            Text(
                                text = stringResource(R.string.addon_confirm_no_changes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.addon_confirm_total_addons, pendingChange.proposedUrls.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.addon_confirm_total_catalogs, pendingChange.proposedCatalogOrderKeys.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

                if (pendingChange.isApplying) {
                    LoadingIndicator(modifier = Modifier.size(36.dp))
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                    ) {
                        Surface(
                            onClick = onReject,
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = NuvioTheme.colors.Surface,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                    shape = RoundedCornerShape(50)
                                )
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = NuvioTheme.colors.TextPrimary
                                )
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                                Text(
                                    text = stringResource(R.string.addon_confirm_reject),
                                    color = NuvioTheme.colors.TextPrimary
                                )
                            }
                        }

                        Surface(
                            onClick = onConfirm,
                            modifier = Modifier.focusRequester(focusRequester),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = NuvioTheme.colors.Secondary,
                                focusedContainerColor = NuvioTheme.colors.SecondaryVariant
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                    shape = RoundedCornerShape(50)
                                )
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Text(
                                text = stringResource(R.string.addon_confirm_confirm),
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.md),
                                color = NuvioTheme.colors.OnSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AddonCard(
    modifier: Modifier = Modifier,
    addon: Addon,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    isReadOnly: Boolean = false,
    showReorder: Boolean = true,
    toggleFocusRequester: FocusRequester? = null
) {
    if (isReadOnly) {
        Surface(
            onClick = { },
            modifier = modifier
                .fillMaxWidth()
                .animateContentSize(),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            AddonCardContent(addon = addon, isReadOnly = true)
        }
    } else {
        val internalToggleFocusRequester = remember { FocusRequester() }
        val effectiveToggleFocusRequester = toggleFocusRequester ?: internalToggleFocusRequester

        Card(
            modifier = modifier
                .fillMaxWidth()
                .animateContentSize()
                .focusProperties {
                    enter = { effectiveToggleFocusRequester }
                },
            colors = CardDefaults.cardColors(containerColor = NuvioTheme.colors.BackgroundCard),
            shape = RoundedCornerShape(NuvioTheme.radii.md)
        ) {
            AddonCardContent(
                addon = addon,
                isReadOnly = false,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove,
                onEnabledChange = onEnabledChange,
                showReorder = showReorder,
                toggleFocusRequester = effectiveToggleFocusRequester
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddonCardContent(
    addon: Addon,
    isReadOnly: Boolean,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onRemove: () -> Unit = {},
    onEnabledChange: (Boolean) -> Unit = {},
    showReorder: Boolean = true,
    toggleFocusRequester: FocusRequester? = null
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (addon.version.isNotBlank()) {
                        Text(
                            text = "v${addon.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                    if (!addon.enabled) {
                        Text(
                            text = stringResource(R.string.addons_badge_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }
            if (!isReadOnly) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { onEnabledChange(!addon.enabled) },
                        modifier = Modifier
                            .focusRequester(toggleFocusRequester ?: remember { FocusRequester() }),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Switch(
                                checked = addon.enabled,
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NuvioTheme.colors.Secondary,
                                    checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                    if (showReorder) {
                        Button(
                            onClick = onMoveUp,
                            enabled = canMoveUp,
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextSecondary,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                focusedContentColor = NuvioTheme.colors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                        ) {
                            Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.cd_move_up))
                        }
                        Button(
                            onClick = onMoveDown,
                            enabled = canMoveDown,
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextSecondary,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                focusedContentColor = NuvioTheme.colors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                        ) {
                            Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.cd_move_down))
                        }
                    }
                    Button(
                        onClick = onRemove,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Error
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Text(text = stringResource(R.string.addon_remove))
                    }
                }
            }
        }

        if (!addon.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = addon.description ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = addon.baseUrl,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        Text(
            text = stringResource(R.string.addon_catalogs_types, addon.catalogs.size, addon.rawTypes.joinToString()),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
    return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
}
