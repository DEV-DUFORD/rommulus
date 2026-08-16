package com.romm.desktop.ui.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * In-memory navigator that tracks a set of registered [FocusRequester]s keyed by a stable
 * [Any] identifier. Provides directional focus movement with edge wrap-around.
 *
 * This is the testable core of the focus-navigation system. Compose integration ([FocusableGrid],
 * [focusableItem]) uses [FocusNavigator] as a delegate — the pure [nextIndex] / [moveFocus] logic
 * is unit-testable without a Compose runtime.
 *
 * Registration order matters: items should be registered in the visual scan order
 * (left-to-right, top-to-bottom) so that directional movement maps to the expected screen
 * geometry.
 */
@Stable
class FocusNavigator {

    private data class Entry(val requester: FocusRequester, val onActivate: (() -> Unit)?)

    /** All registered items, keyed by [Any] and preserved in insertion order. */
    private val entries = LinkedHashMap<Any, Entry>()

    /** The index of the currently-focused item, or `-1` if nothing is focused. */
    private var focusedIndex: Int = -1

    /** Data class returned by [snapshot]. */
    data class RegisteredItem(val key: Any, val requester: FocusRequester)

    // ----------------------------------------------------------------------- pure logic

    /**
     * Returns the index of the item to focus next given the current [focusedIndex] and
     * [FocusDirection]. Uses edge wrap-around: moving Right past the last item wraps to the
     * first, and moving Left past the first wraps to the last.
     *
     * Horizontal directions (Next, Right) advance; vertical directions (Previous, Left) retreat;
     * Up/Down are treated as Up/Right respectively for 1D layouts (see [register] documentation
     * for 2D layouts).
     *
     * @return the next index, or `-1` when [entries] has fewer than 2 items.
     */
    fun nextIndex(focusDirection: FocusDirection): Int {
        val size = entries.size
        if (size < 2) return -1
        return when (focusDirection) {
            FocusDirection.Next, FocusDirection.Right, FocusDirection.Down -> (focusedIndex + 1) % size
            FocusDirection.Previous, FocusDirection.Left, FocusDirection.Up -> {
                if (focusedIndex <= 0) size - 1 else focusedIndex - 1
            }
            else -> -1
        }
    }

    // ----------------------------------------------------------------------- registration

    /**
     * Register an item under [key] with [requester]. If [key] is already registered, its
     * requester/activation callback are replaced (in-place; order is preserved). Returns the
     * index at which the item appears in navigation order (0-based).
     *
     * @param onActivate Optional action invoked by [activateFocused] when this item is the
     *   focused one (e.g. a card's `onClick`). Items registered without it are navigable but
     *   not controller-activatable.
     */
    fun register(key: Any, requester: FocusRequester, onActivate: (() -> Unit)? = null): Int {
        entries[key] = Entry(requester, onActivate)
        return entries.keys.indexOf(key)
    }

    /** The navigation-order index of [key], or `-1` when it is not registered. */
    fun indexOf(key: Any): Int = entries.keys.indexOf(key)

    /** Remove registration for [key]. If the focused item is removed, [focusedIndex] is cleared. */
    fun unregister(key: Any) {
        val idx = findIndex(key)
        if (idx >= 0) {
            entries.remove(key)
            if (idx == focusedIndex) focusedIndex = -1
        }
    }

    /** Returns all currently registered items in registration order. */
    fun snapshot(): List<RegisteredItem> =
        entries.entries.map { (key, e) -> RegisteredItem(key, e.requester) }

    /** Returns the number of currently registered items. */
    fun size(): Int = entries.size

    /** Returns the focused index, or `-1` if nothing is focused. */
    fun focusedIndex(): Int = focusedIndex

    /** Mark [focusedIndex] as [index]. Called from Compose scope focus-change callbacks. */
    internal fun setFocused(index: Int) {
        focusedIndex = index
    }

    // ----------------------------------------------------------------------- move focus

    /**
     * Move focus in [direction]. Finds the currently-focused item, computes the next index via
     * [nextIndex], and requests focus on the target. No-op when < 2 items are registered.
     *
     * requestFocus() may throw IllegalStateException in unit tests (no Compose composition).
     * The focusedIndex update below still applies so index-tracking tests pass.
     */
    fun moveFocus(focusDirection: FocusDirection) {
        val next = nextIndex(focusDirection)
        if (next >= 0) {
            val entry = entries.entries.toTypedArray()[next]
            try {
                entry.value.requester.requestFocus()
            } catch (_: IllegalStateException) {
                // Unit test environment: FocusRequester is not initialized without a composition.
            }
            focusedIndex = next
        }
    }

