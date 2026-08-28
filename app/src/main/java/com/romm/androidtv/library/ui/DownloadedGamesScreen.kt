package com.romm.androidtv.library.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.LibraryRom

@Composable
fun DownloadedGamesScreen(
    roms: List<LibraryRom>,
    onOpenGame: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = "Downloaded Games",
            style = MaterialTheme.typography.headlineSmall,
            color = RommTvColors.TextPrimary,
        )
        Text(
            text = "${roms.size} games - most recently played first",
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextSecondary,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        if (roms.isEmpty()) {
            Text(
                text = "Games you play will appear here after they are downloaded.",
                color = RommTvColors.TextSecondary,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(136.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(roms, key = { it.id }) { rom ->
                    GameCard(
                        title = rom.title,
                        subtitle = rom.platformDisplayName,
                        coverUrl = rom.coverUrl,
                        onClick = { onOpenGame(rom.id) },
                    )
                }
            }
        }
    }
}
