package com.romm.desktop.ui.screens.controller

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romm.androidtv.controller.capture.CaptureTarget
import com.romm.androidtv.controller.config.BindingAddress
import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.controller.config.CoreControlDescriptor
import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.CoreControllerConfig
import com.romm.androidtv.controller.config.CoreControllerProfile
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.androidtv.controller.config.InputKind
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.config.isPauseMenuControl
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.controller.DesktopCaptureCoordinator
import com.romm.desktop.controller.DesktopCapturePump
import com.romm.desktop.controller.DesktopCaptureState
import com.romm.desktop.ui.controller.ControllerArtworkResolver
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class PendingCapture(
    val playerIndex: Int,
    val descriptor: CoreControlDescriptor,
    val slot: BindingSlot,
)

private data class PendingConflict(
    val playerIndex: Int,
    val target: BindingAddress,
    val conflicting: BindingAddress,
    val binding: PhysicalBinding,
)

@Composable
fun ControllerConfigScreen(
    coordinator: DesktopAppCoordinator,
    onCaptureActiveChanged: (Boolean) -> Unit = {},
) {
    val coreId = coordinator.selectedControllerCoreId ?: return
    ControllerConfigScreen(coreId, coordinator, coordinator::onBack, onCaptureActiveChanged)
}

@Composable
fun ControllerConfigScreen(
    coreId: String,
    coordinator: DesktopAppCoordinator,
    onBack: () -> Unit,
    onCaptureActiveChanged: (Boolean) -> Unit = {},
) {
    val profile = remember(coreId) {
        CoreControllerProfiles.byCoreId(coreId)
            ?: error("No controller profile for core '$coreId'")
    }
    val repository = coordinator.controllerConfigRepository
    val config by repository.observeCore(coreId).collectAsState(
        initial = CoreControllerConfig(coreId, profile.defaults),
    )
    val scope = rememberCoroutineScope()
    val focusNavigator = LocalFocusNavigator.current
    val captureCoordinator = remember { DesktopCaptureCoordinator(scope) }
    val capturePump = remember {
        DesktopCapturePump(coordinator.controllerInputSource, captureCoordinator, scope)
    }
    val captureState by captureCoordinator.state.collectAsState()

    var selectedPlayer by remember { mutableIntStateOf(0) }
    var focusedControlId by remember { mutableStateOf<CoreControlId?>(null) }
    var pendingCapture by remember { mutableStateOf<PendingCapture?>(null) }
    var pendingConflict by remember { mutableStateOf<PendingConflict?>(null) }
    var clearConfirmation by remember { mutableStateOf(false) }
    var resetAllConfirmation by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val lastFocusedAddress = remember { mutableMapOf<Int, BindingAddress>() }

    val devices = coordinator.controllerInputSource.enumerate()
    val controllerLabels = remember(devices.map { it.signature.name }, profile.playerCount) {
        numberedControllerLabels(devices.map { it.signature.name }, profile.playerCount)
    }
    val activePlayer = devices.indices.firstOrNull()?.takeIf { it < profile.playerCount }

    DisposableEffect(capturePump) {
        capturePump.start()
        onDispose {
            onCaptureActiveChanged(false)
            captureCoordinator.cancel()
            capturePump.stop()
        }
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(2_500)
            feedback = null
        }
    }

    LaunchedEffect(coreId) {
        val first = BindingAddress(profile.controls.first().id, BindingSlot.PRIMARY)
        lastFocusedAddress[0] = first
        val key = "controller-binding-0-${first.controlId.id}-${first.slot.name}"
        repeat(10) {
            if (focusNavigator.focusItem(key)) return@LaunchedEffect
            delay(16)
        }
    }

    fun bindingKey(address: BindingAddress, playerIndex: Int = selectedPlayer): String =
        "controller-binding-$playerIndex-${address.controlId.id}-${address.slot.name}"

    fun restoreBindingFocus(playerIndex: Int, address: BindingAddress) {
        lastFocusedAddress[playerIndex] = address
        scope.launch {
            delay(80)
            focusNavigator.focusItem(bindingKey(address, playerIndex))
        }
    }

    fun finishCapture(message: String? = null) {
        val capture = pendingCapture
        pendingCapture = null
        onCaptureActiveChanged(false)
        if (message != null) feedback = message
        if (capture != null) {
            restoreBindingFocus(
                capture.playerIndex,
                BindingAddress(capture.descriptor.id, capture.slot),
            )
        }
    }

    LaunchedEffect(captureState) {
        val capture = pendingCapture ?: return@LaunchedEffect
        when (val state = captureState) {
            is DesktopCaptureState.Result -> {
                val target = BindingAddress(capture.descriptor.id, capture.slot)
                val playerBindings = config.players[capture.playerIndex]?.bindings.orEmpty()
                val collision = playerBindings.entries.firstNotNullOfOrNull { (controlId, bindings) ->
                    if (controlId.isPauseMenuControl || target.controlId.isPauseMenuControl) {
                        null
                    } else {
                        bindings.entries()
                            .firstOrNull { (slot, binding) ->
                                BindingAddress(controlId, slot) != target && binding == state.binding
                            }
                            ?.let { (slot, _) -> BindingAddress(controlId, slot) }
                    }
                }
                if (collision == null) {
                    repository.setBinding(
                        coreId,
                        capture.playerIndex,
                        capture.descriptor.id,
                        state.binding,
                        capture.slot,
                    )
                    finishCapture(
                        "${capture.descriptor.label} mapped to ${desktopBindingLabel(state.binding)}",
                    )
                } else {
                    pendingConflict = PendingConflict(
                        playerIndex = capture.playerIndex,
                        target = target,
                        conflicting = collision,
                        binding = state.binding,
                    )
                    pendingCapture = null
                    onCaptureActiveChanged(false)
                }
            }
            DesktopCaptureState.TimedOut -> finishCapture("Mapping timed out")
            DesktopCaptureState.NoDeviceAssigned -> finishCapture("Connect a controller to remap inputs")
            DesktopCaptureState.Cancelled -> finishCapture()
            else -> Unit
        }
    }

    val startCapture: (CoreControlDescriptor, BindingSlot) -> Unit = { descriptor, slot ->
        val selectedDevice = coordinator.controllerInputSource.enumerate().getOrNull(selectedPlayer)
        onCaptureActiveChanged(true)
        captureCoordinator.beginCapture(
            slotIndex = selectedPlayer,
            deviceId = selectedDevice?.id,
            target = when (descriptor.inputKind) {
                InputKind.ANALOG_STICK -> CaptureTarget.Analog
                InputKind.TRIGGER -> CaptureTarget.Trigger
                else -> CaptureTarget.Digital
            },
        )
        pendingCapture = PendingCapture(selectedPlayer, descriptor, slot)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            ControllerHeader(
                consoleName = profile.consoleName,
                focusNavigator = focusNavigator,
                onBack = onBack,
                onResetPlayer = {
                    scope.launch {
                        repository.resetPlayer(coreId, selectedPlayer)
                        feedback = "Player ${selectedPlayer + 1} controller reset"
                    }
                },
                onClearMappings = { clearConfirmation = true },
            )
            Spacer(Modifier.height(18.dp))
            PlayerTabs(
                profile = profile,
                selectedPlayer = selectedPlayer,
                activePlayer = activePlayer,
                controllerLabels = controllerLabels,
                focusNavigator = focusNavigator,
                onSelect = { player ->
                    if (player == selectedPlayer) return@PlayerTabs
                    captureCoordinator.cancel()
                    selectedPlayer = player
                    focusedControlId = null
                    scope.launch {
                        delay(80)
                        val address = lastFocusedAddress[player]
                            ?: BindingAddress(profile.controls.first().id, BindingSlot.PRIMARY)
                        focusNavigator.focusItem(bindingKey(address, player))
                    }
                },
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                ControllerArtworkPanel(
                    profile = profile,
                    focusedControlId = focusedControlId,
                    modifier = Modifier.weight(0.4f).fillMaxHeight(),
                )
                BindingList(
                    profile = profile,
                    config = config,
                    selectedPlayer = selectedPlayer,
                    focusNavigator = focusNavigator,
                    onFocused = { descriptor, slot ->
                        focusedControlId = descriptor.id
                        lastFocusedAddress[selectedPlayer] = BindingAddress(descriptor.id, slot)
                    },
                    onCapture = startCapture,
                    onResetAll = { resetAllConfirmation = true },
                    modifier = Modifier.weight(0.6f).fillMaxHeight(),
                )
            }
        }
    }

    feedback?.let { message ->
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 48.dp, end = 48.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 8.dp,
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    pendingCapture?.let { capture ->
        ControllerCaptureDialog(
            playerLabel = "Player ${capture.playerIndex + 1}",
            controlLabel = capture.descriptor.label,
            slotLabel = capture.slot.displayName,
            connectedController = controllerLabels.getOrNull(capture.playerIndex),
            captureState = captureState,
            onCancel = {
                captureCoordinator.cancel()
                finishCapture()
            },
            onClear = {
                scope.launch {
                    repository.clearBinding(
                        coreId,
                        capture.playerIndex,
                        capture.descriptor.id,
                        capture.slot,
                    )
                }
                captureCoordinator.cancel()
                finishCapture("${capture.descriptor.label} mapping cleared")
            },
        )
    }

    pendingConflict?.let { conflict ->
        val targetLabel = profile.controls.first { it.id == conflict.target.controlId }.label
        val conflictingLabel = profile.controls.first { it.id == conflict.conflicting.controlId }.label
        AlertDialog(
            onDismissRequest = {
                pendingConflict = null
                restoreBindingFocus(conflict.playerIndex, conflict.target)
            },
            title = { Text("Input already mapped") },
            text = {
                Text(
                    "${desktopBindingLabel(conflict.binding)} is assigned to " +
                        "$conflictingLabel. Swap the two mappings or replace the old one?",
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        repository.swapBindings(
                            coreId,
                            conflict.playerIndex,
                            conflict.target,
                            conflict.conflicting,
                        )
                        feedback = "$targetLabel swapped with $conflictingLabel"
                    }
                    pendingConflict = null
                    restoreBindingFocus(conflict.playerIndex, conflict.target)
                }) { Text("Swap") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            repository.replaceBinding(
                                coreId,
                                conflict.playerIndex,
                                conflict.target,
                                conflict.binding,
                            )
                            feedback = "$targetLabel mapped to ${desktopBindingLabel(conflict.binding)}"
                        }
                        pendingConflict = null
                        restoreBindingFocus(conflict.playerIndex, conflict.target)
                    }) { Text("Replace") }
                    TextButton(onClick = {
                        pendingConflict = null
                        restoreBindingFocus(conflict.playerIndex, conflict.target)
                    }) { Text("Cancel") }
                }
            },
        )
    }

    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            title = { Text("Clear Player ${selectedPlayer + 1} mappings?") },
            text = { Text("Every mapping for this controller will be set to Unmapped.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        repository.clearPlayerMappings(coreId, selectedPlayer)
                        feedback = "Player ${selectedPlayer + 1} mappings cleared"
                    }
                    clearConfirmation = false
                }) { Text("Clear mappings") }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmation = false }) { Text("Cancel") }
            },
        )
    }

    if (resetAllConfirmation) {
        AlertDialog(
            onDismissRequest = { resetAllConfirmation = false },
            title = { Text("Reset all controllers?") },
            text = { Text("Mappings for every player will return to the defaults.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        repository.resetCore(coreId)
                        feedback = "All controllers reset"
                    }
                    resetAllConfirmation = false
                }) { Text("Reset all") }
            },
            dismissButton = {
                TextButton(onClick = { resetAllConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ControllerHeader(
    consoleName: String,
    focusNavigator: com.romm.desktop.ui.navigation.FocusNavigator,
    onBack: () -> Unit,
    onResetPlayer: () -> Unit,
    onClearMappings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.focusableItem("controller-back", focusNavigator, onBack),
        ) { Text("Back") }
        Text(
            text = "$consoleName Controller",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        TextButton(
            onClick = onResetPlayer,
            modifier = Modifier.focusableItem("controller-reset-player", focusNavigator, onResetPlayer),
        ) { Text("Reset Controller") }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = onClearMappings,
            modifier = Modifier.focusableItem("controller-clear-player", focusNavigator, onClearMappings),
        ) { Text("Clear Mappings") }
    }
}

@Composable
private fun PlayerTabs(
    profile: CoreControllerProfile,
    selectedPlayer: Int,
    activePlayer: Int?,
    controllerLabels: List<String?>,
    focusNavigator: com.romm.desktop.ui.navigation.FocusNavigator,
    onSelect: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        repeat(profile.playerCount) { playerIndex ->
            Tab(
                selected = playerIndex == selectedPlayer,
                onClick = { onSelect(playerIndex) },
                modifier = Modifier
                    .weight(1f)
                    .focusableItem(
                        "controller-player-tab-$playerIndex",
                        focusNavigator,
                    ) { onSelect(playerIndex) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = buildString {
                                append("Player ${playerIndex + 1}")
                                if (playerIndex == activePlayer) append(" • Active")
                            },
                            fontWeight = if (playerIndex == selectedPlayer) FontWeight.Bold else FontWeight.Medium,
                        )
                        Text(
                            text = controllerLabels.getOrNull(playerIndex) ?: "No controller",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ControllerArtworkPanel(
    profile: CoreControllerProfile,
    focusedControlId: CoreControlId?,
    modifier: Modifier = Modifier,
) {
    val artwork = remember(profile.artwork) {
        ControllerArtworkResolver.imageVectorFor(profile.artwork)
    }
    val focused = profile.controls.firstOrNull { it.id == focusedControlId }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            androidx.compose.foundation.Image(
                imageVector = artwork,
                contentDescription = "${profile.consoleName} controller",
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = focused?.label ?: "Select a control",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = profile.consoleSubtitle ?: profile.consoleName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BindingList(
    profile: CoreControllerProfile,
    config: CoreControllerConfig,
    selectedPlayer: Int,
    focusNavigator: com.romm.desktop.ui.navigation.FocusNavigator,
    onFocused: (CoreControlDescriptor, BindingSlot) -> Unit,
    onCapture: (CoreControlDescriptor, BindingSlot) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = config.players[selectedPlayer]
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Control",
                    modifier = Modifier.weight(0.9f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Primary",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Secondary",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
        }
        itemsIndexed(profile.controls, key = { _, descriptor -> descriptor.id.id }) { _, descriptor ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = descriptor.label,
                    modifier = Modifier.weight(0.9f).padding(horizontal = 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                BindingCell(
                    label = desktopBindingLabel(
                        player?.get(descriptor.id, BindingSlot.PRIMARY),
                    ),
                    key = "controller-binding-$selectedPlayer-${descriptor.id.id}-${BindingSlot.PRIMARY.name}",
                    focusNavigator = focusNavigator,
                    onFocus = { onFocused(descriptor, BindingSlot.PRIMARY) },
                    onClick = { onCapture(descriptor, BindingSlot.PRIMARY) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                BindingCell(
                    label = desktopBindingLabel(
                        player?.get(descriptor.id, BindingSlot.SECONDARY),
                    ),
                    key = "controller-binding-$selectedPlayer-${descriptor.id.id}-${BindingSlot.SECONDARY.name}",
                    focusNavigator = focusNavigator,
                    onFocus = { onFocused(descriptor, BindingSlot.SECONDARY) },
                    onClick = { onCapture(descriptor, BindingSlot.SECONDARY) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            TextButton(
                onClick = onResetAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .focusableItem("controller-reset-all", focusNavigator, onResetAll),
            ) {
                Text("Reset All Controllers")
            }
        }
    }
}

@Composable
private fun BindingCell(
    label: String,
    key: String,
    focusNavigator: com.romm.desktop.ui.navigation.FocusNavigator,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .height(48.dp)
            .focusableItem(key, focusNavigator, onClick)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (focused) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = label,
                maxLines = 1,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                color = if (label == "Unmapped") {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun ControllerCaptureDialog(
    playerLabel: String,
    controlLabel: String,
    slotLabel: String,
    connectedController: String?,
    captureState: DesktopCaptureState,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    val phase = when (captureState) {
        DesktopCaptureState.AwaitingNeutral -> "Release all controls"
        DesktopCaptureState.Capturing -> "Press a button or move an axis"
        DesktopCaptureState.NoDeviceAssigned -> "No controller connected"
        DesktopCaptureState.TimedOut -> "Capture timed out"
        else -> "Preparing capture"
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Map $controlLabel") },
        text = {
            Column {
                Text(
                    text = "$playerLabel • $slotLabel",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                connectedController?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (captureState is DesktopCaptureState.AwaitingNeutral ||
                        captureState is DesktopCaptureState.Capturing
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(phase, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "First valid input wins. Capture times out after 15 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { OutlinedButton(onClick = onCancel) { Text("Cancel") } },
        dismissButton = { TextButton(onClick = onClear) { Text("Clear mapping") } },
    )
}

private fun numberedControllerLabels(names: List<String>, playerCount: Int): List<String?> {
    val normalized = names.map { it.ifBlank { "Game controller" } }
    val totals = normalized.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return List(playerCount) { index ->
        val name = normalized.getOrNull(index) ?: return@List null
        if (totals.getValue(name) == 1) {
            name
        } else {
            val number = (seen[name] ?: 0) + 1
            seen[name] = number
            "$name #$number"
        }
    }
}

data class ControllerBindingRowUi(
    val controlId: CoreControlId,
    val label: String,
    val inputKind: InputKind,
    val primaryBindingLabel: String,
    val secondaryBindingLabel: String,
) {
    val bindingLabel: String get() = primaryBindingLabel
}

fun buildBindingRows(
    profile: CoreControllerProfile,
    playerIndex: Int,
    config: CoreControllerConfig,
): List<ControllerBindingRowUi> {
    val bindings = config.players[playerIndex]?.bindings ?: return emptyList()
    return profile.controls.map { descriptor ->
        ControllerBindingRowUi(
            controlId = descriptor.id,
            label = descriptor.label,
            inputKind = descriptor.inputKind,
            primaryBindingLabel = desktopBindingLabel(bindings[descriptor.id]?.primary),
            secondaryBindingLabel = desktopBindingLabel(bindings[descriptor.id]?.secondary),
        )
    }
}

fun desktopBindingLabel(binding: PhysicalBinding?): String = when (binding) {
    null -> "Unmapped"
    is PhysicalBinding.Key -> keyLabel(binding.keyCode)
    is PhysicalBinding.Axis -> axisLabel(binding.axis)
    is PhysicalBinding.AxisDirection -> axisDirectionLabel(binding.axis, binding.polarity)
}

private fun keyLabel(code: Int): String = when (NeutralKey.fromPlatform(code)) {
    NeutralKey.BUTTON_A -> "Button A"
    NeutralKey.BUTTON_B -> "Button B"
    NeutralKey.BUTTON_X -> "Button X"
    NeutralKey.BUTTON_Y -> "Button Y"
    NeutralKey.BUTTON_L1 -> "L1"
    NeutralKey.BUTTON_R1 -> "R1"
    NeutralKey.BUTTON_L2 -> "L2"
    NeutralKey.BUTTON_R2 -> "R2"
    NeutralKey.BUTTON_SELECT -> "Select"
    NeutralKey.BUTTON_START -> "Start"
    NeutralKey.BUTTON_THUMBL -> "L3"
    NeutralKey.BUTTON_THUMBR -> "R3"
    NeutralKey.DPAD_UP -> "D-Pad Up"
    NeutralKey.DPAD_DOWN -> "D-Pad Down"
    NeutralKey.DPAD_LEFT -> "D-Pad Left"
    NeutralKey.DPAD_RIGHT -> "D-Pad Right"
    else -> "Key $code"
}

private fun axisLabel(code: Int): String = when (NeutralAxis.fromPlatform(code)) {
    NeutralAxis.X -> "Left Stick X"
    NeutralAxis.Y -> "Left Stick Y"
    NeutralAxis.RX, NeutralAxis.Z -> "Right Stick X"
    NeutralAxis.RY, NeutralAxis.RZ -> "Right Stick Y"
    NeutralAxis.LTRIGGER, NeutralAxis.BRAKE -> "Left Trigger"
    NeutralAxis.RTRIGGER, NeutralAxis.GAS -> "Right Trigger"
    else -> "Axis $code"
}

private fun axisDirectionLabel(axisCode: Int, polarity: Int): String =
    when (NeutralAxis.fromPlatform(axisCode)) {
        NeutralAxis.X -> if (polarity > 0) "Left Stick Right" else "Left Stick Left"
        NeutralAxis.Y -> if (polarity > 0) "Left Stick Down" else "Left Stick Up"
        NeutralAxis.RX, NeutralAxis.Z -> if (polarity > 0) "Right Stick Right" else "Right Stick Left"
        NeutralAxis.RY, NeutralAxis.RZ -> if (polarity > 0) "Right Stick Down" else "Right Stick Up"
        else -> axisLabel(axisCode)
    }

data class CaptureDialogContent(
    val title: String,
    val body: String,
    val secondary: String,
    val isError: Boolean,
)

const val CAPTURE_BACK_HINT = "Press Back to cancel \u2022 Hold Back to clear this mapping."

fun captureDialogContent(
    state: DesktopCaptureState,
    controlLabel: String,
    playerLabel: String,
): CaptureDialogContent = when (state) {
    DesktopCaptureState.Idle,
    DesktopCaptureState.Cancelled -> CaptureDialogContent("", "", "", false)
    DesktopCaptureState.AwaitingNeutral,
    DesktopCaptureState.Capturing -> CaptureDialogContent(
        "Map $controlLabel",
        "Press a button or move a stick on the controller for $playerLabel.",
        CAPTURE_BACK_HINT,
        false,
    )
    is DesktopCaptureState.Result -> CaptureDialogContent(
        "Map $controlLabel",
        "Captured: ${desktopBindingLabel(state.binding)}",
        "",
        false,
    )
    DesktopCaptureState.TimedOut -> CaptureDialogContent(
        "Map $controlLabel",
        "No input detected",
        CAPTURE_BACK_HINT,
        true,
    )
    DesktopCaptureState.NoDeviceAssigned -> CaptureDialogContent(
        "Map $controlLabel",
        "Connect $playerLabel to remap inputs",
        CAPTURE_BACK_HINT,
        true,
    )
}
