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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.romm.androidtv.library.LibraryRepository
import com.romm.androidtv.library.LibraryRom
import com.romm.androidtv.library.SearchUiState
import com.romm.androidtv.library.SearchViewModel
import kotlinx.coroutines.flow.Flow

/**
 * Full search screen for the native browsing UI (UI_REFACTOR.md section 4).
 * Uses the host-provided authenticated [LibraryRepository], matching the rest of
 * the native library screens.
 *
 * Features: debounced query input, loading/error/empty/results states, pagination,
 * retry, D-pad/focus-friendly UX, GameCard grid reuse.
 */
@Composable
fun SearchScreen(
    repository: LibraryRepository,
    modifier: Modifier = Modifier,
    onGameSelected: (Long) -> Unit = {},
    hideUnsupportedSystems: () -> Boolean = { true },
    hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
    refreshEvents: Flow<Unit>? = null,
) {
    val profile = com.romm.androidtv.platform.currentDeviceProfile()
    val portraitTouchLayout = profile.usePortraitTouchLayout
    val viewModel: SearchViewModel = viewModel(
        factory = remember(repository, refreshEvents) {
            SearchViewModel.Factory(
                repository,
                hideUnsupportedSystems,
                hideUnsupportedSystemsFlow,
                refreshEvents,
            )
        },
    )

    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi)
            .padding(if (portraitTouchLayout) 16.dp else 24.dp),
    ) {
        // ---- Search input ----
        ControllerFriendlyTextField(
            value = uiState.query,
            onValueChange = viewModel::onQueryChanged,
            placeholder = {
                Text("Search your library…", color = RommTvColors.TextSecondary)
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = RommTvColors.TextSecondary,
                )
            },
            touchEditEnabled = profile.hasTouchscreen,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { viewModel.submitQuery() },
            ),
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = RommTvColors.NightLo,
                unfocusedContainerColor = RommTvColors.NightLo,
                focusedIndicatorColor = RommTvColors.Romm500,
                unfocusedIndicatorColor = RommTvColors.TextSecondary.copy(alpha = 0.3f),
            ),
        )

        // ---- Result count label ----
        // Uses explicit hideUnsupportedSystems from SearchUiState (snapshotted once per fetch).
        // Toggle ON: always displays current visible count ("N results"), never raw server total.
        // Toggle OFF: displays server total ("N results").
        if (uiState.roms.isNotEmpty()) {
            val visibleCount = uiState.roms.size
            val labelText = when {
                uiState.hideUnsupportedSystems -> "$visibleCount result${if (visibleCount != 1) "s" else ""}"
                else -> "${uiState.total} result${if (uiState.total != 1) "s" else ""}"
            }
            Text(
                text = labelText,
                style = MaterialTheme.typography.bodySmall,
                color = RommTvColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))

        // ---- State content ----
        when {
            uiState.query.isBlank() && !uiState.isLoading -> IdleSearchState()
            uiState.isLoading && uiState.roms.isEmpty() && uiState.error == null -> LoadingState()
            uiState.error != null -> ErrorState(uiState.error!!, onRetry = viewModel::retry)
            uiState.roms.isEmpty() && !uiState.isLoading -> EmptySearchState(uiState.activeQuery ?: uiState.query)
            else -> {
                val gridState = rememberLazyGridState()
                SearchResultsGrid(
                    roms = uiState.roms,
                    gridState = gridState,
                    compact = portraitTouchLayout,
                    onLoadMore = viewModel::loadMore,
                    onGameSelected = onGameSelected,
                )
            }
        }
    }
}

/** Idle state when no query has been entered yet. */
@Composable
private fun IdleSearchState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Type to search your library",
            color = RommTvColors.TextSecondary,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Loading spinner for the first page of results. */
@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = RommTvColors.Romm500)
    }
}

/** Error state with retry button. */
@Composable
private fun ErrorState(error: com.romm.androidtv.romm.RommApiError, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Search failed (${error.name.lowercase().replace('_', ' ')})",
                color = RommTvColors.TextSecondary,
            )
            TextButton(onClick = onRetry, modifier = Modifier.tvButtonFocus()) {
                Text("Retry", color = RommTvColors.Romm300)
            }
        }
    }
}

/** Empty state when a query returned zero results. */
@Composable
private fun EmptySearchState(query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "No results for \"$query\"",
            color = RommTvColors.TextSecondary,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Paginated grid of [GameCard] results with auto-load-more on scroll. */
@Composable
private fun SearchResultsGrid(
    roms: List<LibraryRom>,
    gridState: LazyGridState,
    compact: Boolean,
    onLoadMore: () -> Unit,
    onGameSelected: (Long) -> Unit,
) {
    LoadMoreOnScrollEnd(gridState = gridState, itemCount = roms.size, onLoadMore = onLoadMore)

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = if (compact) 112.dp else 136.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(roms, key = { it.id }) { rom ->
            GameCard(
                title = rom.title,
                subtitle = rom.platformDisplayName,
                coverUrl = rom.coverUrl,
                onClick = { onGameSelected(rom.id) },
            )
        }
    }
}

/** Fires [onLoadMore] once the user has scrolled within a few rows of the end. */
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
