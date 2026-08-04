package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.LibraryRom
import com.romm.androidtv.library.RomGridViewModel
import com.romm.androidtv.library.SectionState

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
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi)
            .padding(24.dp),
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
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 136.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        // weight(1f) bounds the grid to the remaining Column height so it owns
                        // its own scrolling — without it the grid isn't height-constrained and
                        // the last row can end up partially or fully unreachable by scrolling.
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        items(section.data, key = { it.id }) { rom: LibraryRom ->
                            GameCard(
                                title = rom.title,
                                subtitle = rom.platformDisplayName,
                                coverUrl = rom.coverUrl,
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