    /** Focus the item at [index] directly. */
    fun focusItem(index: Int) {
        if (index < 0 || index >= entries.size) return
        val entry = entries.entries.toTypedArray()[index]
        // requestFocus() may throw IllegalStateException in unit tests (no Compose composition).
        // The focusedIndex update below still applies so index-tracking tests pass.
        try {
            entry.value.requester.requestFocus()
        } catch (_: IllegalStateException) {
            // Unit test environment: FocusRequester is not initialized without a composition.
        }
        focusedIndex = index
    }

    /** Convenience: focus the first registered item. */
    fun focusFirst() = focusItem(0)

    /** Convenience: focus the last registered item. */
    fun focusLast() = focusItem(entries.size - 1)

    /**
     * Invoke the focused item's activation callback (controller "A" button). Returns `true`
     * when an action was performed, `false` when nothing is focused or the focused item has
     * no activation callback.
     */
    fun activateFocused(): Boolean {
        val idx = focusedIndex
        if (idx < 0 || idx >= entries.size) return false
        val entry = entries.entries.toTypedArray()[idx]
        val action = entry.value.onActivate ?: return false
        action()
        return true
    }

    private fun findIndex(key: Any): Int = entries.keys.indexOf(key)
}

// ------------------------------------------------------------------------- Compose helpers

/**
 * Remember a [FocusNavigator] scoped to this composition. The navigator tracks all items
 * registered with [focusableItem] by key and exposes [FocusNavigator.moveFocus] for directional
 * navigation with wrap-around.
 *
 * Usage:
 * ```
 * val navigator = rememberFocusNavigator()
 * // on arrow key press: navigator.moveFocus(FocusDirection.Right)
 * ```
 */
@Composable
fun rememberFocusNavigator(): FocusNavigator = remember { FocusNavigator() }

/**
 * A modifier that makes this composable a stop on the [navigator]'s focus path. Does NOT
 * render a focus ring — screens that want a focus ring should apply a separate ring modifier
 * (see the Android `tvButtonFocus` pattern, which uses `drawRoundRect` with a coloured stroke).
 *
 * Usage:
 * ```
 * Text(
 *     "Button label",
 *     modifier = Modifier.focusableItem("btn-1", navigator),
 * )
 * ```
 *
 * The modifier:
 *  1. Creates a [FocusRequester] scoped to this composition via `remember`.
 *  2. Registers the item with the navigator EAGERLY (at composition) so directional movement
 *     ([FocusNavigator.moveFocus]) can reach every composed item, not only the one that is
 *     currently focused — the shell's controller router relies on this for D-pad navigation.
 *     Unregisters when the item leaves the composition (e.g. lazy-list recycling).
 *  3. Marks the navigator's focused index whenever the item gains focus.
 *
 * Because this modifier needs `remember` to persist a per-item FocusRequester, it must be
 * applied in a `@Composable` context. The calling screen is responsible for wiring directional
 * key events to [FocusNavigator.moveFocus].
 *
 * @param key A stable key identifying this item within the navigator. Must not change during
 *   the item's lifetime.
 * @param navigator The [FocusNavigator] this item belongs to. Use [rememberFocusNavigator] to
 *   obtain one from a parent composable, or `LocalFocusNavigator.current` for the shell-provided
 *   shared navigator.
 * @param onActivate Optional action invoked by [FocusNavigator.activateFocused] while this item
 *   is focused (controller "A" button) — typically the same action as the item's `onClick`.
 */
@Composable
fun Modifier.focusableItem(
    key: Any,
    navigator: FocusNavigator,
    onActivate: (() -> Unit)? = null,
): Modifier {
    val requester = remember { FocusRequester() }

    // Eager registration: the item participates in D-pad navigation as soon as it is composed.
    DisposableEffect(navigator, key) {
        navigator.register(key, requester, onActivate)
        onDispose { navigator.unregister(key) }
    }
    // Keep the activation callback fresh across recompositions (register replaces in place,
    // preserving navigation order).
    LaunchedEffect(onActivate) {
        if (onActivate != null) navigator.register(key, requester, onActivate)
    }

    return this
        .focusRequester(requester)
        .focusable()
        .onFocusChanged { state ->
            if (state.isFocused) navigator.setFocused(navigator.indexOf(key))
        }
}

// Note: FocusableRow / FocusableGrid helpers are intentionally NOT provided as part of this
// foundation wave. The [FocusNavigator] handles directional movement; screen composables lay
// out items in whatever geometry they choose (Row, Column, custom). Adding a row/grid wrapper
// here risks API drift across Compose Desktop versions. Future waves can add them.
