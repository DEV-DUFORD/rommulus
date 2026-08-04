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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Native version-picker screen (game-detail "Choose Version" flow): lists every
 * sibling version of the current ROM (e.g. multi-disc, region, or revision
 * variants grouped server-side by shared external metadata ID), each row
 * showing its title with the currently-open version checked. Picking a row
 * reports the user's choice via [onSelect] — the caller (MainActivity) stages
 * and launches that specific rom, mirroring [SavePickerScreen]'s UX.
 */
sealed interface VersionPickerState {
    data object Loading : VersionPickerState
    data class Loaded(val model: VersionPickerUiModel) : VersionPickerState
    data class Error(val message: String) : VersionPickerState
}

@Composable
fun VersionPickerScreen(
    modifier: Modifier = Modifier,
    state: VersionPickerState,
    onSelect: (VersionPickerEntryUiModel) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi),
    ) {
        when (state) {
            is VersionPickerState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RommTvColors.Romm500)
            }
            is VersionPickerState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "Couldn't load versions (${state.message})", color = RommTvColors.TextSecondary)
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
            is VersionPickerState.Loaded -> VersionPickerContent(
                model = state.model,
                onSelect = onSelect,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun VersionPickerContent(
    model: VersionPickerUiModel,
    onSelect: (VersionPickerEntryUiModel) -> Unit,
    onBack: () -> Unit,
) {
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
                    text = "Choose Game File",
                    style = MaterialTheme.typography.headlineSmall,
                    color = RommTvColors.TextPrimary,
                )
                Text(
                    text = model.gameTitle,
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
                text = "No other versions found for this game.",
                style = MaterialTheme.typography.bodyMedium,
                color = RommTvColors.TextSecondary,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(model.entries.size) { index ->
                    val entry = model.entries[index]
                    VersionEntryRow(entry = entry, onClick = { onSelect(entry) })
                }
            }
        }
    }
}

@Composable
private fun VersionEntryRow(entry: VersionPickerEntryUiModel, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
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
                if (entry.isCurrentVersion) {
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
                if (entry.isMainSibling) {
                    Text(
                        text = "Default version",
                        style = MaterialTheme.typography.labelSmall,
                        color = RommTvColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
