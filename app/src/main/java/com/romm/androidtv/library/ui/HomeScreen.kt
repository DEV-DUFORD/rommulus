package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import coil.compose.AsyncImage
import com.romm.androidtv.library.CollectionSummary
import com.romm.androidtv.library.HomeViewModel
import com.romm.androidtv.library.LibraryRom
import com.romm.androidtv.library.PlatformSummary
import com.romm.androidtv.library.SectionState

/**
 * Top-level Home content: a vertically scrollable stack of horizontally
 * scrollable shelves. A shelf is omitted entirely when it has no items (e.g.
 * no favorites configured on the server) rather than rendered empty. Meant
 * to be hosted inside [LibraryScaffold]'s content slot, which owns the
 * sidebar (UI_REFACTOR.md section 7.1 — this screen previously built its own
 * Row+NavRail, which meant Platforms/Collections/Search had no sidebar at
 * all; fixed by extracting that into a shared scaffold).
 */
@Composable
fun NativeHomeScreen(
    viewModel: HomeViewModel,
    onOpenGameDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(RommTvColors.StageLo.copy(alpha = 0.35f), RommTvColors.NightHi),
                    endY = 600f,
                ),
            )
            .padding(top = 32.dp, start = 8.dp),
        // Bottom breathing room so the last shelf's title/subtitle isn't flush against
        // the screen edge once scrolled all the way down (matches the Platforms/Collections
        // grid fix — TV displays leave little/no margin for content sitting right at the edge).
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            RomShelf(
                title = "Continue Playing",
                state = uiState.continuePlaying,
                onRetry = viewModel::retryContinuePlaying,
                onCardClick = { onOpenGameDetail(it.id) },
            )
        }
        item {
            RomShelf(
                title = "Recently Added",
                state = uiState.recentlyAdded,
                onRetry = viewModel::retryRecentlyAdded,
                onCardClick = { onOpenGameDetail(it.id) },
            )
        }
        item {
            RomShelf(
                title = "Favorites",
                state = uiState.favorites,
                onRetry = viewModel::retryFavorites,
                onCardClick = { onOpenGameDetail(it.id) },
            )
        }
    }
}

@Composable
private fun RomShelf(
    title: String,
    state: SectionState<List<LibraryRom>>,
    onRetry: () -> Unit,
    onCardClick: (LibraryRom) -> Unit,
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
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.data, key = { it.id }) { rom ->
                        GameCard(
                            title = rom.title,
                            subtitle = rom.platformDisplayName,
                            coverUrl = rom.coverUrl,
                            onClick = { onCardClick(rom) },
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

/** Grid screen for platforms — tapping a tile opens [PlatformDetailScreen] (UI_REFACTOR.md section 7). */
@Composable
fun PlatformsScreen(
    state: SectionState<List<PlatformSummary>>,
    onRetry: () -> Unit,
    onOpenPlatform: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    TileGridScreen(
        title = "Platforms",
        state = state,
        onRetry = onRetry,
        modifier = modifier,
        key = { it.id },
        tileContent = { platform ->
            TileCard(
                title = platform.displayName,
                subtitle = "${platform.romCount} games",
                // Prefer RomM's own bundled platform glyphs (matches the webapp's
                // Platforms grid), trying SVG then ICO since not every platform has
                // both on file; fall back to the metadata-provider logo (e.g. an
                // IGDB photo/wordmark) only if the server has neither.
                imageUrls = platform.iconUrlCandidates + listOfNotNull(platform.logoUrl),
                imagePadding = 20.dp,
                onClick = { onOpenPlatform(platform.id) },
            )
        },
    )
}

/** Grid screen for collections — tapping a tile opens [CollectionDetailScreen] (UI_REFACTOR.md section 7). */
@Composable
fun CollectionsScreen(
    state: SectionState<List<CollectionSummary>>,
    onRetry: () -> Unit,
    onOpenCollection: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    TileGridScreen(
        title = "Collections",
        state = state,
        onRetry = onRetry,
        modifier = modifier,
        key = { it.id },
        tileContent = { collection ->
            TileCard(
                title = collection.name,
                subtitle = "${collection.romCount} games",
                imageUrls = listOfNotNull(collection.coverUrl),
                onClick = { onOpenCollection(collection.id) },
            )
        },
    )
}

@Composable
private fun <T> TileGridScreen(
    title: String,
    state: SectionState<List<T>>,
    onRetry: () -> Unit,
    key: (T) -> Any,
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
        Spacer(modifier = Modifier.height(16.dp))
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
                    // Fixed-size cells (not chunked Rows) so a single item never stretches to
                    // claim the whole row's width (UI_REFACTOR.md section 7.1, bug 1).
                    // Bounded to the remaining Column height via weight(1f) so the grid owns
                    // its own internal scrolling — without it, the grid wasn't constrained and
                    // the last row could render partially or fully off-screen with no way to
                    // scroll it into view.
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        items(state.data, key = key) { item -> tileContent(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileCard(title: String, subtitle: String, imageUrls: List<String>, imagePadding: Dp = 0.dp, onClick: () -> Unit = {}) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // Cascades through [imageUrls] on load failure (e.g. RomM's platform icon set
    // only has a `.ico` on file for some systems, not `.svg`; see LibraryApi.platformIconUrls),
    // falling back to the generic glyph once every candidate has failed.
    var candidateIndex by remember(imageUrls) { mutableStateOf(0) }
    val currentUrl = imageUrls.getOrNull(candidateIndex)

    // `clickable` (and thus the focus target used by Compose's "scroll focused item into
    // view" behavior) lives on the whole Column, not just the image Box below — otherwise
    // bring-into-view only guarantees the image is on-screen, leaving the title/subtitle
    // Text (a sibling outside that Box) clipped at the bottom edge for the last grid row.
    Column(
        modifier = Modifier
            .padding(4.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(8.dp))
                .background(RommTvColors.NightLo)
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = if (isFocused) RommTvColors.Romm500 else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (currentUrl != null) {
                AsyncImage(
                    model = currentUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    onError = { candidateIndex++ },
                    modifier = Modifier.fillMaxSize().padding(imagePadding),
                )
            } else {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Apps,
                    contentDescription = null,
                    tint = RommTvColors.TextSecondary,
                )
            }
        }
        Text(
            text = title,
            color = if (isFocused) RommTvColors.Romm300 else RommTvColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Text(
            text = subtitle,
            color = RommTvColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}


