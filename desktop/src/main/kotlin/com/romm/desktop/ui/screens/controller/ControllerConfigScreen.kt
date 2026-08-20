package com.romm.desktop.ui.screens.controller

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.romm.androidtv.controller.capture.CaptureTarget
import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.controller.config.CoreControllerConfig
import com.romm.androidtv.controller.config.CoreControlDescriptor
import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.CoreControllerProfile
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.androidtv.controller.config.InputKind
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.controller.DesktopCaptureCoordinator
import com.romm.desktop.controller.DesktopCapturePump
import com.romm.desktop.controller.DesktopCaptureState
import com.romm.desktop.controller.captureActive
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.TvButton
import com.romm.desktop.ui.components.TvOutlinedButton
import com.romm.desktop.ui.components.tvFocusRing
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player port configured on this screen (E2: one player tab for now, matching the
 * player's single-table model — [com.romm.desktop.player.RetroPadControlMapping.PLAYER_INDEX]).
 */
private const val PLAYER_INDEX = 0

/** Brief non-blocking confirmation under the binding list (mirrors Android's lastAppliedMessage). */
private const val FEEDBACK_DISMISS_MILLIS = 2_000L

/** Back held this long inside the capture dialog clears the mapping instead of cancelling. */
private const val HOLD_BACK_TO_CLEAR_MILLIS = 600L

/** Inline error-feedback color (mirrors the Android 0xFFF44336). */
private val ErrorRed = Color(0xFFF44336)

// --------------------------------------------------------------------------- pure logic
// (extracted from the composables so it is unit-testable without a Compose runtime,
// following the LibraryScreenLogic pattern)

/** One displayable binding row for a console control (desktop: single PRIMARY slot). */
data class ControllerBindingRowUi(
    val controlId: CoreControlId,
    val label: String,
    val inputKind: InputKind,
    val bindingLabel: String,
)

/**
 * Builds the binding rows shown on the controller config screen from a merged
 * [CoreControllerConfig] (catalog defaults + stored overrides), mirroring Android's
 * `ControllerSettingsViewModel.buildRows` — one row per profile control in catalog order.
 */
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
            bindingLabel = desktopBindingLabel(bindings[descriptor.id]?.primary),
        )
    }
}

/**
 * Human-readable label for a [PhysicalBinding] (desktop port of Android's
 * `BindingLabelFormatter`, keyed by the shared neutral platform codes instead of the
 * Android constant literals). `null` renders as "Unmapped" (Android parity).
 */
fun desktopBindingLabel(binding: PhysicalBinding?): String = when (binding) {
    null -> "Unmapped"
    is PhysicalBinding.Key -> keyLabel(binding.keyCode)
    is PhysicalBinding.Axis -> axisLabel(binding.axis)
    is PhysicalBinding.AxisDirection -> axisDirectionLabel(binding.axis, binding.polarity)
}

