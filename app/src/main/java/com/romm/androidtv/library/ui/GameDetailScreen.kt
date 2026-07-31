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
import com.romm.androidtv.library.isPlatformNativelySupported
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Native game detail screen (UI_REFACTOR.md section 7.2): hero cover, title
 * and platform, metadata chips, summary, a screenshot shelf, and a Play
 * button. The Play button invokes [onPlay] with the RomM ROM ID; the caller
 * is responsible for staging, sync negotiation, conflict/quarantine handling,
 * and native launch (LIBRETRO_REFACTOR.md sections 10–13).
 *
 * @param isStaging When true, the Play button shows "Preparing…" and is disabled.
 * @param errorMessage Transient error message rendered inline below the Play button;
 *   does NOT replace this screen (caller handles blocking overlays separately).
 * @param onDismissError Called when user dismisses the inline error. Retrying via Play also clears it.
 * @param isAuthExpired When true, replaces the Play button with a "Session expired" state
 *   and a "Log in" action. Takes precedence over [errorMessage].
 * @param onLogin Called when user taps "Log in" from the auth-expired state. Does NOT auto-submit credentials.
 * @param onChooseSave Called when the user picks the "Choose Save" affordance next to Play,
 *   to open the save-picker screen (browse all server saves for this ROM and adopt one before launch).
 */
@Composable
fun GameDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: RomDetailViewModel,
    onPlay: (Long) -> Unit,
    isStaging: Boolean = false,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    isAuthExpired: Boolean = false,
    onLogin: () -> Unit = {},
    onChooseSave: (Long) -> Unit = {},
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
            is SectionState.Loaded -> GameDetailContent(
                rom = section.data,
                onPlay = onPlay,
                isStaging = isStaging,
                errorMessage = errorMessage,
                onDismissError = onDismissError,
                isAuthExpired = isAuthExpired,
                onLogin = onLogin,
                onChooseSave = onChooseSave,
            )
        }
    }
}

@Composable
private fun GameDetailContent(
    rom: RomDetail,
    onPlay: (Long) -> Unit,
    isStaging: Boolean,
    errorMessage: String?,
    onDismissError: () -> Unit,
    isAuthExpired: Boolean,
    onLogin: () -> Unit,
    onChooseSave: (Long) -> Unit,
) {
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
                    if (isAuthExpired) {
                        AuthExpiredState(onLogin = onLogin, onDismiss = onDismissError)
                    } else if (!isPlatformNativelySupported(rom.platformSlug)) {
                        // Proactive native "not supported yet" state (LIBRETRO_REFACTOR.md
                        // section 13, Phase 6): checked up front from CoreManifest, not
                        // discovered reactively only after a failed Play attempt.
                        UnsupportedSystemState(platformDisplayName = rom.platformDisplayName)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlayButton(onPlay = { onPlay(rom.id) }, isStaging = isStaging)
                            Spacer(modifier = Modifier.width(12.dp))
                            ChooseSaveButton(onClick = { onChooseSave(rom.id) }, enabled = !isStaging)
                        }
                        if (errorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFf44336),
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                TextButton(onClick = onDismissError) {
                                    Text("Dismiss", color = RommTvColors.Romm300)
                                }
                            }
                        }
                    }
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

/**
 * Proactive native "not supported yet" state (LIBRETRO_REFACTOR.md section
 * 13, Phase 6): shown instead of the Play/Choose Save row whenever
 * [isPlatformNativelySupported] is false for this ROM's platform, so the
 * user never has to press Play to discover a launch will fail. The Play
 * button itself is rendered disabled for a consistent, expected shape on
 * screen; there is no WebView hand-off (LIBRETRO_REFACTOR.md section 1
 * amendment — WebView is deprecated, not a maintained fallback).
 */
@Composable
private fun UnsupportedSystemState(platformDisplayName: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DisabledPlayButton()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Not supported yet — no native emulator core for $platformDisplayName",
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextSecondary,
        )
    }
}

@Composable
private fun DisabledPlayButton() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(RommTvColors.NightLo)
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(
            text = "▶  Play",
            style = MaterialTheme.typography.titleMedium,
            color = RommTvColors.TextSecondary,
        )
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

/**
 * Inline auth-expired state rendered below the ROM metadata.
 * Shows "Session expired" message with a "Log in" action and Dismiss.
 */
@Composable
private fun AuthExpiredState(onLogin: () -> Unit, onDismiss: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Session expired; please log in to continue",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFf44336),
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onLogin) {
                Text("Log in", color = RommTvColors.Romm300)
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = RommTvColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun PlayButton(onPlay: () -> Unit, isStaging: Boolean = false) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isStaging) RommTvColors.NightLo
                else if (isFocused) RommTvColors.Romm500
                else RommTvColors.Romm600.copy(alpha = 0.6f)
            )
            .border(
                width = if (isFocused && !isStaging) 2.dp else 0.dp,
                color = RommTvColors.Romm300,
                shape = RoundedCornerShape(8.dp),
            )
            .then(
                if (!isStaging) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onPlay,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(
            text = if (isStaging) "Preparing…" else "▶  Play",
            style = MaterialTheme.typography.titleMedium,
            color = if (isStaging) RommTvColors.TextSecondary else Color.White,
        )
    }
}

@Composable
private fun ChooseSaveButton(onClick: () -> Unit, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (!enabled) RommTvColors.NightLo
                else if (isFocused) RommTvColors.NightHi
                else RommTvColors.NightLo
            )
            .border(
                width = if (isFocused && enabled) 2.dp else 1.dp,
                color = if (isFocused && enabled) RommTvColors.Romm300 else RommTvColors.TextSecondary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Choose Save",
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) RommTvColors.TextPrimary else RommTvColors.TextSecondary,
        )
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
