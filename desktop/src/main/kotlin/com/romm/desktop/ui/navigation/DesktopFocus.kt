package com.romm.desktop.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * Global [CompositionLocal] providing the current [FocusNavigator]. Allows nested screens to
 * access the navigator without threading it through every composable call.
 *
 * Usage:
 * ```
 * val navigator = LocalFocusNavigator.current
 * // use navigator.register / navigator.moveFocus / etc.
 * ```
 *
 * Must be provided by a parent [DesktopFocusScope]. Reading [current] without a provider will
 * throw [IllegalStateException].
 */
val LocalFocusNavigator = compositionLocalOf<FocusNavigator> {
    error("LocalFocusNavigator is not provided. Wrap your screen in DesktopFocusScope().")
}

/**
 * A composable that provides a [FocusNavigator] via [LocalFocusNavigator], so all descendant
 * screens can register focusable items and perform directional navigation without explicit
 * navigator threading.
 *
 * Usage:
 * ```
 * @Composable
 * fun MyScreen(coordinator: DesktopAppCoordinator) {
 *     DesktopFocusScope {
 *         val navigator = LocalFocusNavigator.current
 *         // navigator is available to all children
 *         ...
 *     }
 * }
 * ```
 *
 * By default the navigator is remembered per [DesktopFocusScope] call, so nested scopes get
 * independent navigators. The desktop shell instead passes its own long-lived
 * [FocusNavigator] (created once at the shell level and driven by the controller router), so
 * every screen shares a single navigation authority; items are only registered while their
 * screen is composed.
 *
 * @param navigator The [FocusNavigator] to provide. Defaults to a fresh per-scope instance.
 * @param content The screen content. Must register focusable items with the navigator obtained
 *   via [LocalFocusNavigator.current].
 */
@Composable
fun DesktopFocusScope(
    navigator: FocusNavigator = remember { FocusNavigator() },
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalFocusNavigator provides navigator) {
        content()
    }
}