private fun keyLabel(code: Int): String = when (val key = NeutralKey.fromPlatform(code)) {
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

private fun axisDirectionLabel(axisCode: Int, polarity: Int): String = when (NeutralAxis.fromPlatform(axisCode)) {
    NeutralAxis.X -> if (polarity > 0) "Left Stick Right" else "Left Stick Left"
    NeutralAxis.Y -> if (polarity > 0) "Left Stick Down" else "Left Stick Up"
    NeutralAxis.RX, NeutralAxis.Z -> if (polarity > 0) "Right Stick Right" else "Right Stick Left"
    NeutralAxis.RY, NeutralAxis.RZ -> if (polarity > 0) "Right Stick Down" else "Right Stick Up"
    // Triggers keep their base label for either polarity (Android parity).
    else -> axisLabel(axisCode)
}

/** The rendered content of the capture overlay for one [DesktopCaptureState]. */
data class CaptureDialogContent(
    val title: String,
    val body: String,
    val secondary: String,
    val isError: Boolean,
)

/** Back hint shown while a capture is waiting for input (Android parity wording). */
const val CAPTURE_BACK_HINT = "Press Back to cancel \u2022 Hold Back to clear this mapping."

/**
 * Maps a [DesktopCaptureState] onto the capture overlay's text content, mirroring the
 * Android `ControllerCaptureDialog` state→copy table. Terminal states that the caller is
 * expected to dismiss immediately (Idle / Cancelled) render empty placeholders.
 */
fun captureDialogContent(
    state: DesktopCaptureState,
    controlLabel: String,
    playerLabel: String,
): CaptureDialogContent = when (state) {
    DesktopCaptureState.Idle -> CaptureDialogContent("", "", "", false)

    DesktopCaptureState.AwaitingNeutral,
    DesktopCaptureState.Capturing -> CaptureDialogContent(
        title = "Map $controlLabel",
        body = "Press a button or move a stick on the controller for $playerLabel.",
        secondary = CAPTURE_BACK_HINT,
        isError = false,
    )

    is DesktopCaptureState.Result -> CaptureDialogContent(
        title = "Map $controlLabel",
        body = "Captured: ${desktopBindingLabel(state.binding)}",
        secondary = "",
        isError = false,
    )

    DesktopCaptureState.Cancelled -> CaptureDialogContent("", "", "", false)

    DesktopCaptureState.TimedOut -> CaptureDialogContent(
        title = "Map $controlLabel",
        body = "No input detected",
        secondary = CAPTURE_BACK_HINT,
        isError = true,
    )

    DesktopCaptureState.NoDeviceAssigned -> CaptureDialogContent(
        title = "Map $controlLabel",
        body = "Connect $playerLabel to remap inputs",
        secondary = CAPTURE_BACK_HINT,
        isError = true,
    )
}

// --------------------------------------------------------------------------- screen

/**
 * Desktop controller-configuration screen for one core (E2) — the desktop mirror of
 * Android's [com.romm.androidtv.controller.ui.ControllerConfigScreen], focused on a single
 * player tab (the player's single-table model):
 *
 *  - binding rows from the merged config (catalog defaults + stored overrides via
 *    [DesktopAppCoordinator.controllerConfigRepository]); selecting a row starts a capture
 *    session through [DesktopCaptureCoordinator] fed by the shared JInput poll source;
 *  - a "Reset" header action restoring this player's catalog defaults
 *    (`repository.resetPlayer`);
 *  - Escape / Back returns to the console list via [DesktopAppCoordinator.onBack].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ControllerConfigScreen(
    coordinator: DesktopAppCoordinator,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    val coreId = coordinator.selectedControllerCoreId
    val profile = remember(coreId) { coreId?.let(CoreControllerProfiles::byCoreId) }

    if (profile == null || coreId == null) {
        // Defensive: CONTROLLER_CONFIG without a selection — offer a way back.
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(colors.nightHi)
                .keyboardShortcuts(
                    onBack = { coordinator.onBack() },
                    onSearch = { /* search is not reachable from this screen */ },
                    onQuit = { /* window close is owned by the desktop shell */ },
                )
                .padding(32.dp),
        ) {
            Text("No console selected.", color = colors.textSecondary)
            Spacer(modifier = Modifier.height(16.dp))
            val navigator = LocalFocusNavigator.current
            TvOutlinedButton(
                onClick = { coordinator.onBack() },
                modifier = Modifier.focusableItem("controller-config:missing-back", navigator) {
                    coordinator.onBack()
                },
            ) { Text("Back") }
        }
        return
    }

    val repository = coordinator.controllerConfigRepository
    // observeCore is statically a Flow (the impl is a per-core StateFlow); the plain-Flow
    // collectAsState overload needs an initial — an empty config for this core, which the
    // flow's first emission (the merged config) replaces before first frame settles.
    val config by repository.observeCore(coreId)
        .collectAsState(initial = CoreControllerConfig(coreId, emptyMap()))
    val rows = remember(config) { buildBindingRows(profile, PLAYER_INDEX, config) }
    val playerLabel = "Controller ${PLAYER_INDEX + 1}"

    // ── Capture plumbing: one-shot coordinator + pump fed by the shared JInput source ──
    val uiScope = rememberCoroutineScope()
    val captureCoordinator = remember { DesktopCaptureCoordinator(uiScope) }
    val pump = remember(coordinator.controllerInputSource, captureCoordinator) {
        DesktopCapturePump(coordinator.controllerInputSource, captureCoordinator, uiScope)
    }
    DisposableEffect(pump) {
        pump.start()
        onDispose { pump.stop() }
    }

    var capturingControlId by remember { mutableStateOf<CoreControlId?>(null) }
    var captureStartedAtMillis by remember { mutableStateOf(0L) }
    val captureState by captureCoordinator.state.collectAsState()

    // Brief non-blocking confirmation (mirrors Android's lastAppliedMessage).
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(FEEDBACK_DISMISS_MILLIS)
            feedback = null
        }
    }

    // Terminal capture states: persist the result / clear on hold-Back, then close the overlay.
    LaunchedEffect(captureState, capturingControlId) {
        val controlId = capturingControlId ?: return@LaunchedEffect
        when (val state = captureState) {
            is DesktopCaptureState.Result -> {
                repository.setBinding(coreId, PLAYER_INDEX, controlId, state.binding, BindingSlot.PRIMARY)
                feedback = "Mapped ${controlLabel(profile, controlId)} to ${desktopBindingLabel(state.binding)}"
                capturingControlId = null
            }
            DesktopCaptureState.Cancelled -> capturingControlId = null
            DesktopCaptureState.TimedOut -> {
                feedback = "No input detected \u2014 mapping unchanged"
                capturingControlId = null
            }
            DesktopCaptureState.NoDeviceAssigned -> {
                feedback = "Connect a controller to remap inputs"
                capturingControlId = null
            }
            else -> Unit
        }
    }

    val startCapture: (CoreControlDescriptor) -> Unit = { descriptor ->
        val deviceIds = coordinator.controllerInputSource.enumerate().mapTo(mutableSetOf()) { it.id }
        captureCoordinator.beginCapture(
            slotIndex = PLAYER_INDEX,
            deviceIds = deviceIds,
            target = if (descriptor.inputKind == InputKind.ANALOG_STICK) CaptureTarget.Analog else CaptureTarget.Digital,
        )
        captureStartedAtMillis = System.currentTimeMillis()
        capturingControlId = descriptor.id
    }

    val resetPlayer: () -> Unit = {
        uiScope.launch {
            repository.resetPlayer(coreId, PLAYER_INDEX)
            feedback = "${profile.consoleName} bindings reset to defaults"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.nightHi)
            .keyboardShortcuts(
                onBack = { coordinator.onBack() },
                onSearch = { /* search is not reachable from this screen */ },
                onQuit = { /* window close is owned by the desktop shell */ },
            )
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        // ---- Header: Back / title / Reset ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val navigator = LocalFocusNavigator.current
            TvOutlinedButton(
                onClick = { coordinator.onBack() },
                modifier = Modifier.focusableItem("controller-config:back", navigator) {
                    coordinator.onBack()
                },
            ) { Text("Back") }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(profile.consoleName, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                profile.consoleSubtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
            TvButton(
                onClick = resetPlayer,
                modifier = Modifier.focusableItem("controller-config:reset", navigator, resetPlayer),
            ) { Text("Reset") }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Binding rows (single player tab) ----
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 8.dp),
        ) {
            items(rows, key = { it.controlId.id }) { row ->
                BindingRowItem(
                    row = row,
                    onClick = {
                        profile.controls.firstOrNull { it.id == row.controlId }?.let(startCapture)
                    },
                )
            }
        }

        // ---- Footer hint (Android parity) ----
        Text(
            text = "Select a control to remap it \u2022 Back to return",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        feedback?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.romm300)
        }
    }

    // ---- Capture overlay (mirrors Android's ControllerCaptureDialog) ----
    val capturingDescriptor = capturingControlId?.let { id -> profile.controls.firstOrNull { it.id == id } }
    if (capturingDescriptor != null) {
        DesktopControllerCaptureDialog(
            controlLabel = capturingDescriptor.label,
            playerLabel = playerLabel,
            state = captureState,
            startedAtMillis = captureStartedAtMillis,
            timeoutMillis = DesktopCaptureCoordinator.DEFAULT_TIMEOUT_MILLIS,
            onDismiss = { captureCoordinator.cancel() },
            onClear = {
                uiScope.launch {
                    repository.clearBinding(coreId, PLAYER_INDEX, capturingDescriptor.id, BindingSlot.PRIMARY)
                    feedback = "${capturingDescriptor.label} cleared"
                }
                captureCoordinator.cancel()
            },
        )
    }
}

