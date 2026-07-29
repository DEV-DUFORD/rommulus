package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import coil.compose.AsyncImage
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.library.RomDetailViewModel
import com.romm.androidtv.library.SectionState
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Native game detail screen (UI_REFACTOR.md section 7.2): hero cover, title
 * and platform, metadata chips, summary, a screenshot shelf, and a Play
 * button. The Play button is an explicit stub for this pass — it does not
 * launch anything (that decision belongs to `LIBRETRO_REFACTOR.md`) — this
 * screen only replaces the *viewing* half of the old WebView flow, not the
 * *launching* half.
 */
@Composable
fun GameDetailScreen(
    viewModel: RomDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi),
    ) {
        when (val section = state) {
            is SectionState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RommTvColors.Romm500)
            }
            is SectionState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Couldn't load this game (${section.error.name.lowercase().replace('_', ' ')})",
                    color = RommTvColors.TextSecondary,
                )
                TextButton(onClick = viewModel::refresh) { Text("Retry", color = RommTvColors.Romm300) }
            }
            is SectionState.Loaded -> GameDetailContent(rom = section.data)
        }
    }
}

@Composable
private fun GameDetailContent(rom: RomDetail) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(RommTvColors.NightLo),
                ) {
                    if (rom.coverUrl != null) {
                        AsyncImage(model = rom.coverUrl, contentDescription = rom.title, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(modifier = Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = rom.title, style = MaterialTheme.typography.headlineMedium, color = RommTvColors.TextPrimary)
                    Text(
                        text = rom.platformDisplayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = RommTvColors.Romm300,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    MetadataChips(rom)
                    if (rom.summary != null) {
                        Text(
                            text = rom.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = RommTvColors.TextSecondary,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    PlayButton()
                }
            }
        }

        if (rom.screenshotUrls.isNotEmpty()) {
            item {
                Text(
                    text = "Screenshots",
                    style = MaterialTheme.typography.titleMedium,
                    color = RommTvColors.TextPrimary,
                    modifier = Modifier.padding(top = 32.dp, bottom = 12.dp),
                )
                TvLazyRow(
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(rom.screenshotUrls) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier
                                .width(280.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(RommTvColors.NightLo),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataChips(rom: RomDetail) {
    val chips = buildList {
        rom.firstReleaseDateEpochMillis?.let { add(formatYear(it)) }
        if (rom.genres.isNotEmpty()) add(rom.genres.joinToString(", "))
        rom.playerCount?.let { add(if (it == "1") "1 player" else "$it players") }
        if (rom.regions.isNotEmpty()) add(rom.regions.joinToString(", "))
        add(formatFileSize(rom.fileSizeBytes))
        rom.averageRating?.let { add("${it.roundToInt()}% rating") }
    }
    if (chips.isEmpty()) return

    Row(
        modifier = Modifier,
    ) {
        Text(
            text = chips.joinToString("  •  "),
            style = MaterialTheme.typography.labelMedium,
            color = RommTvColors.TextSecondary,
        )
    }
}

@Composable
private fun PlayButton() {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) RommTvColors.Romm500 else RommTvColors.Romm600.copy(alpha = 0.6f))
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = RommTvColors.Romm300,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                // Explicit stub for this pass (UI_REFACTOR.md section 7.2): launching is
                // LIBRETRO_REFACTOR.md's concern, not wired here yet.
                onClick = { /* TODO: wire once native/webview launch decision is made */ },
            )
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(text = "▶  Play", style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

private fun formatYear(epochMillis: Long): String =
    SimpleDateFormat("yyyy", Locale.US).format(epochMillis)

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${value.toInt()} ${units[unitIndex]}" else "%.1f %s".format(value, units[unitIndex])
}
