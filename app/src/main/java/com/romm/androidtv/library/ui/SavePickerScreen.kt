package com.romm.androidtv.library.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Native save-picker screen (game-detail "Choose Save" flow, Session 15/16 follow-up):
 * lists every server save for one ROM (all cores/devices — SRAM saves are cross-core
 * compatible for the same platform, so no core filter is applied), each row showing
 * filename, core badge, size, and relative timestamp, with the newest autosave for the
 * current game file checked as the default. Picking a row downloads and adopts that specific save via
 * [com.romm.androidtv.romm.save.SaveSyncCoordinator.adoptChosenSave] before launch —
 * this screen itself only reports the user's choice via [onSelect].
 */
sealed interface SavePickerState {
    data object Loading : SavePickerState
    data class Loaded(val model: SavePickerUiModel) : SavePickerState
    data class Error(val message: String) : SavePickerState
}

@Composable
fun SavePickerScreen(
    modifier: Modifier = Modifier,
    state: SavePickerState,
    onSelect: (SavePickerEntryUiModel) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi),
    ) {
        when (state) {
            is SavePickerState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RommTvColors.Romm500)
            }
            is SavePickerState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "Couldn't load saves (${state.message})", color = RommTvColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    TextButton(onClick = onRetry, modifier = Modifier.tvButtonFocus()) {
                        Text("Retry", color = RommTvColors.Romm300)
                    }
                    TextButton(onClick = onBack, modifier = Modifier.tvButtonFocus()) {
                        Text("Back", color = RommTvColors.TextSecondary)
                    }
                }
            }
            is SavePickerState.Loaded -> SavePickerContent(
                model = state.model,
                onSelect = onSelect,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun SavePickerContent(
    model: SavePickerUiModel,
    onSelect: (SavePickerEntryUiModel) -> Unit,
    onBack: () -> Unit,
) {
    val firstEntryFocusRequester = remember(model.entries) { FocusRequester() }
    var firstEntryReady by remember(model.entries) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Choose Save",
                    style = MaterialTheme.typography.headlineSmall,
                    color = RommTvColors.TextPrimary,
                )
                Text(
                    text = model.romTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = RommTvColors.Romm300,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onBack, modifier = Modifier.tvButtonFocus()) {
                Text("Back", color = RommTvColors.TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (model.entries.isEmpty()) {
            Text(
                text = "No saves found for this game yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = RommTvColors.TextSecondary,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(model.entries.size) { index ->
                    val entry = model.entries[index]
                    SaveEntryRow(
                        entry = entry,
                        onClick = { onSelect(entry) },
                        modifier = if (index == 0) {
                            Modifier
                                .focusRequester(firstEntryFocusRequester)
                                .onGloballyPositioned { firstEntryReady = true }
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }

    LaunchedEffect(firstEntryReady) {
        if (firstEntryReady) firstEntryFocusRequester.requestFocus()
    }
}

@Composable
private fun SaveEntryRow(
    entry: SavePickerEntryUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("save_picker_entry_${entry.saveId}")
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) RommTvColors.Romm600.copy(alpha = 0.3f) else RommTvColors.NightLo)
            .tvFocusRing(isFocused)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(28.dp)) {
                if (entry.isDefaultSelection) {
                    Text(text = "✓", color = RommTvColors.Romm300, style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RommTvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.sourceFileLabel != null) {
                    Text(
                        text = "From ${entry.sourceFileLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = RommTvColors.Romm300,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 2.dp)) {
                    val meta = listOfNotNull(entry.coreId, entry.sizeText, entry.updatedAtText)
                    Text(
                        text = meta.joinToString("  •  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = RommTvColors.TextSecondary,
                    )
                }
            }
        }
    }
}
