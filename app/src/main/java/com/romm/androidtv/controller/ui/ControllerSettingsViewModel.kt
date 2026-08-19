package com.romm.androidtv.controller.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.romm.androidtv.controller.capture.ControllerBindingCaptureCoordinator
import com.romm.androidtv.controller.capture.ControllerBindingCaptureState
import com.romm.androidtv.controller.config.BindingLabelFormatter
import com.romm.androidtv.controller.config.BindingAddress
import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.controller.config.ControlBindings
import com.romm.androidtv.controller.config.ControllerConfigRepository
import com.romm.androidtv.controller.config.ControllerHighlightRegion
import com.romm.androidtv.controller.config.CoreControllerConfig
import com.romm.androidtv.controller.config.CoreControllerProfile
import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.InputKind
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.config.isPauseMenuControl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How the user wants to resolve a physical-input conflict within a player profile.
 * A physical input maps to only one target per player (CONTROLLER_SETTINGS.md line 21).
 */
enum class ConflictResolution {
    /** Exchange the two controls' bindings. */
    SWAP,
    /** Overwrite the target, leaving the colliding control unbound. */
    REPLACE,
    /** Discard the capture; keep the old binding. */
    CANCEL,
}

/**
 * Decision produced by [decideApply] when a captured [PhysicalBinding] arrives.
 *
 * - [Direct]: no other control in the player profile holds this binding, so the
 *   binding can be set immediately via [ControllerConfigRepository.setBinding].
 * - [Conflict]: another control already owns this binding; the UI must present a
 *   conflict dialog before applying.
 */
internal sealed interface BindingApplyDecision {
    data object Direct : BindingApplyDecision
    data class Conflict(val conflictingAddress: BindingAddress) : BindingApplyDecision
}

/**
 * Pure conflict-detection seam (unit-testable without Android/Compose).
 *
 * Per the product contract, a physical input maps to only one target within a player
 * profile. Returns [BindingApplyDecision.Conflict] with the colliding control when any
 * control **other than** [targetControlId] in [playerBindings] currently holds
 * [capturedBinding]; otherwise [BindingApplyDecision.Direct].
 */
internal fun decideApply(
    targetAddress: BindingAddress,
    capturedBinding: PhysicalBinding,
    playerBindings: Map<CoreControlId, ControlBindings>,
): BindingApplyDecision {
    val colliding = playerBindings.entries.firstNotNullOfOrNull { (controlId, bindings) ->
        if (controlId.isPauseMenuControl || targetAddress.controlId.isPauseMenuControl) {
            return@firstNotNullOfOrNull null
        }
        bindings.entries()
            .firstOrNull { (slot, binding) ->
                BindingAddress(controlId, slot) != targetAddress && binding == capturedBinding
            }
            ?.let { (slot, _) -> BindingAddress(controlId, slot) }
    }
    return if (colliding != null) {
        BindingApplyDecision.Conflict(colliding)
    } else {
        BindingApplyDecision.Direct
    }
}

/** One displayable binding row for a console control. */
data class ControllerBindingRow(
    val controlId: CoreControlId,
    val label: String,
    val primaryBindingLabel: String,
    val secondaryBindingLabel: String,
    val inputKind: InputKind,
    val highlightRegion: ControllerHighlightRegion,
)

/** A connected physical controller that the host adapter can resolve for a player port. */
data class ConnectedControllerInfo(
    val deviceId: Int,
    val name: String?,
)

internal fun playerControllerLabels(
    devices: List<ConnectedControllerInfo>,
    playerCount: Int,
): List<String?> {
    val names = devices.map { it.name?.takeIf(String::isNotBlank) ?: "Game controller" }
    val totals = names.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return List(playerCount) { playerIndex ->
        val name = names.getOrNull(playerIndex) ?: return@List null
        if (totals.getValue(name) == 1) {
            name
        } else {
            val number = (seen[name] ?: 0) + 1
            seen[name] = number
            "$name #$number"
        }
    }
}

/** Live info for the open capture dialog overlay. */
data class CaptureDialogInfo(
    val controlLabel: String,
    val bindingSlotLabel: String,
    val playerLabel: String,
    val connectedDeviceName: String?,
    val captureState: ControllerBindingCaptureState,
)

/** Pending conflict awaiting the user's Swap / Replace / Cancel decision. */
data class ConflictDialogInfo(
    val targetAddress: BindingAddress,
    val conflictingAddress: BindingAddress,
    val targetControlLabel: String,
    val conflictingControlLabel: String,
)

