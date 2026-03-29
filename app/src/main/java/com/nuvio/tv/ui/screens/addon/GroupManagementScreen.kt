package com.nuvio.tv.ui.screens.addon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.tv.material3.TabRow
import androidx.tv.material3.Tab
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ViewList
import androidx.tv.material3.Icon
import com.nuvio.tv.domain.model.CatalogGroup
import com.nuvio.tv.domain.model.MainGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import android.graphics.Bitmap

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GroupManagementScreen(
    viewModel: GroupManagementViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Subgroups", "Primary Groups")

    var editingSubgroup by remember { mutableStateOf<CatalogGroup?>(null) }
    var creatingSubgroup by remember { mutableStateOf(false) }

    var editingMainGroup by remember { mutableStateOf<MainGroup?>(null) }
    var creatingMainGroup by remember { mutableStateOf(false) }

    when {
        creatingSubgroup || editingSubgroup != null -> {
            SubgroupEditor(
                initialGroup = editingSubgroup,
                uiState = uiState,
                onSave = { name, logo, keys ->
                    viewModel.saveCatalogGroup(editingSubgroup?.id, name, logo, keys)
                    creatingSubgroup = false
                    editingSubgroup = null
                },
                onCancel = {
                    creatingSubgroup = false
                    editingSubgroup = null
                },
                onDelete = {
                    editingSubgroup?.id?.let { viewModel.deleteCatalogGroup(it) }
                    creatingSubgroup = false
                    editingSubgroup = null
                }
            )
        }
        creatingMainGroup || editingMainGroup != null -> {
            MainGroupEditor(
                initialGroup = editingMainGroup,
                uiState = uiState,
                onSave = { name, type, size, keys ->
                    viewModel.saveMainGroup(editingMainGroup?.id, name, type, size, keys)
                    creatingMainGroup = false
                    editingMainGroup = null
                },
                onCancel = {
                    creatingMainGroup = false
                    editingMainGroup = null
                },
                onDelete = {
                    editingMainGroup?.id?.let { viewModel.deleteMainGroup(it) }
                    creatingMainGroup = false
                    editingMainGroup = null
                }
            )
        }
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NuvioColors.Background)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 36.dp, end = 36.dp, top = 28.dp)
                    ) {
                        Text(
                            text = "Group Management",
                            style = MaterialTheme.typography.headlineMedium,
                            color = NuvioColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = index == selectedTabIndex,
                                    onFocus = { selectedTabIndex = index },
                                    onClick = { selectedTabIndex = index }
                                ) {
                                    Text(
                                        text = title,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Content
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 36.dp)
                    ) {
                        if (selectedTabIndex == 0) {
                            SubgroupsContent(
                                uiState = uiState,
                                onCreateClick = { creatingSubgroup = true },
                                onEditClick = { editingSubgroup = it },
                                onManageFromPhone = { viewModel.startQrMode() }
                            )
                        } else {
                            PrimaryGroupsContent(
                                uiState = uiState,
                                onCreateClick = { creatingMainGroup = true },
                                onEditClick = { editingMainGroup = it }
                            )
                        }
                    }
                }

                // QR Code overlay
                if (uiState.isQrModeActive) {
                    Popup(properties = PopupProperties(focusable = true)) {
                        GroupQrCodeOverlay(
                            qrBitmap = uiState.qrCodeBitmap,
                            serverUrl = uiState.serverUrl,
                            onClose = viewModel::stopQrMode,
                            hasPendingChange = uiState.pendingChange != null
                        )
                    }
                }

                // Confirmation dialog overlay
                if (uiState.pendingChange != null) {
                    Popup(properties = PopupProperties(focusable = true)) {
                        uiState.pendingChange?.let { pending ->
                            GroupConfirmChangesDialog(
                                onConfirm = { viewModel.confirmPendingChange() },
                                onReject = { viewModel.rejectPendingChange() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubgroupsContent(
    uiState: GroupManagementUiState,
    onCreateClick: () -> Unit,
    onEditClick: (CatalogGroup) -> Unit,
    onManageFromPhone: () -> Unit = {}
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CreateButton(text = "Create Subgroup", onClick = onCreateClick)
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            GroupManageFromPhoneCard(onClick = onManageFromPhone)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (uiState.catalogGroups.isEmpty()) {
            item {
                Text(
                    text = "No subgroups configured. Create a subgroup to combine catalogs.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            items(uiState.catalogGroups, key = { it.id }) { group ->
                GroupCard(
                    title = group.name,
                    subtitle = "${group.catalogKeys.size} catalogs",
                    icon = Icons.Default.Folder,
                    onClick = { onEditClick(group) }
                )
            }
        }
    }
}

@Composable
private fun PrimaryGroupsContent(
    uiState: GroupManagementUiState,
    onCreateClick: () -> Unit,
    onEditClick: (MainGroup) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CreateButton(text = "Create Primary Group", onClick = onCreateClick)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (uiState.mainGroups.isEmpty()) {
            item {
                Text(
                    text = "No primary groups configured. Create a primary group to appear on the home screen.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            items(uiState.mainGroups, key = { it.id }) { main ->
                GroupCard(
                    title = main.name,
                    subtitle = "${main.subGroupIds.size} subgroups nested",
                    icon = Icons.Default.ViewList,
                    onClick = { onEditClick(main) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CreateButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = NuvioColors.Surface,
            contentColor = NuvioColors.TextPrimary,
            focusedContainerColor = NuvioColors.FocusBackground,
            focusedContentColor = NuvioColors.Primary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(text = text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
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
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isFocused) NuvioColors.Secondary else NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioColors.TextPrimary
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SubgroupEditor(
    initialGroup: CatalogGroup?,
    uiState: GroupManagementUiState,
    onSave: (name: String, logo: String?, keys: List<String>) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(initialGroup?.name ?: "") }
    var logoUrl by remember { mutableStateOf(initialGroup?.logoUrl ?: "") }
    var selectedKeys by remember { mutableStateOf(initialGroup?.catalogKeys?.toSet() ?: emptySet()) }
    var isEditingName by remember { mutableStateOf(false) }
    var isEditingLogo by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val textFieldFocusRequester = remember { FocusRequester() }
    val logoFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isEditingName, isEditingLogo) {
        if (isEditingName) {
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        } else if (isEditingLogo) {
            logoFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    BackHandler { onCancel() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(36.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (initialGroup == null) "Create Subgroup" else "Edit Subgroup",
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioColors.TextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { onSave(name, logoUrl.takeIf { it.isNotBlank() }, selectedKeys.toList()) },
                        enabled = name.isNotBlank() && selectedKeys.isNotEmpty()
                    ) {
                        Text("Save")
                    }
                    Button(onClick = onCancel) {
                        Text("Cancel")
                    }
                    if (initialGroup != null) {
                        Button(onClick = onDelete, colors = ButtonDefaults.colors(containerColor = NuvioColors.Error)) {
                            Text("Delete")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            // Name Input
            Surface(
                onClick = { isEditingName = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(border = BorderStroke(2.dp, NuvioColors.FocusRing), shape = RoundedCornerShape(12.dp))
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Box(modifier = Modifier.padding(16.dp).clipToBounds()) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(textFieldFocusRequester)
                            .onFocusChanged {
                                if (!it.isFocused && isEditingName) {
                                    isEditingName = false
                                    keyboardController?.hide()
                                }
                            },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                isEditingName = false
                                isEditingLogo = true
                            }
                        ),
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = NuvioColors.TextPrimary),
                        cursorBrush = SolidColor(if (isEditingName) NuvioColors.Primary else Color.Transparent),
                        decorationBox = { innerTextField ->
                            if (name.isEmpty()) {
                                Text("Enter Subgroup Name", color = NuvioColors.TextTertiary)
                            }
                            innerTextField()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Logo URL Input
            Surface(
                onClick = { isEditingLogo = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(border = BorderStroke(2.dp, NuvioColors.FocusRing), shape = RoundedCornerShape(12.dp))
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Box(modifier = Modifier.padding(16.dp).clipToBounds()) {
                    BasicTextField(
                        value = logoUrl,
                        onValueChange = { logoUrl = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(logoFieldFocusRequester)
                            .onFocusChanged {
                                if (!it.isFocused && isEditingLogo) {
                                    isEditingLogo = false
                                    keyboardController?.hide()
                                }
                            },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Uri),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                isEditingLogo = false
                                keyboardController?.hide()
                            }
                        ),
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = NuvioColors.TextPrimary),
                        cursorBrush = SolidColor(if (isEditingLogo) NuvioColors.Primary else Color.Transparent),
                        decorationBox = { innerTextField ->
                            if (logoUrl.isEmpty()) {
                                Text("Direct Image Link (.jpg/.png)", color = NuvioColors.TextTertiary)
                            }
                            innerTextField()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Select Catalogs",
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.availableCatalogs, key = { it.key }) { catalog ->
                    val isSelected = selectedKeys.contains(catalog.key)
                    Surface(
                        onClick = {
                            selectedKeys = if (isSelected) selectedKeys - catalog.key else selectedKeys + catalog.key
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = NuvioColors.BackgroundElevated,
                            focusedContainerColor = NuvioColors.FocusBackground
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Add,
                                contentDescription = null,
                                tint = if (isSelected) NuvioColors.Success else NuvioColors.TextSecondary,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                            Column {
                                Text(text = catalog.catalogName, color = NuvioColors.TextPrimary)
                                Text(text = "${catalog.addonName} • ${catalog.typeLabel}", color = NuvioColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MainGroupEditor(
    initialGroup: MainGroup?,
    uiState: GroupManagementUiState,
    onSave: (name: String, type: String, size: String, keys: List<String>) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(initialGroup?.name ?: "") }
    var selectedSubGroups by remember { mutableStateOf(initialGroup?.subGroupIds?.toSet() ?: emptySet()) }
    var isEditingName by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val textFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isEditingName) {
        if (isEditingName) {
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    BackHandler { onCancel() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(36.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (initialGroup == null) "Create Primary Group" else "Edit Primary Group",
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioColors.TextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { onSave(name, "Square", "Default", selectedSubGroups.toList()) },
                        enabled = name.isNotBlank() && selectedSubGroups.isNotEmpty()
                    ) {
                        Text("Save")
                    }
                    Button(onClick = onCancel) {
                        Text("Cancel")
                    }
                    if (initialGroup != null) {
                        Button(onClick = onDelete, colors = ButtonDefaults.colors(containerColor = NuvioColors.Error)) {
                            Text("Delete")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            // Name Input
            Surface(
                onClick = { isEditingName = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(border = BorderStroke(2.dp, NuvioColors.FocusRing), shape = RoundedCornerShape(12.dp))
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Box(modifier = Modifier.padding(16.dp).clipToBounds()) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(textFieldFocusRequester)
                            .onFocusChanged {
                                if (!it.isFocused && isEditingName) {
                                    isEditingName = false
                                    keyboardController?.hide()
                                }
                            },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                isEditingName = false
                                keyboardController?.hide()
                            }
                        ),
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = NuvioColors.TextPrimary),
                        cursorBrush = SolidColor(if (isEditingName) NuvioColors.Primary else Color.Transparent),
                        decorationBox = { innerTextField ->
                            if (name.isEmpty()) {
                                Text("Enter Primary Group Name", color = NuvioColors.TextTertiary)
                            }
                            innerTextField()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Select Subgroups",
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.catalogGroups, key = { it.id }) { group ->
                    val isSelected = selectedSubGroups.contains(group.id)
                    Surface(
                        onClick = {
                            selectedSubGroups = if (isSelected) selectedSubGroups - group.id else selectedSubGroups + group.id
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = NuvioColors.BackgroundElevated,
                            focusedContainerColor = NuvioColors.FocusBackground
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Add,
                                contentDescription = null,
                                tint = if (isSelected) NuvioColors.Success else NuvioColors.TextSecondary,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                            Column {
                                Text(text = group.name, color = NuvioColors.TextPrimary)
                                Text(text = "${group.catalogKeys.size} catalogs", color = NuvioColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupManageFromPhoneCard(onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
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
                    tint = if (isFocused) NuvioColors.Secondary else NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Manage from phone",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NuvioColors.TextPrimary
                    )
                    Text(
                        text = "Scan QR to arrange groups on your mobile device",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioColors.TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupQrCodeOverlay(
    qrBitmap: Bitmap?,
    serverUrl: String?,
    onClose: () -> Unit,
    hasPendingChange: Boolean = false
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
                text = "Scan with your phone's camera\nMake sure your TV and phone are on the same WiFi network.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(220.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (serverUrl != null) {
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextTertiary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                onClick = onClose,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuvioColors.Surface,
                    focusedContainerColor = NuvioColors.FocusBackground
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(50)
                    )
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = NuvioColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Close",
                        color = NuvioColors.TextPrimary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupConfirmChangesDialog(
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

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
            modifier = Modifier.width(360.dp),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioColors.SurfaceVariant
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Confirm Changes",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Your phone wants to save new group configuration. Apply these changes?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = onReject,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.Surface,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.focusRequester(focusRequester),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.Primary,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}
