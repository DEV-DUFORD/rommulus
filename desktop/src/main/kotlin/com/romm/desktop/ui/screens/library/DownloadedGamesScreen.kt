package com.romm.desktop.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.Screen
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.navigation.LocalFocusNavigator

@Composable
fun DownloadedGamesScreen(coordinator: DesktopAppCoordinator) {
    val colors = LocalRommulusColors.current
    val roms = coordinator.downloadedGames()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.nightHi)
            .padding(24.dp),
    ) {
        Text(
            text = "Downloaded Games",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
        )
        Text(
            text = "${roms.size} games - most recently played first",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (roms.isEmpty()) {
            Text(
                text = "Games you play will appear here after they are downloaded.",
                color = colors.textSecondary,
            )
        } else {
            PositionalRomGrid(
                navigator = LocalFocusNavigator.current,
                gridState = rememberLazyGridState(),
                roms = roms,
                cardKeyPrefix = "downloads:",
                onOpen = { coordinator.openGameDetail(it, Screen.DOWNLOADED) },
                modifier = Modifier.fillMaxSize(),
                isLoadingMore = false,
            )
        }
    }
}