/** The console-native label for a [CoreControlId] within [profile]. */
fun controlLabel(profile: CoreControllerProfile, controlId: CoreControlId): String =
    profile.controls.firstOrNull { it.id == controlId }?.label ?: controlId.id

// --------------------------------------------------------------------------- row

/**
 * One focusable binding row: the console control label (main) and its current primary
 * binding (right). Selecting it starts a capture session for that control.
 */
@Composable
private fun BindingRowItem(
    row: ControllerBindingRowUi,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) colors.romm600.copy(alpha = 0.3f) else colors.nightLo)
            .tvFocusRing(shape = RoundedCornerShape(8.dp))
            .focusableItem("controller-config:${row.controlId.id}", navigator, onClick)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) colors.romm300 else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.bindingLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

// --------------------------------------------------------------------------- capture dialog

/**
 * Desktop capture overlay (E2) — the desktop mirror of Android's
 * [com.romm.androidtv.controller.ui.ControllerCaptureDialog]: a centered modal telling the
 * user to press a button or move an axis, with a timeout countdown.
 *
 * Like the Android original it renders NO focusable Cancel/OK buttons — Back is the escape:
 *  - a quick Back cancels the capture ([onDismiss] → coordinator.cancel());
 *  - holding Back (keyboard Escape held past [HOLD_BACK_TO_CLEAR_MILLIS]) clears the
 *    selected mapping instead ([onClear]). Controller Back arrives as a single rising edge
 *    through the shell's focus router (JInput emits no auto-repeat), so it always cancels.
 *
 * The dialog is its own desktop window (like [com.romm.desktop.ui.screens.detail.ThemePickerDialog]
 * in SettingsScreen): it installs the shared navigator's spatial-focus override so controller
 * Move/Back route here, and it owns its key handling for the hold-to-clear gesture.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopControllerCaptureDialog(
    controlLabel: String,
    playerLabel: String,
    state: DesktopCaptureState,
    startedAtMillis: Long,
    timeoutMillis: Long,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val content = remember(state, controlLabel, playerLabel) {
        captureDialogContent(state, controlLabel, playerLabel)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        // Route controller Move/Back to this modal while it is up (shell's handleBack then
        // invokes the override's back action — a quick Back cancels the capture).
        val dialogFocusManager = LocalFocusManager.current
        val focusOverrideOwner = remember { Any() }
        DisposableEffect(navigator, dialogFocusManager, focusOverrideOwner) {
            navigator.installSpatialFocusOverride(focusOverrideOwner, dialogFocusManager::moveFocus, onDismiss)
            onDispose { navigator.removeSpatialFocusOverride(focusOverrideOwner) }
        }

        // The modal has no buttons — make the card itself the focus anchor so key events
        // dispatch to it.
        val rootFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { rootFocus.requestFocus() }

        // Timeout countdown: the coordinator's timeout window starts at beginCapture
        // (startedAtMillis), so count down from wall-clock elapsed time.
        var remainingSeconds by remember { mutableStateOf((timeoutMillis / 1000L + 1).toInt()) }
        LaunchedEffect(startedAtMillis, timeoutMillis) {
            while (true) {
                val remaining = timeoutMillis - (System.currentTimeMillis() - startedAtMillis)
                remainingSeconds = ((remaining + 999L) / 1000L).coerceAtLeast(0L).toInt()
                if (remaining <= 0L) break
                delay(250L)
            }
        }

        // Hold-Back-to-clear: a fresh Escape KeyDown arms the hold timer; release before it
        // fires cancels instead. Repeat KeyDown events (auto-repeat) are ignored while held.
        val scope = rememberCoroutineScope()
        var clearJob by remember { mutableStateOf<Job?>(null) }
        var clearedByBackHold by remember { mutableStateOf(false) }
        var backDown by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            onDispose { clearJob?.cancel() }
        }

        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.nightLo)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Escape) return@onPreviewKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (!backDown) {
                                clearedByBackHold = false
                                clearJob?.cancel()
                                clearJob = scope.launch {
                                    delay(HOLD_BACK_TO_CLEAR_MILLIS)
                                    onClear()
                                    clearedByBackHold = true
                                }
                            }
                            backDown = true
                            true
                        }
                        KeyEventType.KeyUp -> {
                            backDown = false
                            clearJob?.cancel()
                            clearJob = null
                            if (!clearedByBackHold) onDismiss()
                            clearedByBackHold = false
                            true
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 36.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = content.body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (content.isError) ErrorRed else colors.textPrimary,
                textAlign = TextAlign.Center,
            )

            if (captureActive(state)) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Times out in $remainingSeconds s",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (content.secondary.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = content.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
