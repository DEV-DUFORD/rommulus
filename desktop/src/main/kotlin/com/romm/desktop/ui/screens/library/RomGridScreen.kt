package com.romm.desktop.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
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
import com.romm.androidtv.library.RomQuery
import com.romm.androidtv.library.SectionState
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.Screen
import com.romm.desktop.ui.components.GameCard
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.LoadingIndicator
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts

/**
 * Desktop paginated ROM grid (Phase 6), used for both platform detail and
 * collection detail. A platform or collection can have hundreds of ROMs, so
 * the grid loads more automatically as the user scrolls near the end
 * ([LoadMoreOnScrollEnd] → the pure, unit-tested [shouldLoadMoreOnScrollEnd]
 * predicate → [com.romm.androidtv.library.RomGridPresenter.loadMore]).
 *
 * Drives the shared [com.romm.androidtv.library.RomGridPresenter] obtained via
 * `coordinator.romGridPresenter(query)`, remembered per [query] so a new
 * presenter (and first-page fetch) is created only when the query changes.
 */
@Composable
fun RomGridScreen(
    coordinator: DesktopAppCoordinator,
    title: String,
    query: RomQuery,
    modifier: Modifier = Modifier,
) {
    val presenter = remember(query) { coordinator.romGridPresenter(query) }
    val uiState by presenter.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val colors = LocalRommulusColors.current
    val parent = gridParentScreen(query)
    // Shell-provided shared navigator (controller D-pad / left-stick navigation).
    val navigator = LocalFocusNavigator.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.nightHi)
            .keyboardShortcuts(
                onBack = { coordinator.onBack() },
                onSearch = { coordinator.navigate(Screen.SEARCH) },
                onQuit = { /* window close is owned by the desktop shell */ },
            )
            .padding(24.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
        if (uiState.total > 0) {
            Text(
                text = "${uiState.total} games",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        when (val section = uiState.section) {
            is SectionState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator()
            }

            is SectionState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Couldn't load $title (${errorMessage(section.error)})",
                    color = colors.textSecondary,
                )
                RetryButton(onRetry = presenter::refresh)
            }

            is SectionState.Loaded -> {
                if (section.data.isEmpty()) {
                    Text(text = "Nothing here yet.", color = colors.textSecondary)
                } else {
                    LoadMoreOnScrollEnd(
                        gridState = gridState,
                        itemCount = section.data.size,
                        onLoadMore = presenter::loadMore,
                    )
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 136.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        // weight(1f) bounds the grid to the remaining Column height so it owns
                        // its own scrolling — without it the last row can end up unreachable.
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        items(section.data, key = { it.id }) { rom ->
                            GameCard(
                                rom = rom,
                                onClick = { coordinator.openGameDetail(rom.id, parent) },
                                modifier = Modifier.focusableItem("grid:${rom.id}", navigator) {
                                    coordinator.openGameDetail(rom.id, parent)
                                },
                            )
                        }
                        if (uiState.isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(color = colors.romm500)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fires [onLoadMore] once the user has scrolled within a few rows of the end
 * of the grid. The threshold decision is delegated to the pure, unit-tested
 * [shouldLoadMoreOnScrollEnd] predicate. `internal` so the Search screen
 * (same package) can reuse it.
 */
@Composable
internal fun LoadMoreOnScrollEnd(
    gridState: LazyGridState,
    itemCount: Int,
    onLoadMore: () -> Unit,
) {
    val shouldLoad by remember(itemCount) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            shouldLoadMoreOnScrollEnd(lastVisible, itemCount)
        }
    }
    LaunchedEffect(gridState, itemCount) {
        snapshotFlow { shouldLoad }.collect { if (it) onLoadMore() }
    }
}
