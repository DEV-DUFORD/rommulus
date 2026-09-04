package com.romm.desktop.ui.screens.controller

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.navigation.keyboardShortcuts

@Composable
fun KeyboardConsoleListScreen(coordinator: DesktopAppCoordinator) {
    val colors = LocalRommulusColors.current
    val profiles = remember {
        CoreControllerProfiles.forApprovedCores(setOf(coordinator.layout.buildIdentity))
    }
    Column(
        Modifier.fillMaxSize()
            .background(colors.nightHi)
            .keyboardShortcuts(coordinator::onBack, {}, {})
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Text(
            "Keyboard Control Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            "Map keyboard keys to console controls. Keyboard input is assigned to Player 1.",
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(profiles, key = { it.coreId }) { profile ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.nightLo)
                        .clickable { coordinator.openKeyboardConfig(profile.coreId) }
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.consoleName, color = colors.textPrimary)
                        profile.consoleSubtitle?.let { Text(it, color = colors.textSecondary) }
                    }
                    Text("Configure", color = colors.romm300)
                }
            }
        }
    }
}
