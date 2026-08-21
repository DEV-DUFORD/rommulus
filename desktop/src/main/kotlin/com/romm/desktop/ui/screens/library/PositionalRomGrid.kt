package com.romm.desktop.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.LibraryRom
import com.romm.desktop.ui.components.GameCard
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.navigation.FocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.positionalGridNeighbor
import kotlinx.coroutines.launch

/** Card min-size / spacing — must match the grid's `Adaptive(minSize)` + arrangements below. */
private val CARD_MIN_SIZE = 136.dp
private val ITEM_SPACING = 16.dp

/**
 * Paginated ROM grid with positional D-pad navigation (Android `positionalGridNeighbor` parity,
 * used by both the Search screen and the platform/collection [RomGridScreen]): Up/Down move to
 * the item DIRECTLY above/below — scrolling first when the destination row is not yet composed —
 * for BOTH input paths:
 *  - keyboard arrows via the grid's preview key handler (Android does the same per card);
 *  - controller D-pad via a [FocusNavigator] grid-navigation hook, since controller movement is
 *    routed through the navigator rather than key events.
 * Left/Right keep the navigator's default traversal; at a grid edge the positional move declines
 * (null) and default handling takes over — mirroring Android's "leaves focus navigation to
 * Compose" fallback.
 *
 * @param cardKeyPrefix Stable per-card focus key prefix (`"search:"` / `"grid:"`) so cards from
 *   different screens never collide in the shared navigator.
 */
@Composable
internal fun PositionalRomGrid(
    navigator: FocusNavigator,
    gridState: LazyGridState,
    roms: List<LibraryRom>,
    cardKeyPrefix: String,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isLoadingMore: Boolean = false,
) {
    val colors = LocalRommulusColors.current
    // Per-card requesters so a positional move can focus the target even when it is not the
    // currently-focused card (Android `cardFocusRequesters` parity).
    val cardFocusRequesters = remember { mutableMapOf<Long, FocusRequester>() }
    val gridScope = rememberCoroutineScope()
    val gridNavOwner = remember { Any() }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Mirrors Adaptive's slot calculation (Android RomGridScreen parity): a stable row stride
        // for positional Up/Down even while the LazyVerticalGrid composes a new row.
        val columnCount = maxOf(1, ((maxWidth + ITEM_SPACING) / (CARD_MIN_SIZE + ITEM_SPACING)).toInt())

        // Positional vertical move: find the focused card's index, step ±columnCount, scroll when
        // the target row is not composed yet, then request focus on the target card. Returns false
        // (declines → default traversal) when no card is focused or the move hits a grid edge.
        fun moveVertically(moveDown: Boolean): Boolean {
            val cardKeys = roms.map { cardKeyPrefix + it.id }
            val focusedIndex = cardKeys.indexOfFirst { navigator.isFocused(it) }
            if (focusedIndex < 0) return false
            val targetIndex = positionalGridNeighbor(focusedIndex, columnCount, roms.size, moveDown)
                ?: return false
            gridScope.launch {
                if (gridState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
                    gridState.scrollToItem(targetIndex)
                    // The target item is created by the scroll; wait for it to attach before
                    // asking its requester for focus (Android parity).
                    withFrameNanos { }
                    withFrameNanos { }
                }
                cardFocusRequesters[roms[targetIndex].id]?.requestFocusSafely()
            }
            return true
        }

        // Controller D-pad: route Up/Down through the positional handler while this grid is
        // composed (re-installed when the row stride or item count changes, e.g. pagination).
        DisposableEffect(navigator, gridNavOwner, columnCount, roms.size) {
            navigator.installGridNavigation(gridNavOwner) { direction ->
                moveVertically(direction == FocusDirection.Down)
            }
            onDispose { navigator.removeGridNavigation(gridNavOwner) }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = CARD_MIN_SIZE),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
            modifier = Modifier
                .fillMaxSize()
                // Keyboard arrows: positional Up/Down while a card holds focus (the grid is an
                // ancestor of its items, so preview events from the focused card arrive here).
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val moveDown = event.key == Key.DirectionDown
                    val moveUp = event.key == Key.DirectionUp
                    if (!moveDown && !moveUp) return@onPreviewKeyEvent false
                    moveVertically(moveDown)
                },
        ) {
            items(roms, key = { it.id }) { rom ->
                GameCard(
                    rom = rom,
                    onClick = { onOpen(rom.id) },
                    modifier = Modifier
                        .focusRequester(cardFocusRequesters.getOrPut(rom.id) { FocusRequester() })
                        .focusableItem(cardKeyPrefix + rom.id, navigator) { onOpen(rom.id) },
                )
            }
            if (isLoadingMore) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.romm500)
                    }
                }
            }
        }
    }
}

/**
 * [FocusRequester.requestFocus], swallowing the `IllegalStateException` Compose throws when no
 * currently-composed node holds this requester (the target row may still be attaching after a
 * scroll). Losing one focus request is harmless; crashing is not.
 */
private fun FocusRequester.requestFocusSafely() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        // Requester not attached to a composed node this frame — nothing to focus, ignore.
    }
}
