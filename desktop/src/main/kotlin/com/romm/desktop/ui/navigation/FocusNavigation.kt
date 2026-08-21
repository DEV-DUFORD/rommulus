package com.romm.desktop.ui.navigation

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

    /** Stable key of the focused item. Registration indices can shift as lazy items change. */
    private var focusedKey: Any? = null

    private var spatialFocusOverrideOwner: Any? = null
    private var spatialFocusOverride: ((FocusDirection) -> Boolean)? = null
    private var backOverride: (() -> Unit)? = null

    private var gridNavigationOwner: Any? = null
    private var gridNavigationHandler: ((FocusDirection) -> Boolean)? = null

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
        val focusedIndex = focusedIndex()
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

    /** Remove registration for [key]. If the focused item is removed, focus is cleared. */
    fun unregister(key: Any) {
        if (key in entries) {
            entries.remove(key)
            if (key == focusedKey) focusedKey = null
        }
    }

    /** Returns all currently registered items in registration order. */
    fun snapshot(): List<RegisteredItem> =
        entries.entries.map { (key, e) -> RegisteredItem(key, e.requester) }

    /** Returns the number of currently registered items. */
    fun size(): Int = entries.size

    /** Returns the focused item's current index, or `-1` if nothing is focused. */
    fun focusedIndex(): Int = focusedKey?.let(::findIndex) ?: -1

    /** Returns whether [key] is the item currently holding controller/keyboard focus. */
    fun isFocused(key: Any): Boolean = focusedKey == key

    /** Returns the stable key currently holding focus, or `null` when nothing is focused. */
    internal fun currentFocusKey(): Any? = focusedKey

    /** Mark the item currently at [index] as focused. */
    internal fun setFocused(index: Int) {
        focusedKey = entries.keys.elementAtOrNull(index)
    }

    /** Mark [key] as focused without relying on its mutable registration index. */
    internal fun setFocusedKey(key: Any) {
        if (key in entries) focusedKey = key
    }

    /** Clear focus ownership only if [key] is still the item recorded as focused. */
    internal fun clearFocusedKey(key: Any) {
        if (focusedKey == key) focusedKey = null
    }

    /** Route spatial movement to a modal's focus owner while that modal is visible. */
    internal fun installSpatialFocusOverride(
        owner: Any,
        moveFocus: (FocusDirection) -> Boolean,
        onBack: () -> Unit,
    ) {
        spatialFocusOverrideOwner = owner
        spatialFocusOverride = moveFocus
        backOverride = onBack
    }

    internal fun removeSpatialFocusOverride(owner: Any) {
        if (spatialFocusOverrideOwner == owner) {
            spatialFocusOverrideOwner = null
            spatialFocusOverride = null
            backOverride = null
        }
    }

    /**
     * Installs a positional vertical-movement handler for a 2D grid (the desktop counterpart of
     * Android's `positionalGridNeighbor` D-pad handling on grid screens): while [owner] is
     * installed, Up/Down movements are delegated to [moveVertical], which should focus the item
     * directly above/below and return `true` when it handled the move. Left/Right keep their
     * normal behavior and Back is unaffected — this is NOT a modal override (a modal's
     * [installSpatialFocusOverride] still wins). Only one grid handler is active at a time: the
     * most recently installed screen wins, and screens remove theirs on dispose.
     */
    internal fun installGridNavigation(owner: Any, moveVertical: (FocusDirection) -> Boolean) {
        gridNavigationOwner = owner
        gridNavigationHandler = moveVertical
    }

    /** Removes the grid navigation handler installed by [owner] (no-op when it is not active). */
    internal fun removeGridNavigation(owner: Any) {
        if (gridNavigationOwner == owner) {
            gridNavigationOwner = null
            gridNavigationHandler = null
        }
    }

    fun moveSpatialFocus(
        direction: FocusDirection,
        fallback: (FocusDirection) -> Boolean,
    ): Boolean {
        // A modal override decides outright (true = consumed; false = it declined, matching the
        // original elvis semantics — no fall-through).
        spatialFocusOverride?.invoke(direction)?.let { return it }
        // A grid handler only consumes a move when it ACCEPTS it (returns true); a decline
        // (grid edge / no card focused) falls through to default traversal.
        if (direction == FocusDirection.Up || direction == FocusDirection.Down) {
            gridNavigationHandler?.let { handler -> if (handler(direction)) return true }
        }
        return fallback(direction)
    }

    /** Invoke a modal's back action, returning false when no modal owns controller focus. */
    fun handleBack(): Boolean {
        val action = backOverride ?: return false
        action()
        return true
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
            focusedKey = entry.key
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
        focusedKey = entry.key
    }

    /** Focus the item registered under [key]. Returns false when it is not currently composed. */
    fun focusItem(key: Any): Boolean {
        val index = indexOf(key)
        if (index < 0) return false
        focusItem(index)
        return true
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
        val key = focusedKey ?: return false
        val action = entries[key]?.onActivate ?: return false
        action()
        return true
    }

    private fun findIndex(key: Any): Int = entries.keys.indexOf(key)
}

/**
 * Returns the item directly above or below [currentIndex] in a grid with [columnCount] columns.
 * Desktop mirror of Android's `positionalGridNeighbor` (RomGridScreen): keeping this calculation
 * independent of Compose makes vertical D-pad movement deterministic when the destination row has
 * not yet been composed. Returns null at a grid edge (or for invalid input) so the caller can
 * fall back to default focus traversal.
 */
internal fun positionalGridNeighbor(
    currentIndex: Int,
    columnCount: Int,
    itemCount: Int,
    moveDown: Boolean,
): Int? {
    if (currentIndex !in 0 until itemCount || columnCount <= 0) return null
    val candidate = currentIndex + if (moveDown) columnCount else -columnCount
    return candidate.takeIf { it in 0 until itemCount }
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
 * A modifier that makes this composable a stop on the [navigator]'s focus path. Visual focus
 * treatment belongs to the host component so its border matches the component's shape.
 *
 * Usage:
 * ```
 * Text(
 *     "Button label",
 *     modifier = Modifier.focusableItem("btn-1", navigator),
 * )
 * ```
 *
 * The host composable must provide the actual focus target (for example, a clickable, Button,
 * Switch, or TextField). This modifier deliberately does not add a duplicate focus target.
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
        .onFocusChanged { state ->
            if (state.isFocused) {
                navigator.setFocusedKey(key)
            } else {
                navigator.clearFocusedKey(key)
            }
        }
}

// Note: FocusableRow / FocusableGrid helpers are intentionally NOT provided as part of this
// foundation wave. The [FocusNavigator] handles directional movement; screen composables lay
// out items in whatever geometry they choose (Row, Column, custom). Adding a row/grid wrapper
// here risks API drift across Compose Desktop versions. Future waves can add them.
