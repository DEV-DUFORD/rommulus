package com.romm.desktop.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.LibraryRom
import com.romm.androidtv.library.SectionState
import com.romm.androidtv.romm.RommApiError
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.Screen
import com.romm.desktop.ui.components.ErrorBanner
import com.romm.desktop.ui.components.GameCard
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.LoadingIndicator
import com.romm.desktop.ui.components.TileCard
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts

/** Fixed width for platform/collection tiles inside the Home LazyRow shelves. */
private val DesktopTileShelfTileWidth: Dp = 176.dp

/**
 * Desktop Home screen (Phase 6): a vertically scrolling stack of five
 * independently-loading shelves — Continue Playing, Recently Added, and
 * Favorites (ROM shelves of [GameCard] in a [LazyRow]) plus Platforms and
 * Collections (tile shelves of [TileCard] in a [LazyRow]).
 *
 * Drives the shared [com.romm.androidtv.library.HomePresenter] obtained from
 * the [DesktopAppCoordinator] (`coordinator.homePresenter()`). The presenter is
 * created once per composition via `remember` because the factory constructs a
 * fresh presenter (and kicks off its initial refresh) on every call.
 *
 * Empty shelves are omitted entirely (Android Home parity); each shelf renders
 * its own loading/error state with a retry button wired to the presenter's
 * per-section retry methods.
 */
@Composable
fun HomeScreen(
    coordinator: DesktopAppCoordinator,
    modifier: Modifier = Modifier,
) {
    val presenter = remember { coordinator.homePresenter() }
    val uiState by presenter.uiState.collectAsState()
    val colors = LocalRommulusColors.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.nightHi)
            .keyboardShortcuts(
                onBack = { coordinator.onBack() },
                onSearch = { coordinator.navigate(Screen.SEARCH) },
                onQuit = { /* window close is owned by the desktop shell */ },
            )
            .padding(top = 24.dp, start = 16.dp, end = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            RomShelf(
                title = "Continue Playing",
                state = uiState.continuePlaying,
                onRetry = presenter::retryContinuePlaying,
                onCardClick = { rom -> coordinator.openGameDetail(rom.id, Screen.HOME) },
            )
        }
        item {
            RomShelf(
                title = "Recently Added",
                state = uiState.recentlyAdded,
                onRetry = presenter::retryRecentlyAdded,
                onCardClick = { rom -> coordinator.openGameDetail(rom.id, Screen.HOME) },
            )
        }
        item {
            RomShelf(
                title = "Favorites",
                state = uiState.favorites,
                onRetry = presenter::retryFavorites,
                onCardClick = { rom -> coordinator.openGameDetail(rom.id, Screen.HOME) },
            )
        }
        item {
            TileShelf(
                title = "Platforms",
                state = uiState.platforms,
                onRetry = presenter::retryPlatforms,
                key = { it.id },
                tile = { platform ->
                    val navigator = LocalFocusNavigator.current
                    TileCard(
                        title = platform.displayName,
                        subtitle = "${platform.romCount} games",
                        // Prefer RomM's bundled platform glyphs (SVG then ICO), falling back to
                        // the metadata-provider logo only if the server has neither.
                        imageUrl = platformTileImageUrl(platform),
                        onClick = { coordinator.openPlatformDetail(platform.id) },
                        modifier = Modifier
                            .width(DesktopTileShelfTileWidth)
                            .focusableItem("home:platform:${platform.id}", navigator) {
                                coordinator.openPlatformDetail(platform.id)
                            },
                    )
                },
            )
        }
        item {
            TileShelf(
                title = "Collections",
                state = uiState.collections,
                onRetry = presenter::retryCollections,
                key = { it.id },
                tile = { collection ->
                    val navigator = LocalFocusNavigator.current
                    TileCard(
                        title = collection.name,
                        subtitle = "${collection.romCount} games",
                        imageUrl = collection.coverUrl,
                        onClick = { coordinator.openCollectionDetail(collection.id) },
                        modifier = Modifier
                            .width(DesktopTileShelfTileWidth)
                            .focusableItem("home:collection:${collection.id}", navigator) {
                                coordinator.openCollectionDetail(collection.id)
                            },
                    )
                },
            )
        }
    }
}

/**
 * A horizontally scrolling ROM shelf (Continue Playing / Recently Added /
 * Favorites). Omitted entirely when its [SectionState] is empty-loaded.
 */
@Composable
private fun RomShelf(
    title: String,
    state: SectionState<List<LibraryRom>>,
    onRetry: () -> Unit,
    onCardClick: (LibraryRom) -> Unit,
) {
    val colors = LocalRommulusColors.current

    // Omit the shelf entirely once we know it's empty — never render an empty row.
    if (sectionDisplayState(state) == SectionDisplayState.EMPTY) return

    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        when (sectionDisplayState(state)) {
            SectionDisplayState.LOADING -> ShelfPlaceholder {
                LoadingIndicator()
            }

            SectionDisplayState.ERROR -> ShelfPlaceholder {
                val error = (state as? SectionState.Error)?.error ?: RommApiError.SERVER_ERROR
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorBanner(message = "Couldn't load \"$title\" (${errorMessage(error)})")
                    RetryButton(onRetry = onRetry)
                }
            }

            SectionDisplayState.CONTENT -> {
                val roms = (state as? SectionState.Loaded<List<LibraryRom>>)?.data ?: return
                val navigator = LocalFocusNavigator.current
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(roms, key = { it.id }) { rom ->
                        GameCard(
                            rom = rom,
                            onClick = { onCardClick(rom) },
                            modifier = Modifier.focusableItem("home:$title:${rom.id}", navigator) {
                                onCardClick(rom)
                            },
                        )
                    }
                }
            }

            SectionDisplayState.EMPTY -> Unit
        }
    }
}

/**
 * A horizontally scrolling tile shelf (Platforms / Collections), generic over
 * the tile item type. Omitted entirely when its [SectionState] is empty-loaded.
 */
@Composable
private fun <T> TileShelf(
    title: String,
    state: SectionState<List<T>>,
    onRetry: () -> Unit,
    key: (T) -> Any,
    tile: @Composable (T) -> Unit,
) {
    val colors = LocalRommulusColors.current

    if (sectionDisplayState(state) == SectionDisplayState.EMPTY) return

    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        when (sectionDisplayState(state)) {
            SectionDisplayState.LOADING -> ShelfPlaceholder {
                LoadingIndicator()
            }

            SectionDisplayState.ERROR -> ShelfPlaceholder {
                val error = (state as? SectionState.Error)?.error ?: RommApiError.SERVER_ERROR
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorBanner(message = "Couldn't load $title (${errorMessage(error)})")
                    RetryButton(onRetry = onRetry)
                }
            }

            SectionDisplayState.CONTENT -> {
                val shelfItems = (state as? SectionState.Loaded<List<T>>)?.data ?: return
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(shelfItems, key = key) { item -> tile(item) }
                }
            }

            SectionDisplayState.EMPTY -> Unit
        }
    }
}

/** Fixed-height placeholder area for a shelf's loading/error state. */
@Composable
private fun ShelfPlaceholder(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

/**
 * Accent-colored Retry button for shelf error states. `internal` so the
 * RomGrid and Search screens (same package) can reuse it.
 */
@Composable
internal fun RetryButton(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onRetry, modifier = modifier) {
        Text("Retry", color = LocalRommulusColors.current.romm300)
    }
}
