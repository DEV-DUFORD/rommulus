package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
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
 * Full-bleed transient drill-down screen for a genuine RomM synchronization conflict.
 * Displays local vs server metadata side-by-side and requires an explicit user choice.
 * Cancel/Go Back changes nothing. Safest non-destructive initial focus is on the Cancel button.
 *
 * This composable receives pure UI models ([SaveConflictUiModel]) plus a
 * [ConflictPresentationAction] interface; it never queries Room, makes network calls,
 * or writes to disk directly.
 */
@Composable
fun SaveConflictScreen(
    model: SaveConflictUiModel,
    actions: ConflictPresentationAction,
    modifier: Modifier = Modifier,
) {
    val cancelFocusRequester = remember { FocusRequester() }
    var attached by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(attached) {
        if (attached) {
            view.postOnAnimation {
                cancelFocusRequester.requestFocus()
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
        // Title + description
        item {
            Text(
                text = model.title,
                style = MaterialTheme.typography.headlineSmall,
                color = RommTvColors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodyMedium,
                color = RommTvColors.TextSecondary,
            )
        }

        // Side-by-side comparison
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SaveConflictColumn(
                    side = model.local,
                    modifier = Modifier.weight(1f),
                    actionLabel = "Keep Local",
                    onAction = actions::keepLocal,
                )
                SaveConflictColumn(
                    side = model.server,
                    modifier = Modifier.weight(1f),
                    actionLabel = "Keep Server",
                    onAction = actions::keepServer,
                )
            }
        }

        // Warning note
        item {
            Text(
                text = "The losing copy is preserved before replacement. Neither file is deleted without an explicit backup.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFf44336).copy(alpha = 0.8f),
            )
        }

        // Cancel button — safest initial focus target
        item {
            Row(horizontalArrangement = Arrangement.Center) {
                ConflictButton(
                    text = "Go Back (Cancel)",
                    onClick = actions::cancel,
                    modifier = Modifier
                        .focusRequester(cancelFocusRequester)
                        .onGloballyPositioned {
                            if (!attached) {
                                attached = true
                            }
                        },
                    isPrimary = false,
                    testTag = "conflict_cancel_button",
                )
            }
        }
    }
}

@Composable
private fun SaveConflictColumn(
    side: SaveConflictSide,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Header
        Text(
            text = side.label,
            style = MaterialTheme.typography.titleMedium,
            color = RommTvColors.Romm300,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // Metadata rows
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ConflictMetadataRow(label = "File", value = side.fileName)
            side.saveId?.let { ConflictMetadataRow(label = "Save ID", value = it.toString()) }
            side.hashPrefix?.let { ConflictMetadataRow(label = "Hash", value = it + "\u2026") }
            ConflictMetadataRow(label = "Size", value = side.sizeText ?: "Unknown")
            side.coreId?.let { ConflictMetadataRow(label = "Core", value = it) }
            side.slot?.let { ConflictMetadataRow(label = "Slot", value = it) }
            ConflictMetadataRow(label = "ROM ID", value = side.romId?.toString() ?: "Unknown")
            side.updatedAtText?.let { ConflictMetadataRow(label = "Updated", value = it) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action button
        ConflictButton(
            text = actionLabel,
            onClick = onAction,
            isPrimary = true,
        )
    }
}

@Composable
private fun ConflictMetadataRow(label: String, value: String) {
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
private fun ConflictButton(
    text: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isPrimary) RommTvColors.Romm500 else Color.Transparent,
            )
            .tvFocusRing(isFocused)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable()
            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isPrimary) Color.White else RommTvColors.TextSecondary,
        )
    }
}
