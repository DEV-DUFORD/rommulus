package com.romm.desktop.ui.screens.controller

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.romm.androidtv.controller.config.CoreControllerProfile
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.tvFocusRing
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts

/**
 * The console-list entry point of the desktop controller-settings flow (E2) — the desktop
 * mirror of Android's [com.romm.androidtv.controller.ui.ControllerConsoleListScreen].
 *
 * Lists every approved core from the shared [CoreControllerProfiles] catalog as a focusable
 * card showing the console name (never a core id), its optional subtitle, and its player-port
 * count. Selecting a card opens [com.romm.desktop.Screen.CONTROLLER_CONFIG] for that core via
 * [DesktopAppCoordinator.openControllerConfig]; Escape / Back returns to Settings via
 * [DesktopAppCoordinator.onBack] (CONTROLLER_LIST's parent is SETTINGS).
 */
@Composable
fun ControllerConsoleListScreen(
    coordinator: DesktopAppCoordinator,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val profiles = remember { CoreControllerProfiles.forApprovedCores() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.nightHi)
            .keyboardShortcuts(
                onBack = { coordinator.onBack() },
                onSearch = { /* search is not reachable from this screen */ },
                onQuit = { /* window close is owned by the desktop shell */ },
            )
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Text(
            text = "Controller Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(bottom = 24.dp),
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // weight(1f) bounds the list to the remaining Column height so it owns its own
            // scrolling — without it the last card can end up unreachable.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(profiles, key = { it.coreId }) { profile ->
                ConsoleCard(
                    profile = profile,
                    onClick = { coordinator.openControllerConfig(profile.coreId) },
                )
            }
        }
    }
}

/**
 * One focusable console card: console name (main), optional subtitle beneath, and a
 * player-port count badge on the right. Mirrors the Android ConsoleCard's GameCard-style
 * focus highlight.
 */
@Composable
private fun ConsoleCard(
    profile: CoreControllerProfile,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) colors.romm600.copy(alpha = 0.3f) else colors.nightLo)
            .tvFocusRing(shape = RoundedCornerShape(8.dp))
            .focusableItem("controller-list:${profile.coreId}", navigator, onClick)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { contentDescription = "${profile.consoleName}, ${consolePortCountLabel(profile.playerCount)}" }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.consoleName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (focused) colors.romm300 else colors.textPrimary,
                    maxLines = 1,
                )
                profile.consoleSubtitle?.let { subtitle ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = consolePortCountLabel(profile.playerCount),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
        }
    }
}

/** "%d port(s)" — mirrors Android's `controller_console_port_count` string. */
fun consolePortCountLabel(playerCount: Int): String =
    "$playerCount port${if (playerCount != 1) "s" else ""}"