/** Full UI state emitted by [ControllerSettingsViewModel]. */
data class ControllerConfigUiState(
    val consoleName: String,
    val artworkResourceId: Int,
    val selectedPlayerIndex: Int = 0,
    val playerCount: Int = 1,
    val playerControllerLabels: List<String?> = emptyList(),
    val activePlayerIndex: Int? = null,
    val rows: List<ControllerBindingRow> = emptyList(),
    /** Control row currently focused (drives the artwork highlight). */
    val focusedControlId: CoreControlId? = null,
    /** Null when no capture dialog is shown. */
    val capture: CaptureDialogInfo? = null,
    /** Pending conflict dialog (Swap/Replace/Cancel), null when none. */
    val conflict: ConflictDialogInfo? = null,
    /** True while the "Reset All Controllers" confirmation is pending. */
    val resetAllAwaitingConfirmation: Boolean = false,
    /** True while the selected controller's clear-mappings confirmation is pending. */
    val clearMappingsAwaitingConfirmation: Boolean = false,
    /** Brief non-blocking confirmation such as "A mapped to Button X". */
    val lastAppliedMessage: String? = null,
)

/**
 * Drives the shared controller-configuration screen (CONTROLLER_SETTINGS.md Phase 5).
 *
 * Owns capture lifecycle, conflict detection/resolution, and reset actions. It is
 * host-agnostic: MainActivity and EmulationActivity both construct it with the same
 * contract, per Architecture section 7.
 *
 * Capture/conflict storage decisions live here; raw input parsing lives in the
 * [ControllerBindingCaptureCoordinator].
 */
