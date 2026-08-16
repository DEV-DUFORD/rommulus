package com.romm.desktop.ui.screens.detail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.BiosConfigurationOption
import com.romm.androidtv.library.BiosConfigurationState
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.LoadingIndicator
import com.romm.desktop.ui.components.tvFocusRing
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts

/**
 * RomM platform slug for a [DesktopAppCoordinator.BiosSystem]. The desktop
 * [com.romm.desktop.library.DesktopBiosConfigurationProvider] is constructed with the slug
 * ("sega_cd" / "psx"), exactly like the Android BIOS managers.
 */
private fun slugFor(system: DesktopAppCoordinator.BiosSystem): String = when (system) {
    DesktopAppCoordinator.BiosSystem.SEGA_CD -> "sega_cd"
    DesktopAppCoordinator.BiosSystem.PLAYSTATION -> "psx"
}

/**
 * Desktop BIOS configuration screen (SEGA CD / PlayStation): a mirror of the Android
 * [com.romm.androidtv.library.ui.BiosConfigurationScreen] driven through the shared
 * [com.romm.androidtv.library.BiosConfigurationPresenter].
 *
 * The active console comes from [DesktopAppCoordinator.selectedBiosSystem] (set via
 * `openBiosConfiguration(system)` before navigating to `Screen.BIOS_CONFIGURATION`);
 * the presenter is created for the matching platform slug and remembered per system so a
 * system switch (recomposition of this screen) builds a fresh presenter whose init kicks
 * off the catalog fetch.
 *
 * Renders:
 *  - [BiosConfigurationState.Loading] — centered spinner;
 *  - [BiosConfigurationState.Error] — message + Retry (`presenter.refresh()`);
 *  - [BiosConfigurationState.Loaded] — a LazyColumn of rows (checkmark on the selected
 *    firmware, display name + file name, a small spinner on the row whose firmware is
 *    downloading, all rows disabled while any download is in flight) plus a
 *    success/failure message below the list.
 *
 * Rows are keyboard/controller-focusable (`clickable` + auto-tracked [tvFocusRing]);
 * Escape backs out via [DesktopAppCoordinator.onBack].
 */
@Composable
fun BiosConfigurationScreen(
    coordinator: DesktopAppCoordinator,
    modifier: Modifier = Modifier,
) {
    val system = coordinator.selectedBiosSystem
    val presenter = remember(system) { coordinator.biosConfigurationPresenter(slugFor(system)) }
    val uiState by presenter.uiState.collectAsState()
    val colors = LocalRommulusColors.current

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(presenter.title, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                Text(
                    presenter.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            val navigator = LocalFocusNavigator.current
            TextButton(
                onClick = { coordinator.onBack() },
                modifier = Modifier.tvFocusRing().focusableItem("bios:back", navigator) { coordinator.onBack() },
            ) {
                Text("Back", color = colors.textSecondary)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        when (val current = uiState) {
            BiosConfigurationState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator()
            }

            is BiosConfigurationState.Error -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(current.message, color = colors.textSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                val navigator = LocalFocusNavigator.current
                TextButton(
                    onClick = presenter::refresh,
                    modifier = Modifier.tvFocusRing().focusableItem("bios:retry", navigator, presenter::refresh),
                ) {
                    Text("Retry", color = colors.romm300)
                }
            }

            is BiosConfigurationState.Loaded -> {
                if (current.options.isEmpty()) {
                    Text(
                        presenter.emptyMessage,
                        color = colors.textSecondary,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // weight(1f) bounds the list to the remaining Column height so it owns
                        // its own scrolling — without it the last row can end up unreachable.
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 8.dp),
                    ) {
                        items(current.options, key = { it.firmware.firmwareId }) { option ->
                            BiosOptionRow(
                                option = option,
                                selected = current.selectedFirmwareId == option.firmware.firmwareId,
                                downloading = current.downloadingFirmwareId == option.firmware.firmwareId,
                                enabled = current.downloadingFirmwareId == null,
                                onClick = { presenter.select(option) },
                            )
                        }
                    }
                    current.message?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(it, color = colors.romm300, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * One selectable BIOS row: checkmark on the currently-selected firmware, display name
 * (main) with the firmware file name beneath, and a small progress spinner while this
 * row's firmware is downloading. Dimmed and unclickable while any download is in flight.
 */
@Composable
private fun BiosOptionRow(
    option: BiosConfigurationOption,
    selected: Boolean,
    downloading: Boolean,
    enabled: Boolean,
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
            .tvFocusRing(shape = RoundedCornerShape(8.dp), enabled = enabled)
            .focusableItem("bios:${option.firmware.firmwareId}", navigator, onClick)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics { contentDescription = option.displayName }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected) "✓" else "",
                color = colors.romm300,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(option.displayName, color = colors.textPrimary)
                Text(
                    option.firmware.fileName,
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (downloading) {
                CircularProgressIndicator(
                    color = colors.romm500,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(20.dp),
                )
            }
        }
    }
}
