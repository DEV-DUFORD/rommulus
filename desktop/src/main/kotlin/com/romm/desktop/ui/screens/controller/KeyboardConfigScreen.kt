package com.romm.desktop.ui.screens.controller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.controller.keyboard.KeyboardMappingRow
import com.romm.desktop.controller.keyboard.keyboardKeyFor
import com.romm.desktop.controller.keyboard.keyboardKeyLabel
import com.romm.desktop.ui.components.DesktopScaledDialogContent
import com.romm.desktop.ui.components.LocalDesktopUiScale
import com.romm.desktop.ui.components.LocalRommulusColors

private data class KeyboardCapture(val target: String, val slot: BindingSlot, val label: String)

@Composable
fun KeyboardConfigScreen(
    coordinator: DesktopAppCoordinator,
    onCaptureActiveChanged: (Boolean) -> Unit = {},
) {
    val coreId = coordinator.selectedKeyboardCoreId ?: return
    val profile = remember(coreId) { checkNotNull(CoreControllerProfiles.byCoreId(coreId)) }
    val repository = coordinator.keyboardMappingRepository
    val rows by repository.observe(coreId).collectAsState()
    var capture by remember { mutableStateOf<KeyboardCapture?>(null) }
    val colors = LocalRommulusColors.current
    val uiScale = LocalDesktopUiScale.current
    LaunchedEffect(capture) {
        onCaptureActiveChanged(capture != null)
    }
    DisposableEffect(Unit) {
        onDispose { onCaptureActiveChanged(false) }
    }
    val captureKeyEvent: (KeyEvent) -> Boolean = { event ->
        val pending = capture
        if (pending == null) {
            false
        } else if (event.type != KeyEventType.KeyDown) {
            true
        } else if (event.key == Key.Escape) {
            capture = null
            true
        } else {
            keyboardKeyFor(event.key)?.let {
                repository.set(coreId, pending.target, pending.slot, it.scancode)
                capture = null
            }
            true
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(colors.nightHi)
            .onPreviewKeyEvent(captureKeyEvent)
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = coordinator::onBack) { Text("Back") }
            Text(
                "${profile.consoleName} Keyboard",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { repository.reset(coreId) }) { Text("Reset to Default") }
            OutlinedButton(onClick = { repository.clear(coreId) }) { Text("Clear Mappings") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("Control", color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text("Primary", color = colors.textSecondary, modifier = Modifier.weight(.8f))
            Text("Secondary", color = colors.textSecondary, modifier = Modifier.weight(.8f))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = KeyboardMappingRow::target) { row ->
                Row(
                    Modifier.fillMaxWidth().background(colors.nightLo).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(row.label, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    BindingButton(
                        row.primaryScancode,
                        Modifier.weight(.8f),
                        onMap = { capture = KeyboardCapture(row.target, BindingSlot.PRIMARY, row.label) },
                        onClear = { repository.set(coreId, row.target, BindingSlot.PRIMARY, null) },
                    )
                    BindingButton(
                        row.secondaryScancode,
                        Modifier.weight(.8f),
                        onMap = { capture = KeyboardCapture(row.target, BindingSlot.SECONDARY, row.label) },
                        onClear = { repository.set(coreId, row.target, BindingSlot.SECONDARY, null) },
                    )
                }
            }
        }
    }

    capture?.let { pending ->
        DialogWindow(
            onCloseRequest = { capture = null },
            state = rememberDialogState(size = DpSize(480.dp * uiScale, 220.dp * uiScale)),
            title = "Map ${pending.label}",
            resizable = false,
            onPreviewKeyEvent = captureKeyEvent,
        ) {
            DesktopScaledDialogContent(scale = uiScale) {
            Surface(color = colors.nightLo, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Text(
                        "Map ${pending.label}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary,
                    )
                    Text(
                        "Press a keyboard key. Escape cancels.",
                        color = colors.textSecondary,
                    )
                    TextButton(
                        onClick = { capture = null },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Cancel")
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun BindingButton(
    scancode: Int?,
    modifier: Modifier,
    onMap: () -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onMap, modifier = Modifier.weight(1f)) {
            Text(keyboardKeyLabel(scancode), maxLines = 1)
        }
        if (scancode != null) {
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}
