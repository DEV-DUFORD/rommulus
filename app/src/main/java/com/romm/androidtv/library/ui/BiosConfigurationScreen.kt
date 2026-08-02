package com.romm.androidtv.library.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.BiosConfigurationState
import com.romm.androidtv.library.BiosConfigurationOption
import com.romm.androidtv.library.BiosConfigurationViewModel

@Composable
fun BiosConfigurationScreen(
    viewModel: BiosConfigurationViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(viewModel.title, style = MaterialTheme.typography.headlineSmall, color = RommTvColors.TextPrimary)
                Text(
                    viewModel.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = RommTvColors.TextSecondary,
                )
            }
            TextButton(onClick = onBack) { Text("Back", color = RommTvColors.TextSecondary) }
        }
        Spacer(modifier = Modifier.height(20.dp))
        when (val current = state) {
            BiosConfigurationState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = RommTvColors.Romm500)
            }
            is BiosConfigurationState.Error -> Column {
                Text(current.message, color = RommTvColors.TextSecondary)
                TextButton(onClick = viewModel::refresh) { Text("Retry", color = RommTvColors.Romm300) }
            }
            is BiosConfigurationState.Loaded -> {
                if (current.options.isEmpty()) {
                    Text(
                        viewModel.emptyMessage,
                        color = RommTvColors.TextSecondary,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.options.size) { index ->
                            val option = current.options[index]
                            BiosOptionRow(
                                option = option,
                                selected = current.selectedFirmwareId == option.firmware.firmwareId,
                                downloading = current.downloadingFirmwareId == option.firmware.firmwareId,
                                enabled = current.downloadingFirmwareId == null,
                                onClick = { viewModel.select(option) },
                            )
                        }
                    }
                }
                current.message?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(it, color = RommTvColors.Romm300, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) RommTvColors.Romm600.copy(alpha = 0.3f) else RommTvColors.NightLo)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = RommTvColors.Romm300,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected) "✓" else "",
                color = RommTvColors.Romm300,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(option.displayName, color = RommTvColors.TextPrimary)
                Text(
                    option.firmware.fileName,
                    color = RommTvColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (downloading) {
                CircularProgressIndicator(color = RommTvColors.Romm500)
            }
        }
    }
}
