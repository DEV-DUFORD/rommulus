package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.LibraryRom
import com.romm.androidtv.library.RomGridViewModel
import com.romm.androidtv.library.SectionState
import com.romm.androidtv.platform.currentDeviceProfile
import kotlinx.coroutines.launch

/**
 * Returns the item directly above or below [currentIndex] in a grid. Keeping this
 * calculation independent of Compose makes vertical D-pad movement deterministic
 * when the destination row has not yet been composed.
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

/**
 * Generic paginated ROM grid, used for both `PlatformDetailScreen` and
 * `CollectionDetailScreen` (UI_REFACTOR.md section 7.2/7.3). A platform or
 * collection can have hundreds of ROMs (confirmed live: 343 for one
 * platform), so the grid loads more automatically as the user scrolls near
 * the end rather than fetching a single fixed-size page.
 */
@Composable
fun RomGridScreen(
    title: String,
    viewModel: RomGridViewModel,
    onOpenGameDetail: (Long) -> Unit,
    restoreFocusRomId: Long? = null,
    onFocusRestored: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val portraitTouchLayout = currentDeviceProfile().usePortraitTouchLayout
    val cardFocusRequesters = remember { mutableMapOf<Long, FocusRequester>() }
    val focusScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi)
            .padding(if (portraitTouchLayout) 16.dp else 24.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, color = RommTvColors.TextPrimary)
        if (uiState.total > 0) {
            Text(
                text = "${uiState.total} games",
                style = MaterialTheme.typography.bodySmall,
                color = RommTvColors.TextSecondary,
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))

        when (val section = uiState.section) {
            is SectionState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RommTvColors.Romm500)
            }
            is SectionState.Error -> Column {
                Text(
                    text = "Couldn't load $title (${section.error.name.lowercase().replace('_', ' ')})",
                    color = RommTvColors.TextSecondary,
                )
                TextButton(onClick = viewModel::refresh, modifier = Modifier.tvButtonFocus()) {
                    Text("Retry", color = RommTvColors.Romm300)
                }
            }
            is SectionState.Loaded -> {
                if (section.data.isEmpty()) {
                    Text(text = "Nothing here yet.", color = RommTvColors.TextSecondary)
                } else {
                    LoadMoreOnScrollEnd(gridState = gridState, itemCount = section.data.size, onLoadMore = viewModel::loadMore)
                    val cardWidth = if (portraitTouchLayout) 112.dp else 136.dp
                    val itemSpacing = if (portraitTouchLayout) 12.dp else 16.dp

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        // This mirrors Adaptive's slot calculation. It gives D-pad Up/Down a
                        // stable row stride even while LazyVerticalGrid composes a new row.
                        val columnCount = maxOf(1, ((maxWidth + itemSpacing) / (cardWidth + itemSpacing)).toInt())

                        LaunchedEffect(restoreFocusRomId, section.data) {
                            val restoreIndex = restoreFocusRomId?.let { id ->
                                section.data.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                            } ?: return@LaunchedEffect
                            gridState.scrollToItem(restoreIndex)
                            // The target item is created by the scroll operation; wait for it
                            // to attach before asking its requester for focus.
                            withFrameNanos { }
                            withFrameNanos { }
                            cardFocusRequesters[section.data[restoreIndex].id]?.requestFocus()
                            onFocusRestored()
                        }

                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = cardWidth),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                            verticalArrangement = Arrangement.spacedBy(itemSpacing),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(section.data, key = { it.id }) { rom: LibraryRom ->
                                val itemIndex = section.data.indexOf(rom)
                                val focusRequester = cardFocusRequesters.getOrPut(rom.id) { FocusRequester() }
                                GameCard(
                                    title = rom.title,
                                    subtitle = rom.platformDisplayName,
                                    coverUrl = rom.coverUrl,
                                    modifier = Modifier
                                        .focusRequester(focusRequester)
                                        .onPreviewKeyEvent { event ->
                                            val moveDown = event.key == Key.DirectionDown
                                            val moveUp = event.key == Key.DirectionUp
                                            if (event.type != KeyEventType.KeyDown || (!moveDown && !moveUp)) {
                                                return@onPreviewKeyEvent false
                                            }
                                            val targetIndex = positionalGridNeighbor(
                                                currentIndex = itemIndex,
                                                columnCount = columnCount,
                                                itemCount = section.data.size,
                                                moveDown = moveDown,
                                            ) ?: return@onPreviewKeyEvent false
                                            focusScope.launch {
                                                if (gridState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
                                                    gridState.scrollToItem(targetIndex)
                                                    withFrameNanos { }
                                                    withFrameNanos { }
                                                }
                                                cardFocusRequesters[section.data[targetIndex].id]?.requestFocus()
                                            }
                                            true
                                        },
                                    onClick = { onOpenGameDetail(rom.id) },
                                )
                            }
                            if (uiState.isLoadingMore) {
                                item {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = RommTvColors.Romm500)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Fires [onLoadMore] once the user has scrolled within a few rows of the end of the grid. */
@Composable
private fun LoadMoreOnScrollEnd(gridState: LazyGridState, itemCount: Int, onLoadMore: () -> Unit) {
    val shouldLoadMore by remember(itemCount) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            itemCount > 0 && lastVisible >= itemCount - 6
        }
    }
    LaunchedEffect(gridState, itemCount) {
        snapshotFlow { shouldLoadMore }.collect { if (it) onLoadMore() }
    }
}
