package com.nuvio.tv.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.nuvio.tv.ui.components.input.NuvioTextInput

/** Compatibility wrapper for existing account forms. */
@Composable
internal fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    NuvioTextInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        keyboardType = keyboardType,
        isPassword = isPassword,
        imeAction = imeAction,
        onImeAction = onImeAction,
        modifier = modifier
    )
}