class ControllerSettingsViewModel(
    private val coreId: String,
    private val profile: CoreControllerProfile,
    private val repository: ControllerConfigRepository,
    private val captureCoordinator: ControllerBindingCaptureCoordinator,
    private val connectedDevicesProvider: () -> List<ConnectedControllerInfo> = { emptyList() },
    private val labelFormatter: (PhysicalBinding) -> String = { BindingLabelFormatter.label(it) },
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ControllerConfigUiState(
            consoleName = profile.consoleName,
            artworkResourceId = com.romm.androidtv.controller.config.ControllerArtworkResolver.resourceIdFor(profile.artwork),
            selectedPlayerIndex = 0,
            playerCount = profile.playerCount,
        ),
    )
    val uiState: StateFlow<ControllerConfigUiState> = _uiState.asStateFlow()

    /** Latest merged config, used for conflict decisions and row labels. */
    private var latestConfig: CoreControllerConfig? = null

    /** The control row a capture was opened for (pending capture or pending conflict). */
    private var pendingAddress: BindingAddress? = null

    /** The captured binding waiting on a conflict decision. */
    private var pendingConflictBinding: PhysicalBinding? = null

    private var messageJob: Job? = null
    private var controllerActivityJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeCore(coreId).collect { config ->
                latestConfig = config
                _uiState.update { it.copy(rows = buildRows(config)) }
            }
        }
        viewModelScope.launch {
            captureCoordinator.state.collect { captureState ->
                _uiState.update { it.copy(capture = it.capture?.copy(captureState = captureState)) }
                handleTerminalCaptureState(captureState)
            }
        }
        refreshConnectedDevices()
    }

    fun refreshConnectedDevices() {
        _uiState.update {
            it.copy(
                playerControllerLabels = playerControllerLabels(
                    devices = connectedDevicesProvider(),
                    playerCount = profile.playerCount,
                ),
            )
        }
    }

    fun onControllerActivity(deviceId: Int) {
        val playerIndex = connectedDevicesProvider().indexOfFirst { it.deviceId == deviceId }
        if (playerIndex !in 0 until profile.playerCount) return

        refreshConnectedDevices()
        controllerActivityJob?.cancel()
        _uiState.update { it.copy(activePlayerIndex = playerIndex) }
        controllerActivityJob = viewModelScope.launch {
            delay(CONTROLLER_ACTIVITY_MILLIS)
            _uiState.update { state ->
                if (state.activePlayerIndex == playerIndex) {
                    state.copy(activePlayerIndex = null)
                } else {
                    state
                }
            }
        }
    }

    fun selectTab(playerIndex: Int) {
        if (playerIndex == _uiState.value.selectedPlayerIndex) return
        // Spec rule 8: cancel capture on selected-tab change.
        if (_uiState.value.capture != null) cancelCapture()
        pendingAddress = null
        pendingConflictBinding = null
        _uiState.update {
            it.copy(
                selectedPlayerIndex = playerIndex,
                capture = null,
                conflict = null,
                rows = latestConfig?.let { config -> buildRows(config, playerIndex) } ?: it.rows,
            )
        }
    }

    /** Marks the row as focused (drives the artwork highlight). */
    fun onRowFocused(controlId: CoreControlId) {
        if (_uiState.value.focusedControlId != controlId) {
            _uiState.update { it.copy(focusedControlId = controlId) }
        }
    }

    /** Opens a capture for [controlId] in the selected player tab. */
    fun onRowSelected(controlId: CoreControlId, bindingSlot: BindingSlot) {
        val state = _uiState.value
        if (state.capture != null || state.conflict != null) return
        val descriptor = profile.controls.firstOrNull { it.id == controlId } ?: return
        val playerIndex = state.selectedPlayerIndex
        val devices = connectedDevicesProvider()
        val deviceLabel = when (devices.size) {
            0 -> null
            1 -> devices.single().name
            else -> "${devices.size} connected controllers"
        }

        pendingAddress = BindingAddress(controlId, bindingSlot)
        pendingConflictBinding = null
        _uiState.update {
            it.copy(
                focusedControlId = controlId,
                capture = CaptureDialogInfo(
                    controlLabel = descriptor.label,
                    bindingSlotLabel = bindingSlot.displayName,
                    playerLabel = playerLabel(playerIndex),
                    connectedDeviceName = deviceLabel,
                    captureState = ControllerBindingCaptureState.AwaitingNeutral,
                ),
            )
        }

        captureCoordinator.beginCapture(
            slotIndex = playerIndex,
            deviceIds = devices.mapTo(mutableSetOf()) { it.deviceId },
            target = when (descriptor.inputKind) {
                InputKind.BUTTON, InputKind.DPAD -> com.romm.androidtv.controller.capture.CaptureTarget.Digital
                InputKind.ANALOG_STICK, InputKind.TRIGGER ->
                    com.romm.androidtv.controller.capture.CaptureTarget.Analog
            },
        )
    }

    fun clearPendingBinding() {
        val address = pendingAddress ?: return
        val playerIndex = _uiState.value.selectedPlayerIndex
        val controlLabel = profile.controls.firstOrNull { it.id == address.controlId }?.label ?: address.controlId.id
        pendingAddress = null
        pendingConflictBinding = null
        captureCoordinator.cancel()
        _uiState.update { it.copy(capture = null, conflict = null) }
        viewModelScope.launch {
            repository.clearBinding(coreId, playerIndex, address.controlId, address.slot)
            showMessage("$controlLabel ${address.slot.displayName.lowercase()} mapping cleared")
        }
    }

    fun dismissCaptureDialog() {
        cancelCapture()
        pendingAddress = null
        pendingConflictBinding = null
        _uiState.update { it.copy(capture = null) }
    }

    /** Applies a chosen conflict resolution. */
    fun resolveConflict(resolution: ConflictResolution) {
        val target = pendingAddress
        val conflicting = _uiState.value.conflict
        val binding = pendingConflictBinding
        val playerIndex = _uiState.value.selectedPlayerIndex
        // Capture is resolved regardless of choice; Cancel keeps the old binding.
        pendingAddress = null
        pendingConflictBinding = null
        _uiState.update { it.copy(capture = null, conflict = null) }

        if (target == null || conflicting == null) return

        viewModelScope.launch {
            when (resolution) {
                ConflictResolution.SWAP -> {
                    repository.swapBindings(coreId, playerIndex, target, conflicting.conflictingAddress)
                    showMessage("${conflicting.targetControlLabel} swapped with ${conflicting.conflictingControlLabel}")
                }
                ConflictResolution.REPLACE -> {
                    if (binding != null) {
                        repository.replaceBinding(coreId, playerIndex, target, binding)
                        showMessage("${conflicting.targetControlLabel} mapped to ${labelFormatter(binding)}")
                    }
                }
                ConflictResolution.CANCEL -> {
                    // Keep old binding; nothing persisted.
                }
            }
        }
    }

    /** Reset Controller — resets only the selected player tab (no confirmation). */
    fun resetPlayer() {
        val playerIndex = _uiState.value.selectedPlayerIndex
        viewModelScope.launch {
            repository.resetPlayer(coreId, playerIndex)
            showMessage("Controller ${playerIndex + 1} reset to defaults")
        }
    }

    fun requestClearMappings() {
        _uiState.update { it.copy(clearMappingsAwaitingConfirmation = true) }
    }

    fun confirmClearMappings() {
        val playerIndex = _uiState.value.selectedPlayerIndex
        _uiState.update { it.copy(clearMappingsAwaitingConfirmation = false) }
        viewModelScope.launch {
            repository.clearPlayerMappings(coreId, playerIndex)
            showMessage("Controller ${playerIndex + 1} mappings cleared")
        }
    }

    fun cancelClearMappings() {
        _uiState.update { it.copy(clearMappingsAwaitingConfirmation = false) }
    }

    /** Reset All Controllers — requires confirmation first. */
    fun requestResetAll() {
        _uiState.update { it.copy(resetAllAwaitingConfirmation = true) }
    }

    fun confirmResetAll() {
        _uiState.update { it.copy(resetAllAwaitingConfirmation = false) }
        viewModelScope.launch {
            repository.resetCore(coreId)
            showMessage("All controllers reset to defaults")
        }
    }

    fun cancelResetAll() {
        _uiState.update { it.copy(resetAllAwaitingConfirmation = false) }
    }

    // ---- Internals ----

    private fun handleTerminalCaptureState(state: ControllerBindingCaptureState) {
        if (_uiState.value.capture == null) return
        when (state) {
            is ControllerBindingCaptureState.Result -> applyCapturedBinding(state.binding)
            ControllerBindingCaptureState.Idle,
            ControllerBindingCaptureState.AwaitingNeutral,
            ControllerBindingCaptureState.Capturing,
            -> Unit
            ControllerBindingCaptureState.Cancelled,
            -> _uiState.update { it.copy(capture = null) }
            ControllerBindingCaptureState.TimedOut,
            ControllerBindingCaptureState.NoDeviceAssigned,
            -> Unit
        }
    }

    private fun applyCapturedBinding(binding: PhysicalBinding) {
        val target = pendingAddress ?: return
        val playerIndex = _uiState.value.selectedPlayerIndex
        val bindings = latestConfig?.players?.get(playerIndex)?.bindings ?: emptyMap()

        when (val decision = decideApply(target, binding, bindings)) {
            BindingApplyDecision.Direct -> {
                pendingAddress = null
                _uiState.update { it.copy(capture = null) }
                viewModelScope.launch {
                    repository.setBinding(coreId, playerIndex, target.controlId, binding, target.slot)
                    val controlLabel = profile.controls.firstOrNull { it.id == target.controlId }?.label
                        ?: target.controlId.id
                    showMessage("$controlLabel mapped to ${labelFormatter(binding)}")
                }
            }
            is BindingApplyDecision.Conflict -> {
                pendingConflictBinding = binding
                val targetLabel = profile.controls.firstOrNull { it.id == target.controlId }?.label
                    ?: target.controlId.id
                val conflictingLabel =
                    profile.controls.firstOrNull { it.id == decision.conflictingAddress.controlId }?.label
                        ?: decision.conflictingAddress.controlId.id
                _uiState.update {
                    it.copy(
                        capture = null,
                        conflict = ConflictDialogInfo(
                            targetAddress = target,
                            conflictingAddress = decision.conflictingAddress,
                            targetControlLabel = "$targetLabel (${target.slot.displayName})",
                            conflictingControlLabel =
                                "$conflictingLabel (${decision.conflictingAddress.slot.displayName})",
                        ),
                    )
                }
            }
        }
    }

    private fun cancelCapture() {
        if (_uiState.value.capture != null) {
            captureCoordinator.cancel()
        }
    }

    private fun buildRows(config: CoreControllerConfig, playerIndex: Int = _uiState.value.selectedPlayerIndex): List<ControllerBindingRow> {
        val bindings = config.players[playerIndex]?.bindings ?: emptyMap()
        return profile.controls.map { desc ->
            ControllerBindingRow(
                controlId = desc.id,
                label = desc.label,
                primaryBindingLabel = bindings[desc.id]?.primary?.let(labelFormatter) ?: "Unmapped",
                secondaryBindingLabel = bindings[desc.id]?.secondary?.let(labelFormatter) ?: "Unmapped",
                inputKind = desc.inputKind,
                highlightRegion = desc.highlightRegion,
            )
        }
    }

    private fun playerLabel(playerIndex: Int): String = "Controller ${playerIndex + 1}"

    /** Sets [ControllerConfigUiState.lastAppliedMessage] and auto-dismisses it shortly after. */
    private fun showMessage(message: String) {
        messageJob?.cancel()
        _uiState.update { it.copy(lastAppliedMessage = message) }
        messageJob = viewModelScope.launch {
            delay(MESSAGE_DISMISS_MILLIS)
            _uiState.update { it.copy(lastAppliedMessage = null) }
        }
    }

    /** Factory matching the codebase's `viewModel(factory = ...)` convention (see SettingsViewModel.Factory). */
    class Factory(
        private val coreId: String,
        private val profile: CoreControllerProfile,
        private val repository: ControllerConfigRepository,
        private val captureCoordinator: ControllerBindingCaptureCoordinator,
        private val connectedDevicesProvider: () -> List<ConnectedControllerInfo> = { emptyList() },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ControllerSettingsViewModel(
                coreId = coreId,
                profile = profile,
                repository = repository,
                captureCoordinator = captureCoordinator,
                connectedDevicesProvider = connectedDevicesProvider,
            ) as T
        }
    }

    private companion object {
        const val CONTROLLER_ACTIVITY_MILLIS = 1000L
        const val MESSAGE_DISMISS_MILLIS = 3000L
    }
}
