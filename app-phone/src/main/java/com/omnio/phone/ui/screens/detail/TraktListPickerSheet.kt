package com.omnio.phone.ui.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnio.tv.domain.model.LibraryListTab
import com.omnio.tv.domain.model.TraktListPrivacy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraktListPickerSheet(
    state: ListPickerState,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onCreateListClick: () -> Unit,
    onEditorNameChange: (String) -> Unit,
    onEditorDescriptionChange: (String) -> Unit,
    onEditorPrivacyChange: (TraktListPrivacy) -> Unit,
    onEditorCancel: () -> Unit,
    onEditorSubmit: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = "Save to lists",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            if (state.isLoading) {
                LoadingRow()
            } else {
                CreateListRow(
                    enabled = !state.isSaving,
                    onClick = onCreateListClick
                )
                HorizontalDivider()
                if (state.tabs.isEmpty()) {
                    EmptyMessage(text = "No Trakt lists yet. Create one above.")
                } else {
                    ListsBody(
                        tabs = state.tabs,
                        membership = state.membership,
                        enabled = !state.isSaving,
                        onToggle = onToggle
                    )
                }
            }

            state.error?.let { errorText ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !state.isSaving
                ) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onSave,
                    enabled = !state.isSaving && !state.isLoading && state.hasChanges
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save")
                }
            }
        }
    }

    state.editor?.let { editor ->
        ListEditorDialog(
            state = editor,
            onNameChange = onEditorNameChange,
            onDescriptionChange = onEditorDescriptionChange,
            onPrivacyChange = onEditorPrivacyChange,
            onCancel = onEditorCancel,
            onSubmit = onEditorSubmit
        )
    }
}

@Composable
private fun CreateListRow(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Create new list…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ListsBody(
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    enabled: Boolean,
    onToggle: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(tabs, key = { it.key }) { tab ->
            val checked = membership[tab.key] == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onToggle(tab.key) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle(tab.key) },
                    enabled = enabled
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val subtitle = when (tab.type) {
                        LibraryListTab.Type.WATCHLIST -> "Watchlist"
                        LibraryListTab.Type.PERSONAL -> tab.description?.takeIf { it.isNotBlank() }
                            ?: "Personal list"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ListEditorDialog(
    state: ListEditorState,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPrivacyChange: (TraktListPrivacy) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.isSubmitting) onCancel() },
        title = { Text("Create new list") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !state.isSubmitting,
                    isError = state.error != null && state.name.trim().isEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.description,
                    onValueChange = onDescriptionChange,
                    label = { Text("Description (optional)") },
                    enabled = !state.isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Privacy",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TraktListPrivacy.entries.forEach { privacy ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.isSubmitting) { onPrivacyChange(privacy) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.privacy == privacy,
                            onClick = { onPrivacyChange(privacy) },
                            enabled = !state.isSubmitting
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = privacy.displayLabel())
                    }
                }
                state.error?.let { errorText ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = !state.isSubmitting && state.name.trim().isNotEmpty()
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !state.isSubmitting) {
                Text("Cancel")
            }
        }
    )
}

private fun TraktListPrivacy.displayLabel(): String = when (this) {
    TraktListPrivacy.PRIVATE -> "Private"
    TraktListPrivacy.LINK -> "Link only"
    TraktListPrivacy.FRIENDS -> "Friends"
    TraktListPrivacy.PUBLIC -> "Public"
}
