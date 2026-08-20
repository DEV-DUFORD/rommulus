package com.romm.desktop.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.romm.androidtv.library.RommTheme
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.RommulusTheme
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts

/** Orange reason badge (mirrors Android's SaveQuarantineScreen). */
private val QuarantineBadgeOrange = Color(0xFFff9800)

/** Red warning surface (mirrors Android's SaveQuarantineScreen). */
private val QuarantineWarningRed = Color(0xFFf44336)

/**
 * Desktop mirror of Android's `SaveQuarantineScreen`: a read-only, acknowledge-only drill-down
 * for one quarantined save. Structurally distinct from any conflict-resolution UI — it offers NO
 * destructive adoption: the quarantined copy is preserved on disk and requires a separate
 * explicit compatibility/import decision outside this dialog's scope.
 *
 * Layout mirrors Android 1:1: title + orange reason badge, description, read-only metadata rows
 * (File / Save ID / Size / Core / Slot / ROM ID — null fields are omitted), "Stored at: <path>",
 * an explicit red warning that dismissing does not resolve the quarantine, and exactly ONE
 * action — "Acknowledge & Go Back" (non-mutating; also the safest initial focus target).
 *
 * Like [ThemePickerDialog], this is a separate desktop window: composition locals do NOT cross
 * into dialog windows, so the caller passes the active [theme] and the content re-wraps in
 * [RommulusTheme]; its own spatial-focus override keeps controller/keyboard navigation inside.
 */
@Composable
fun SaveQuarantineDialog(
    model: SaveQuarantineUiModel,
    theme: RommTheme,
    onDismiss: () -> Unit,
) {
    val acknowledgeFocusRequester = remember { FocusRequester() }

    RommulusTheme(theme = theme) {
        val colors = LocalRommulusColors.current
        val navigator = LocalFocusNavigator.current
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            val dialogFocusManager = LocalFocusManager.current
            val focusOverrideOwner = remember { Any() }
            DisposableEffect(navigator, dialogFocusManager, focusOverrideOwner) {
                navigator.installSpatialFocusOverride(
                    focusOverrideOwner,
                    dialogFocusManager::moveFocus,
                    onDismiss,
                )
                onDispose { navigator.removeSpatialFocusOverride(focusOverrideOwner) }
            }

            Column(
                modifier = Modifier
                    .width(480.dp)
                    .fillMaxWidth()
                    .background(colors.nightHi)
                    .keyboardShortcuts(
                        onBack = onDismiss,
                        onSearch = { /* no search inside the dialog */ },
                        onQuit = { /* window close is owned by the desktop shell */ },
                    )
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Title + reason badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = QuarantineBadgeOrange.copy(alpha = 0.2f),
                    ) {
                        Text(
                            text = model.reason,
                            style = MaterialTheme.typography.labelMedium,
                            color = QuarantineBadgeOrange,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                // Explanation
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )

                // Quarantined file metadata (read-only display)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Quarantined File",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.romm300,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuarantineMetadataRow(label = "File", value = model.fileName)
                        model.saveId?.let {
                            QuarantineMetadataRow(label = "Save ID", value = it.toString())
                        }
                        model.sizeText?.let {
                            QuarantineMetadataRow(label = "Size", value = it)
                        }
                        model.coreId?.let {
                            QuarantineMetadataRow(label = "Core", value = it)
                        }
                        model.slot?.let {
                            QuarantineMetadataRow(label = "Slot", value = it)
                        }
                        QuarantineMetadataRow(
                            label = "ROM ID",
                            value = model.romId?.toString() ?: "Unknown",
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Path display (read-only, not focusable)
                    if (model.quarantinedPath.isNotBlank()) {
                        Text(
                            text = "Stored at: ${model.quarantinedPath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Explicit note: no destructive action offered here
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = QuarantineWarningRed.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "This save is preserved on disk. Dismissing this dialog does not resolve the quarantine; a separate compatibility or import decision is required to adopt it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = QuarantineWarningRed.copy(alpha = 0.9f),
                        modifier = Modifier.padding(16.dp),
                    )
                }

                // The single action — safest initial focus target (mirrors Android)
                Row(horizontalArrangement = Arrangement.Center) {
                    QuarantineAcknowledgeButton(
                        text = "Acknowledge & Go Back",
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(acknowledgeFocusRequester),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        acknowledgeFocusRequester.requestFocus()
    }
}

/** A read-only label/value metadata row (mirrors Android's QuarantineMetadataRow). */
@Composable
private fun QuarantineMetadataRow(label: String, value: String) {
    val colors = LocalRommulusColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The dialog's single "Acknowledge & Go Back" affordance: mouse-clickable and a stop on the
 * D-pad/keyboard/controller focus path (same wiring pattern as [SaveActionButton]). Dismissing is
 * non-mutating — the quarantined copy stays preserved at its quarantine path.
 */
@Composable
private fun QuarantineAcknowledgeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) colors.romm500 else Color.Transparent)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) colors.romm300 else colors.textSecondary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .focusableItem("quarantine:acknowledge", navigator, onClick)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isFocused) Color.White else colors.textSecondary,
        )
    }
}
