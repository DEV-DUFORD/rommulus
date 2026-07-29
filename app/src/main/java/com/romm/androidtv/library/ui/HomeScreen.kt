package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import com.romm.androidtv.library.CollectionSummary
import com.romm.androidtv.library.HomeViewModel
import com.romm.androidtv.library.LibraryRom
import com.romm.androidtv.library.PlatformSummary
import com.romm.androidtv.library.SectionState
import com.romm.androidtv.romm.RommApiError

/**
 * Top-level native browsing screen (UI_REFACTOR.md): a collapsible left
 * [NavRail] plus a vertically scrollable stack of horizontally-scrollable
 * shelves. A shelf is omitted entirely when it has no items (e.g. no
 * favorites configured on the server) rather than rendered empty.
 */
@Composable
fun NativeHomeScreen(
    viewModel: HomeViewModel,
    onOpenPlatforms: () -> Unit,
    onOpenCollections: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val navIcons = remember {
        mapOf(
            NavDestination.HOME to Icons.Filled.Home,
            NavDestination.PLATFORMS to Icons.Filled.Apps,
            NavDestination.COLLECTIONS to Icons.Filled.Collections,
            NavDestination.SEARCH to Icons.Filled.Search,
            NavDestination.SETTINGS to Icons.Filled.Settings,
        )
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi),
    ) {
        NavRail(
            selected = NavDestination.HOME,
            icons = navIcons,
            onSelect = { destination ->
                when (destination) {
                    NavDestination.HOME -> Unit
                    NavDestination.PLATFORMS -> onOpenPlatforms()
                    NavDestination.COLLECTIONS -> onOpenCollections()
                    NavDestination.SEARCH -> onOpenSearch()
                    NavDestination.SETTINGS -> onOpenSettings()
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(RommTvColors.StageLo.copy(alpha = 0.35f), RommTvColors.NightHi),
                        endY = 600f,
                    ),
                )
                .padding(top = 32.dp, start = 8.dp),
        ) {
            item {
                RomShelf(
                    title = "Continue Playing",
                    state = uiState.continuePlaying,
                    onRetry = viewModel::retryContinuePlaying,
                )
            }
            item {
                RomShelf(
                    title = "Recently Added",
                    state = uiState.recentlyAdded,
                    onRetry = viewModel::retryRecentlyAdded,
                )
            }
            item {
                RomShelf(
                    title = "Favorites",
                    state = uiState.favorites,
                    onRetry = viewModel::retryFavorites,
                )
            }
        }
    }
}

@Composable
private fun RomShelf(
    title: String,
    state: SectionState<List<LibraryRom>>,
    onRetry: () -> Unit,
) {
    // Omit the shelf entirely once we know it's empty — never render an empty row.
    if (state is SectionState.Loaded && state.data.isEmpty()) return

    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = RommTvColors.TextPrimary,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
        )

        when (state) {
            is SectionState.Loading -> ShelfPlaceholder {
                CircularProgressIndicator(color = RommTvColors.Romm500)
            }

            is SectionState.Error -> ShelfPlaceholder {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Couldn't load \"$title\" (${state.error.name.lowercase().replace('_', ' ')})",
                        color = RommTvColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onRetry) {
                        Text("Retry", color = RommTvColors.Romm300)
                    }
                }
            }

            is SectionState.Loaded -> {
                TvLazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.data, key = { it.id }) { rom ->
                        GameCard(
                            title = rom.title,
                            subtitle = rom.platformDisplayName,
                            coverUrl = rom.coverUrl,
                        )
                    }
                }
            }
        }
    }
}

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

/** Minimal grid screen for platforms — see UI_REFACTOR.md section 4 (out of scope: platform-detail browsing). */
@Composable
fun PlatformsScreen(state: SectionState<List<PlatformSummary>>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    TileGridScreen(
        title = "Platforms",
        state = state,
        onRetry = onRetry,
        modifier = modifier,
        tileContent = { platform -> TileCard(title = platform.displayName, subtitle = "${platform.romCount} games", imageUrl = platform.logoUrl) },
    )
}

/** Minimal grid screen for collections — see UI_REFACTOR.md section 4 (out of scope: collection-detail browsing). */
@Composable
fun CollectionsScreen(state: SectionState<List<CollectionSummary>>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    TileGridScreen(
        title = "Collections",
        state = state,
        onRetry = onRetry,
        modifier = modifier,
        tileContent = { collection -> TileCard(title = collection.name, subtitle = "${collection.romCount} games", imageUrl = collection.coverUrl) },
    )
}

@Composable
private fun <T> TileGridScreen(
    title: String,
    state: SectionState<List<T>>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    tileContent: @Composable (T) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi)
            .padding(24.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, color = RommTvColors.TextPrimary)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
        when (state) {
            is SectionState.Loading -> CircularProgressIndicator(color = RommTvColors.Romm500)
            is SectionState.Error -> Column {
                Text(
                    text = "Couldn't load $title (${state.error.name.lowercase().replace('_', ' ')})",
                    color = RommTvColors.TextSecondary,
                )
                TextButton(onClick = onRetry) { Text("Retry", color = RommTvColors.Romm300) }
            }
            is SectionState.Loaded -> {
                if (state.data.isEmpty()) {
                    Text(text = "Nothing here yet.", color = RommTvColors.TextSecondary)
                } else {
                    LazyColumn {
                        items(state.data.chunked(4)) { rowItems ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(bottom = 16.dp),
                            ) {
                                rowItems.forEach { item -> tileContent(item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileCard(title: String, subtitle: String, imageUrl: String?) {
    Column(modifier = Modifier.padding(4.dp)) {
        Box(
            modifier = Modifier
                .height(100.dp)
                .background(RommTvColors.NightLo),
        ) {
            if (imageUrl != null) {
                coil.compose.AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(text = title, color = RommTvColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
        Text(text = subtitle, color = RommTvColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

/** Stub search screen — full search UI is out of scope for this pass (UI_REFACTOR.md section 4). */
@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Search — coming soon", color = RommTvColors.TextSecondary, style = MaterialTheme.typography.titleMedium)
    }
}
