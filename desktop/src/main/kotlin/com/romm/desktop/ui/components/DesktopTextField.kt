package com.romm.desktop.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A simple desktop text field wrapping Material3 [OutlinedTextField].
 *
 * Unlike the Android [ControllerFriendlyTextField], this has NO IME
 * orchestration (no LocalSoftwareKeyboardController, no LocalInputModeManager,
 * no D-pad focus trapping). Desktop users type directly; Enter triggers
 * [onDone] if provided.
 *
 * - Pressing Enter (ImeAction.Done) while the field has focus triggers
 *   [onDone] and moves focus to the next focusable.
 * - [isPassword] applies [PasswordVisualTransformation].
 */
@Composable
fun DesktopTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    onDone: (() -> Unit)? = null,
    singleLine: Boolean = true,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.None,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onDone?.invoke() },
        ),
        placeholder = placeholder?.let { { Text(text = it) } },
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
    )
}
