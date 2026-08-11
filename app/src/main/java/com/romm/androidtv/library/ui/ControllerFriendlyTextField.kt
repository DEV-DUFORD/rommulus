package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A [TextField] wrapper that's actually usable with a TV remote.
 *
 * Plain [TextField]s are unworkable on Android TV: the instant a real, editable
 * [TextField] gains Android focus — whether via D-pad navigation or a screen's initial
 * `FocusRequester.requestFocus()` — the platform shows the on-screen keyboard, which
 * then steals every subsequent D-pad Up/Down press for its own on-screen key
 * navigation. The only escape is Back, which most users don't expect to need just to
 * keep scrolling past a field.
 *
 * The fix here is structural, not reactive: the actual editable [TextField] is kept
 * entirely unfocusable (`focusProperties { canFocus = isEditing }`) until the user
 * explicitly opts in, so it's never the thing D-pad navigation or initial-focus lands
 * on — an outer, focusable [Box] is. This avoids any race between "focus gained" and
 * "hide the keyboard we didn't want" (which previously caused a brief keyboard flash):
 * the IME-triggering field is simply never focused until we want it to be.
 *
 * - The wrapping [Box] receives D-pad focus and shows a highlighted border while
 *   focused-but-not-editing — this is what callers should attach their
 *   [Modifier.focusRequester] to via [modifier].
 * - Pressing DPAD_CENTER/Enter while the box is focused hands focus to the real
 *   [TextField] and shows the keyboard — this is the only path that opens it.
 * - Back while editing hides the keyboard, exits editing, and returns focus to the
 *   wrapping box, consuming the key so it doesn't also pop the screen/activity back
 *   stack.
 * - For [singleLine] fields (the only kind used in this app), Up/Down while editing
 *   always exits editing, hides the keyboard, and moves focus to the next/previous
 *   focusable — up/down never means anything for cursor movement in a single line.
 */
@Composable
fun ControllerFriendlyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    touchEditEnabled: Boolean = false,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    colors: TextFieldColors? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val boxFocusRequester = remember { FocusRequester() }
    val fieldFocusRequester = remember { FocusRequester() }
    var isEditing by remember { mutableStateOf(false) }
    var isBoxFocused by remember { mutableStateOf(false) }

    // The only place the keyboard is ever explicitly shown: right after the user opts
    // into editing. Focus is requested here (rather than as a side effect of D-pad
    // navigation) so the two always happen together, deliberately, with no flash.
    LaunchedEffect(isEditing) {
        if (isEditing) {
            fieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Box(
        modifier = modifier
            .then(
                if (isBoxFocused && !isEditing) {
                    Modifier.border(2.dp, RommTvColors.Romm500, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                },
            )
            .focusRequester(boxFocusRequester)
            // onFocusChanged must precede focusable() in the chain — it only observes
            // focus-state changes of focus targets that come after it (closer to the
            // leaf), so it has to sit above/outside the actual focusable() node here.
            .onFocusChanged { state -> isBoxFocused = state.isFocused }
            .focusable(enabled = !isEditing)
            .onPreviewKeyEvent { event ->
                if (isEditing || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                    isEditing = true
                    true
                } else {
                    false
                }
            },
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            isError = isError,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            colors = colors ?: TextFieldDefaults.colors(),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocusRequester)
                .focusProperties {
                    canFocus = isEditing ||
                        (touchEditEnabled && inputModeManager.inputMode == InputMode.Touch)
                }
                .onFocusChanged { state ->
                    if (
                        state.isFocused &&
                        touchEditEnabled &&
                        inputModeManager.inputMode == InputMode.Touch
                    ) {
                        isEditing = true
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (!isEditing || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Back -> {
                            isEditing = false
                            keyboardController?.hide()
                            boxFocusRequester.requestFocus()
                            true
                        }
                        singleLine && (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
                            isEditing = false
                            keyboardController?.hide()
                            focusManager.moveFocus(
                                if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down,
                            )
                            true
                        }
                        else -> false
                    }
                },
        )
    }
}
