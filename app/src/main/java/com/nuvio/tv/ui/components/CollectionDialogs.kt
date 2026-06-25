package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.FolderViewMode
import com.nuvio.tv.ui.theme.NuvioTheme
import android.view.KeyEvent as AndroidKeyEvent

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionPickerDialog(
    collections: List<Collection>,
    onCreateNewClick: () -> Unit,
    onCollectionClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.cast_detail_dialog_choose_collection),
        subtitle = stringResource(R.string.cast_detail_dialog_choose_collection_subtitle)
    ) {
        Button(
            onClick = onCreateNewClick,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.cast_detail_dialog_create_new))
        }

        if (collections.isNotEmpty()) {
            val listState = rememberLazyListState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    items(collections) { collection ->
                        Button(
                            onClick = { onCollectionClick(collection.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextPrimary
                            )
                        ) {
                            Text(collection.title)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CollectionNameInputDialog(
    initialValue: String,
    onConfirm: (String, FolderViewMode) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialValue) }
    var viewMode by remember { mutableStateOf(FolderViewMode.TABBED_GRID) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isEditing by remember { mutableStateOf(false) }

    fun isSelectKey(keyCode: Int): Boolean {
        return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.collection_dialog_create_title),
        subtitle = stringResource(R.string.collection_dialog_create_subtitle),
        width = 460.dp
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        isEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        isEditing = true
                        keyboardController?.show()
                    }
                    false
                },
            readOnly = !isEditing,
            singleLine = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    isEditing = false
                    keyboardController?.hide()
                }
            ),
            label = { androidx.compose.material3.Text(stringResource(R.string.collection_dialog_name_label)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioTheme.colors.TextPrimary,
                unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                focusedBorderColor = NuvioTheme.colors.FocusRing,
                unfocusedBorderColor = NuvioTheme.colors.Border,
                focusedLabelColor = NuvioTheme.colors.TextSecondary,
                unfocusedLabelColor = NuvioTheme.colors.TextTertiary,
                cursorColor = NuvioTheme.colors.FocusRing
            )
        )

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        androidx.compose.material3.Text(
            text = stringResource(R.string.collection_dialog_format_label),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = NuvioTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            val gridShape = RoundedCornerShape(NuvioTheme.radii.sm)
            val rowsShape = RoundedCornerShape(NuvioTheme.radii.sm)

            Card(
                onClick = { viewMode = FolderViewMode.TABBED_GRID },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = CardDefaults.shape(shape = gridShape),
                colors = CardDefaults.colors(
                    containerColor = if (viewMode == FolderViewMode.TABBED_GRID) NuvioTheme.colors.FocusRing.copy(alpha = 0.15f) else NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.BackgroundCard
                ),
                border = CardDefaults.border(
                    border = Border(border = BorderStroke(1.dp, if (viewMode == FolderViewMode.TABBED_GRID) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border), shape = gridShape),
                    focusedBorder = Border(border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing), shape = gridShape)
                )
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.collection_dialog_format_grid), style = MaterialTheme.typography.labelMedium)
                }
            }

            Card(
                onClick = { viewMode = FolderViewMode.ROWS },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = CardDefaults.shape(shape = rowsShape),
                colors = CardDefaults.colors(
                    containerColor = if (viewMode == FolderViewMode.ROWS) NuvioTheme.colors.FocusRing.copy(alpha = 0.15f) else NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.BackgroundCard
                ),
                border = CardDefaults.border(
                    border = Border(border = BorderStroke(1.dp, if (viewMode == FolderViewMode.ROWS) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border), shape = rowsShape),
                    focusedBorder = Border(border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing), shape = rowsShape)
                )
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.collection_dialog_format_rows), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Button(
            onClick = {
                if (name.isNotBlank()) {
                    onConfirm(name, viewMode)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(android.R.string.ok))
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(android.R.string.cancel))
        }
    }
}
