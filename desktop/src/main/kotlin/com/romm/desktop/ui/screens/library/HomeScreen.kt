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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
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
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts
import kotlinx.coroutines.launch

/**
 * Desktop Home screen: Android-parity shelves for Continue Playing, Recently
 * Added, and Favorites.
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
    val navigator = LocalFocusNavigator.current
    val columnState = rememberLazyListState()
    val continuePlayingState = rememberLazyListState()
    val recentlyAddedState = rememberLazyListState()
    val favoritesState = rememberLazyListState()
    val shelfStates = mapOf(
        HomeShelf.CONTINUE_PLAYING to continuePlayingState,
        HomeShelf.RECENTLY_ADDED to recentlyAddedState,
        HomeShelf.FAVORITES to favoritesState,
    )
    val rememberedCardIndices = remember { mutableStateMapOf<HomeShelf, Int>() }
    val navigationScope = rememberCoroutineScope()
    val navigationOwner = remember { Any() }
    val navigationShelves = listOfNotNull(
        uiState.continuePlaying.navigationSnapshot(
            HomeShelf.CONTINUE_PLAYING,
            rememberedCardIndices[HomeShelf.CONTINUE_PLAYING] ?: 0,
        ),
        uiState.recentlyAdded.navigationSnapshot(
            HomeShelf.RECENTLY_ADDED,
            rememberedCardIndices[HomeShelf.RECENTLY_ADDED] ?: 0,
        ),
        uiState.favorites.navigationSnapshot(
            HomeShelf.FAVORITES,
            rememberedCardIndices[HomeShelf.FAVORITES] ?: 0,
        ),
    )

    DisposableEffect(navigator, navigationOwner, navigationShelves) {
        navigator.installGridNavigation(navigationOwner) { direction ->
            val target = homeShelfNavigationTarget(
                focusedKey = navigator.currentFocusKey(),
                shelves = navigationShelves,
                moveDown = direction == FocusDirection.Down,
            ) ?: return@installGridNavigation false

            navigationScope.launch {
                columnState.scrollToItem(target.shelf.listIndex)
                withFrameNanos { }
                withFrameNanos { }

                val rowState = shelfStates.getValue(target.shelf)
                if (rowState.layoutInfo.visibleItemsInfo.none { it.index == target.cardIndex }) {
                    rowState.scrollToItem(target.cardIndex)
                    withFrameNanos { }
                    withFrameNanos { }
                }
                navigator.focusItem(target.cardKey)
            }
            true
        }
        onDispose { navigator.removeGridNavigation(navigationOwner) }
    }

    LazyColumn(
        state = columnState,
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
                shelf = HomeShelf.CONTINUE_PLAYING,
                state = uiState.continuePlaying,
                rowState = continuePlayingState,
                onRetry = presenter::retryContinuePlaying,
                onCardClick = { rom -> coordinator.openGameDetail(rom.id, Screen.HOME) },
                onCardFocused = { rememberedCardIndices[HomeShelf.CONTINUE_PLAYING] = it },
            )
        }
        item {
            RomShelf(
                shelf = HomeShelf.RECENTLY_ADDED,
                state = uiState.recentlyAdded,
                rowState = recentlyAddedState,
                onRetry = presenter::retryRecentlyAdded,
                onCardClick = { rom -> coordinator.openGameDetail(rom.id, Screen.HOME) },
                onCardFocused = { rememberedCardIndices[HomeShelf.RECENTLY_ADDED] = it },
            )
        }
        item {
            RomShelf(
                shelf = HomeShelf.FAVORITES,
                state = uiState.favorites,
                rowState = favoritesState,
                onRetry = presenter::retryFavorites,
                onCardClick = { rom -> coordinator.openGameDetail(rom.id, Screen.HOME) },
                onCardFocused = { rememberedCardIndices[HomeShelf.FAVORITES] = it },
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
    shelf: HomeShelf,
    state: SectionState<List<LibraryRom>>,
    rowState: LazyListState,
    onRetry: () -> Unit,
    onCardClick: (LibraryRom) -> Unit,
    onCardFocused: (Int) -> Unit,
) {
    val colors = LocalRommulusColors.current
    val title = shelf.title

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
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(roms, key = { _, rom -> rom.id }) { cardIndex, rom ->
                        GameCard(
                            rom = rom,
                            onClick = { onCardClick(rom) },
                            modifier = Modifier
                                .onFocusChanged { if (it.isFocused) onCardFocused(cardIndex) }
                                .focusableItem(shelf.cardKey(rom.id), navigator) { onCardClick(rom) },
                        )
                    }
                }
            }

            SectionDisplayState.EMPTY -> Unit
        }
    }
}

internal enum class HomeShelf(
    val title: String,
    val listIndex: Int,
) {
    CONTINUE_PLAYING("Continue Playing", 0),
    RECENTLY_ADDED("Recently Added", 1),
    FAVORITES("Favorites", 2),
    ;

    fun cardKey(romId: Long): String = "home:$title:$romId"
}

internal data class HomeShelfNavigationSnapshot(
    val shelf: HomeShelf,
    val cardKeys: List<String>,
    val rememberedCardIndex: Int,
)

internal data class HomeShelfNavigationTarget(
    val shelf: HomeShelf,
    val cardIndex: Int,
    val cardKey: String,
)

internal fun homeShelfNavigationTarget(
    focusedKey: Any?,
    shelves: List<HomeShelfNavigationSnapshot>,
    moveDown: Boolean,
): HomeShelfNavigationTarget? {
    val currentShelfIndex = shelves.indexOfFirst { focusedKey in it.cardKeys }
    if (currentShelfIndex < 0) return null
    val targetShelfIndex = currentShelfIndex + if (moveDown) 1 else -1
    val targetShelf = shelves.getOrNull(targetShelfIndex) ?: return null
    val cardIndex = targetShelf.rememberedCardIndex.coerceIn(targetShelf.cardKeys.indices)
    return HomeShelfNavigationTarget(
        shelf = targetShelf.shelf,
        cardIndex = cardIndex,
        cardKey = targetShelf.cardKeys[cardIndex],
    )
}

private fun SectionState<List<LibraryRom>>.navigationSnapshot(
    shelf: HomeShelf,
    rememberedCardIndex: Int,
): HomeShelfNavigationSnapshot? {
    val roms = (this as? SectionState.Loaded<List<LibraryRom>>)?.data.orEmpty()
    if (roms.isEmpty()) return null
    return HomeShelfNavigationSnapshot(
        shelf = shelf,
        cardKeys = roms.map { shelf.cardKey(it.id) },
        rememberedCardIndex = rememberedCardIndex,
    )
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
