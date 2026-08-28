package com.romm.desktop.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * A [Modifier.onPreviewKeyEvent] handler that maps desktop keyboard shortcuts to callbacks.
 *
 * Maps:
 *  - [Key.Escape] → [onBack] (navigates back / closes modals, per plans/LINUX_X64.md §8.1)
 *  - Ctrl+[Key.F] → [onSearch] (opens the search screen)
 *  - Ctrl+[Key.Q] → [onQuit] (requests application shutdown)
 *
 * Enter and Space are handled implicitly by the focus system: the focused item is activated
 * when the user presses Enter or Space, via the standard Compose `onClick` flow — no explicit
 * mapping is needed here.
 *
 * When [enabled] is `false` (default `true`), this modifier is a no-op. Screens that want
 * to opt out of global shortcuts (e.g., text-input fields) can use a `Modifier` with
 * `enabled = false` or simply not include this modifier.
 *
 * Usage:
 * ```
 * Text(
 *     "My screen",
 *     modifier = Modifier.keyboardShortcuts(
 *         onBack = { coordinator.onBack() },
 *         onSearch = { coordinator.navigate(Screen.SEARCH) },
 *         onQuit = { onCloseRequest() },
 *     ),
 * )
 * ```
 *
 * @param onBack Invoked on Escape press. Typically calls [DesktopAppCoordinator.onBack].
 * @param onSearch Invoked on Ctrl+F. Typically navigates to the search screen.
 * @param onQuit Invoked on Ctrl+Q. Typically calls the window close handler.
 * @param enabled When `false`, the modifier is a no-op (default: `true`).
 */
fun Modifier.keyboardShortcuts(
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onQuit: () -> Unit,
    enabled: Boolean = true,
): Modifier = if (enabled) {
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            event.key == Key.Escape -> {
                onBack()
                true
            }
            event.isCtrlPressed && event.key == Key.F -> {
                onSearch()
                true
            }
            event.isCtrlPressed && event.key == Key.Q -> {
                onQuit()
                true
            }
            else -> false
        }
    }
} else {
    this
}
