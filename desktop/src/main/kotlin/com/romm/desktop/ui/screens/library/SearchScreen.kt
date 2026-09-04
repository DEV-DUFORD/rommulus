package com.romm.desktop.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.romm.androidtv.romm.RommApiError
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.Screen
import com.romm.desktop.ui.components.DesktopTextField
import com.romm.desktop.ui.components.ErrorBanner
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.LoadingIndicator
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts

/**
 * Desktop Search screen (Phase 6): a [DesktopTextField] driving the shared
 * [com.romm.androidtv.library.SearchPresenter] (obtained via
 * `coordinator.searchPresenter()`, remembered once per composition).
 *
 * The presenter debounces typing and paginates results; Enter submits
 * immediately ([DesktopTextField] `onDone` → [com.romm.androidtv.library.SearchPresenter.submitQuery]).
 * Results render as a paginated [GameCard] grid (same [LoadMoreOnScrollEnd]
 * pattern as [RomGridScreen]) with idle/loading/error/empty states.
 */
@Composable
fun SearchScreen(
    coordinator: DesktopAppCoordinator,
    modifier: Modifier = Modifier,
) {
    val presenter = remember { coordinator.searchPresenter() }
    val uiState by presenter.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val colors = LocalRommulusColors.current
    // Shell-provided shared navigator (controller D-pad / left-stick navigation).
    val navigator = LocalFocusNavigator.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.nightHi)
            .keyboardShortcuts(
                onBack = { coordinator.onBack() },
                onSearch = { /* already on the search screen */ },
                onQuit = { /* window close is owned by the desktop shell */ },
            )
            .padding(24.dp),
    ) {
        // ---- Search input (Enter submits, bypassing the presenter's debounce) ----
        DesktopTextField(
            value = uiState.query,
            onValueChange = presenter::onQueryChanged,
            label = "Search",
            placeholder = "Search your library…",
            onDone = presenter::submitQuery,
            modifier = Modifier.focusableItem(
                key = "search:query",
                navigator = navigator,
                onActivate = { coordinator.virtualKeyboardLauncher.launch() },
            ),
        )

        // ---- Result count label ----
        // Uses the presenter's snapshotted hide-unsupported flag: toggle ON → visible
        // count; toggle OFF → server total (mirrors the Android label rule).
        if (uiState.roms.isNotEmpty()) {
            Text(
                text = resultCountLabel(searchResultCount(uiState)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- State content ----
        when {
            uiState.query.isBlank() && !uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Type to search your library",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            uiState.isLoading && uiState.roms.isEmpty() && uiState.error == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator()
            }

            uiState.error != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // `uiState` is a delegated property, so no smart cast — elvis to a safe default.
                    val error = uiState.error ?: RommApiError.SERVER_ERROR
                    ErrorBanner(message = "Search failed (${errorMessage(error)})")
                    RetryButton(onRetry = presenter::retry)
                }
            }

            uiState.roms.isEmpty() && !uiState.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No results for \"${uiState.activeQuery ?: uiState.query}\"",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            else -> {
                LoadMoreOnScrollEnd(
                    gridState = gridState,
                    itemCount = uiState.roms.size,
                    onLoadMore = presenter::loadMore,
                )
                // Positional D-pad grid navigation (Android positionalGridNeighbor parity):
                // Up/Down move to the card directly above/below, scrolling when needed.
                PositionalRomGrid(
                    navigator = navigator,
                    gridState = gridState,
                    roms = uiState.roms,
                    cardKeyPrefix = "search:",
                    onOpen = { id -> coordinator.openGameDetail(id, Screen.SEARCH) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
