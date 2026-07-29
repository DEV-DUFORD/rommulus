package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Full-bleed transient drill-down screen for a quarantined save: unknown/incompatible provenance
 * or size mismatch. Structurally distinct from [SaveConflictScreen] — this does NOT offer
 * destructive adoption. The quarantined copy is preserved and requires a separate explicit
 * compatibility/import decision outside this screen's scope.
 *
 * Safest non-destructive initial focus is on the Dismiss/Acknowledge button.
 */
@Composable
fun SaveQuarantineScreen(
    model: SaveQuarantineUiModel,
    actions: QuarantinePresentationAction,
    modifier: Modifier = Modifier,
) {
    val dismissFocusRequester = remember { FocusRequester() }
    var attached by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(attached) {
        if (attached) {
            view.postOnAnimation {
                dismissFocusRequester.requestFocus()
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Title + reason badge
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = RommTvColors.TextPrimary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFff9800).copy(alpha = 0.2f),
                ) {
                    Text(
                        text = model.reason,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFff9800),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Explanation
        item {
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodyMedium,
                color = RommTvColors.TextSecondary,
            )
        }

        // Quarantined file metadata (read-only display)
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Quarantined File",
                    style = MaterialTheme.typography.titleMedium,
                    color = RommTvColors.Romm300,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuarantineMetadataRow(label = "File", value = model.quarantined.fileName)
                    model.quarantined.saveId?.let {
                        QuarantineMetadataRow(label = "Save ID", value = it.toString())
                    }
                    model.quarantined.sizeText?.let {
                        QuarantineMetadataRow(label = "Size", value = it)
                    }
                    model.quarantined.coreId?.let {
                        QuarantineMetadataRow(label = "Core", value = it)
                    }
                    model.quarantined.slot?.let {
                        QuarantineMetadataRow(label = "Slot", value = it)
                    }
                    QuarantineMetadataRow(
                        label = "ROM ID",
                        value = model.quarantined.romId?.toString() ?: "Unknown",
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Path display (read-only, not focusable)
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
        item {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFf44336).copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "This save is preserved on disk. Dismissing this screen does not resolve the quarantine; a separate compatibility or import decision is required to adopt it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFf44336).copy(alpha = 0.9f),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // Dismiss button — safest initial focus target
        item {
            Row(horizontalArrangement = Arrangement.Center) {
                QuarantineDismissButton(
                    text = "Acknowledge & Go Back",
                    onClick = actions::dismiss,
                    modifier = Modifier
                        .focusRequester(dismissFocusRequester)
                        .onGloballyPositioned {
                            if (!attached) {
                                attached = true
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun QuarantineMetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuarantineDismissButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Transparent)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = RommTvColors.Romm300,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable()
            .testTag("quarantine_dismiss_button")
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = RommTvColors.TextSecondary,
        )
    }
}
