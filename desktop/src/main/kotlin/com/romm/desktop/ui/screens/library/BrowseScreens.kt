package com.romm.desktop.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.CollectionSummary
import com.romm.androidtv.library.PlatformSummary
import com.romm.androidtv.library.SectionState
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.ui.components.ErrorBanner
import com.romm.desktop.ui.components.LoadingIndicator
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.TileCard
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem

@Composable
fun PlatformsScreen(coordinator: DesktopAppCoordinator) {
    val presenter = remember { coordinator.homePresenter() }
    val state by presenter.uiState.collectAsState()
    TileGridScreen(
        title = "Platforms",
        state = state.platforms,
        onRetry = presenter::retryPlatforms,
        key = PlatformSummary::id,
        onActivate = { coordinator.openPlatformDetail(it.id) },
    ) { platform, modifier ->
        TileCard(
            title = platform.displayName,
            subtitle = "${platform.romCount} games",
            imageUrl = platformTileImageUrl(platform),
            onClick = { coordinator.openPlatformDetail(platform.id) },
            modifier = modifier,
        )
    }
}

@Composable
fun CollectionsScreen(coordinator: DesktopAppCoordinator) {
    val presenter = remember { coordinator.homePresenter() }
    val state by presenter.uiState.collectAsState()
    TileGridScreen(
        title = "Collections",
        state = state.collections,
        onRetry = presenter::retryCollections,
        key = CollectionSummary::id,
        onActivate = { coordinator.openCollectionDetail(it.id) },
    ) { collection, modifier ->
        TileCard(
            title = collection.name,
            subtitle = "${collection.romCount} games",
            imageUrl = collection.coverUrl,
            onClick = { coordinator.openCollectionDetail(collection.id) },
            modifier = modifier,
        )
    }
}

@Composable
private fun <T> TileGridScreen(
    title: String,
    state: SectionState<List<T>>,
    onRetry: () -> Unit,
    key: (T) -> Any,
    onActivate: (T) -> Unit,
    tile: @Composable (T, Modifier) -> Unit,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.nightHi)
            .padding(top = 24.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        when (state) {
            SectionState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator()
            }

            is SectionState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorBanner(message = "Couldn't load $title (${errorMessage(state.error)})")
                    RetryButton(onRetry)
                }
            }

            is SectionState.Loaded -> {
                if (state.data.isEmpty()) {
                    Text(
                        text = "Nothing here yet.",
                        color = colors.textSecondary,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 220.dp),
                        contentPadding = PaddingValues(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(state.data, key = key) { item ->
                            tile(
                                item,
                                Modifier.focusableItem(
                                    key = "browse:$title:${key(item)}",
                                    navigator = navigator,
                                    onActivate = { onActivate(item) },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
