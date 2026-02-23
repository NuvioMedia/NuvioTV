package com.nuvio.tv.ui.screens.library

import android.view.KeyEvent as AndroidKeyEvent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var expandedPicker by remember { mutableStateOf<String?>(null) }
    val primaryFocusRequester = remember { FocusRequester() }
    var pendingPrimaryFocus by remember { mutableStateOf(true) }

    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            pendingPrimaryFocus = true
        }
    }

    LaunchedEffect(uiState.isLoading, uiState.sourceMode, uiState.listTabs.size) {
        if (!uiState.isLoading && pendingPrimaryFocus) {
            val focused = runCatching { primaryFocusRequester.requestFocus() }.isSuccess
            if (!focused) {
                delay(16)
                runCatching { primaryFocusRequester.requestFocus() }
            }
            pendingPrimaryFocus = false
        }
    }

    if (uiState.isLoading) {
        val loadingFocusRequester = remember { FocusRequester() }
        LaunchedEffect(uiState.isLoading) {
            loadingFocusRequester.requestFocus()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioColors.Background),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(loadingFocusRequester)
                    .focusable()
            )
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LoadingIndicator()
                Text(
                    text = "Syncing Trakt library...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (showBuiltInHeader) NuvioColors.TextPrimary else Color.Transparent,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = if (uiState.sourceMode == LibrarySourceMode.TRAKT) "TRAKT" else "LOCAL",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (showBuiltInHeader) NuvioColors.TextTertiary else Color.Transparent,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp
                )
            }
        }

        item {
            LibrarySelectorsRow(
                sourceMode = uiState.sourceMode,
                listTabs = uiState.listTabs,
                typeTabs = uiState.availableTypeTabs,
                sortOptions = uiState.availableSortOptions,
                selectedListKey = uiState.selectedListKey,
                selectedTypeTab = uiState.selectedTypeTab,
                selectedSortOption = uiState.selectedSortOption,
                primaryFocusRequester = primaryFocusRequester,
                expandedPicker = expandedPicker,
                onExpandedChange = { picker, shouldExpand ->
                    expandedPicker = if (shouldExpand) picker else null
                },
                onSelectList = { key ->
                    viewModel.onSelectListTab(key)
                    expandedPicker = null
                },
                onSelectType = { type ->
                    viewModel.onSelectTypeTab(type)
                    expandedPicker = null
                },
                onSelectSort = { sort ->
                    viewModel.onSelectSortOption(sort)
                    expandedPicker = null
                }
            )
        }

        if (uiState.sourceMode == LibrarySourceMode.TRAKT) {
            item {
                LibraryActionsRow(
                    pending = uiState.pendingOperation,
                    isSyncing = uiState.isSyncing,
                    onManageLists = viewModel::onOpenManageLists,
                    onRefresh = viewModel::onRefresh
                )
            }
        }

        item {
            if (uiState.visibleItems.isEmpty()) {
                val selectedTypeLabel = uiState.selectedTypeTab?.label?.lowercase() ?: "items"
                val title = when (uiState.sourceMode) {
                    LibrarySourceMode.LOCAL -> "No $selectedTypeLabel yet"
                    LibrarySourceMode.TRAKT -> "No $selectedTypeLabel in this list"
                }
                val subtitle = when (uiState.sourceMode) {
                    LibrarySourceMode.LOCAL -> "Start saving your favorites to see them here"
                    LibrarySourceMode.TRAKT -> "Use + in details to add items to watchlist or lists"
                }
                EmptyScreenState(
                    title = title,
                    subtitle = subtitle,
                    icon = Icons.Default.BookmarkBorder
                )
            }
        }

        if (uiState.visibleItems.isNotEmpty()) {
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.visibleItems, key = { "${it.type}:${it.id}" }) { item ->
                        ContentCard(
                            item = item.toMetaPreview(),
                            onClick = {
                                onNavigateToDetail(item.id, item.type, item.addonBaseUrl)
                            }
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }

    if (uiState.showManageDialog && uiState.sourceMode == LibrarySourceMode.TRAKT) {
        ManageListsDialog(
            tabs = uiState.listTabs,
            selectedKey = uiState.manageSelectedListKey,
            errorMessage = uiState.errorMessage,
            pending = uiState.pendingOperation,
            onSelect = viewModel::onSelectManageList,
            onCreate = viewModel::onStartCreateList,
            onEdit = viewModel::onStartEditList,
            onMoveUp = viewModel::onMoveSelectedListUp,
            onMoveDown = viewModel::onMoveSelectedListDown,
            onDelete = { showDeleteConfirm = true },
            onDismiss = viewModel::onCloseManageLists
        )
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            pending = uiState.pendingOperation,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.onDeleteSelectedList()
            },
            onCancel = { showDeleteConfirm = false }
        )
    }

    val listEditor = uiState.listEditorState
    if (listEditor != null && uiState.showManageDialog) {
        ListEditorDialog(
            state = listEditor,
            pending = uiState.pendingOperation,
            onNameChanged = viewModel::onUpdateEditorName,
            onDescriptionChanged = viewModel::onUpdateEditorDescription,
            onPrivacyChanged = viewModel::onUpdateEditorPrivacy,
            onSave = viewModel::onSubmitEditor,
            onCancel = viewModel::onCancelEditor
        )
    }

    val transientMessage = uiState.transientMessage
    if (!transientMessage.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) {
            Text(
                text = transientMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextPrimary,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .background(NuvioColors.BackgroundElevated, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibrarySelectorsRow(
    sourceMode: LibrarySourceMode,
    listTabs: List<LibraryListTab>,
    typeTabs: List<LibraryTypeTab>,
    sortOptions: List<LibrarySortOption>,
    selectedListKey: String?,
    selectedTypeTab: LibraryTypeTab?,
    selectedSortOption: LibrarySortOption,
    primaryFocusRequester: FocusRequester,
    expandedPicker: String?,
    onExpandedChange: (String, Boolean) -> Unit,
    onSelectList: (String) -> Unit,
    onSelectType: (LibraryTypeTab) -> Unit,
    onSelectSort: (LibrarySortOption) -> Unit
) {
    val selectedListLabel = listTabs.firstOrNull { it.key == selectedListKey }?.title ?: "Select"
    val selectedTypeLabel = selectedTypeTab?.label ?: "All"
    val selectedSortLabel = selectedSortOption.label

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (sourceMode == LibrarySourceMode.TRAKT) {
            LibraryDropdownPicker(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 48.dp)
                    .focusRequester(primaryFocusRequester),
                title = "List",
                value = selectedListLabel,
                expanded = expandedPicker == "list",
                options = listTabs.map { LibraryOption(it.title, it.key) },
                onExpandedChange = { onExpandedChange("list", it) },
                onSelect = { onSelectList(it.value) }
            )
        }

        LibraryDropdownPicker(
            modifier = if (sourceMode == LibrarySourceMode.TRAKT) {
                Modifier.weight(1f)
            } else {
                Modifier
                    .width(420.dp)
                    .padding(start = 48.dp, end = 48.dp)
                    .focusRequester(primaryFocusRequester)
            },
            title = "Type",
            value = selectedTypeLabel,
            expanded = expandedPicker == "type",
            options = typeTabs.map { LibraryOption(it.label, it.key) },
            onExpandedChange = { onExpandedChange("type", it) },
            onSelect = { option ->
                typeTabs.firstOrNull { it.key == option.value }?.let(onSelectType)
            }
        )

        if (sourceMode == LibrarySourceMode.TRAKT) {
            LibraryDropdownPicker(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 48.dp),
                title = "Sort",
                value = selectedSortLabel,
                expanded = expandedPicker == "sort",
                options = sortOptions.map { LibraryOption(it.label, it.key) },
                onExpandedChange = { onExpandedChange("sort", it) },
                onSelect = { option ->
                    sortOptions.firstOrNull { it.key == option.value }?.let(onSelectSort)
                }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryDropdownPicker(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    expanded: Boolean,
    options: List<LibraryOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (LibraryOption) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier) {
        Card(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.FocusBackground
            ),
            border = CardDefaults.border(
                border = androidx.tv.material3.Border(
                    border = BorderStroke(1.dp, NuvioColors.Border),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.0f,
                pressedScale = 1.0f
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioColors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                        tint = if (isFocused) NuvioColors.FocusRing else NuvioColors.TextSecondary
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier
                .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                .heightIn(max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NuvioColors.BackgroundCard,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, NuvioColors.Border)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            color = NuvioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onSelect(option) },
                    colors = MenuDefaults.itemColors(
                        textColor = NuvioColors.TextPrimary,
                        disabledTextColor = NuvioColors.TextDisabled
                    )
                )
            }
        }
    }
}

private data class LibraryOption(
    val label: String,
    val value: String
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryActionsRow(
    pending: Boolean,
    isSyncing: Boolean,
    onManageLists: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onManageLists,
            enabled = !pending && !isSyncing,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text("Manage Lists")
        }
        Button(
            onClick = onRefresh,
            enabled = !pending && !isSyncing,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(if (isSyncing) "Syncing..." else "Sync")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageListsDialog(
    tabs: List<LibraryListTab>,
    selectedKey: String?,
    errorMessage: String?,
    pending: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val personalTabs = remember(tabs) { tabs.filter { it.type == LibraryListTab.Type.PERSONAL } }
    val firstFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(personalTabs.size) {
        val target = if (personalTabs.isNotEmpty()) firstFocusRequester else closeFocusRequester
        val focused = runCatching { target.requestFocus() }.isSuccess
        if (!focused) {
            delay(16)
            runCatching { target.requestFocus() }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(620.dp)
                .background(NuvioColors.BackgroundElevated, RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Manage Trakt Lists",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB6B6)
                    )
                }

                if (personalTabs.isEmpty()) {
                    Text(
                        text = "No personal lists yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(personalTabs, key = { it.key }) { tab ->
                            val selected = tab.key == selectedKey
                            Button(
                                onClick = { onSelect(tab.key) },
                                enabled = !pending,
                                modifier = if (tab.key == personalTabs.firstOrNull()?.key) {
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(firstFocusRequester)
                                } else {
                                    Modifier.fillMaxWidth()
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = if (selected) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                                    contentColor = NuvioColors.TextPrimary
                                )
                            ) {
                                Text(
                                    text = tab.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onCreate,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Create") }
                    Button(
                        onClick = onEdit,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Edit") }
                    Button(
                        onClick = onMoveUp,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Move Up") }
                    Button(
                        onClick = onMoveDown,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Move Down") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onDelete,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF4A2323),
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Delete") }
                    Button(
                        onClick = onDismiss,
                        enabled = !pending,
                        modifier = Modifier.focusRequester(closeFocusRequester),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Close") }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ListEditorDialog(
    state: LibraryListEditorState,
    pending: Boolean,
    onNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onPrivacyChanged: (TraktListPrivacy) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val nameFocusRequester = remember { FocusRequester() }
    val descriptionFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var nameEditing by remember { mutableStateOf(false) }
    var descriptionEditing by remember { mutableStateOf(false) }

    fun isSelectKey(keyCode: Int): Boolean {
        return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
    }

    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onCancel,
        title = if (state.mode == LibraryListEditorState.Mode.CREATE) "Create List" else "Edit List",
        width = 560.dp
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = state.name,
            onValueChange = onNameChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        nameEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        nameEditing = true
                        descriptionEditing = false
                        keyboardController?.show()
                    }
                    false
                },
            enabled = !pending,
            readOnly = !nameEditing,
            singleLine = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    nameEditing = false
                    keyboardController?.hide()
                }
            ),
            label = { androidx.compose.material3.Text("Name") },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioColors.TextPrimary,
                unfocusedTextColor = NuvioColors.TextPrimary,
                focusedContainerColor = NuvioColors.BackgroundCard,
                unfocusedContainerColor = NuvioColors.BackgroundCard,
                focusedBorderColor = NuvioColors.FocusRing,
                unfocusedBorderColor = NuvioColors.Border,
                focusedLabelColor = NuvioColors.TextSecondary,
                unfocusedLabelColor = NuvioColors.TextTertiary,
                cursorColor = NuvioColors.FocusRing
            )
        )

        androidx.compose.material3.OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(descriptionFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        descriptionEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        descriptionEditing = true
                        nameEditing = false
                        keyboardController?.show()
                    }
                    false
                },
            enabled = !pending,
            readOnly = !descriptionEditing,
            minLines = 3,
            maxLines = 5,
            label = { androidx.compose.material3.Text("Description") },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioColors.TextPrimary,
                unfocusedTextColor = NuvioColors.TextPrimary,
                focusedContainerColor = NuvioColors.BackgroundCard,
                unfocusedContainerColor = NuvioColors.BackgroundCard,
                focusedBorderColor = NuvioColors.FocusRing,
                unfocusedBorderColor = NuvioColors.Border,
                focusedLabelColor = NuvioColors.TextSecondary,
                unfocusedLabelColor = NuvioColors.TextTertiary,
                cursorColor = NuvioColors.FocusRing
            )
        )

        Text(
            text = "Privacy",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.extendedColors.textSecondary
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(TraktListPrivacy.entries.toList(), key = { it.name }) { privacy ->
                val selected = privacy == state.privacy
                Button(
                    onClick = { onPrivacyChanged(privacy) },
                    enabled = !pending,
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text(privacy.apiValue.replaceFirstChar { it.uppercase() })
                }
            }
        }

        Button(
            onClick = onSave,
            enabled = !pending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(if (pending) "Saving..." else "Save")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmDeleteDialog(
    pending: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    NuvioDialog(
        onDismiss = onCancel,
        title = "Delete this list?",
        subtitle = "This removes the list and all list items from Trakt.",
        width = 420.dp
    ) {
        Button(
            onClick = onConfirm,
            enabled = !pending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF4A2323),
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text("Delete")
        }
    }
}
