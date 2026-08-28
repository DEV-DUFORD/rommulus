package com.romm.androidtv.controller.config

import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.LogicalControl

/**
 * Describes a single console control within a [CoreControllerProfile].
 *
 * - [id] is the stable, console-semantic [CoreControlId] (e.g., `N64_C_LEFT`, `GENESIS_MODE`).
 * - [label] is the console-native text the user sees in the UI (e.g., "C-Left", "Mode").
 * - [target] is the RetroPad / [LogicalControl] value the core expects. For example,
 *   Mupen64Plus describes N64 A as RetroPad B and C-Down as RetroPad A; an N64 "A Button"
 *   descriptor therefore has `target = LogicalControl.BUTTON_B`.
 * - [inputKind] classifies the input nature for capture behavior.
 * - [highlightRegion] is the artwork region highlighted when this control row gains focus.
 */
data class CoreControlDescriptor(
    val id: CoreControlId,
    val label: String,
    val target: LogicalControl,
    val inputKind: InputKind,
    val highlightRegion: ControllerHighlightRegion,
)

/**
 * Complete controller profile for a single emulator core.
 *
 * Declares the console name, player count, artwork, every control with its
 * RetroPad target mapping, and per-player default bindings.
 */
data class CoreControllerProfile(
    val coreId: String,
    val consoleName: String,
    val consoleSubtitle: String?,
    val playerCount: Int,
    val artwork: ControllerArtwork,
    val controls: List<CoreControlDescriptor>,
    val defaults: Map<Int, PlayerControllerConfig>,
) {
    init {
        require(playerCount in 1..ControllerSlot.SLOT_COUNT) {
            "playerCount must be in 1..${ControllerSlot.SLOT_COUNT}, was $playerCount"
        }
        val expectedKeys = (0 until playerCount).toSet()
        require(defaults.keys == expectedKeys) {
            "defaults keys must equal ${expectedKeys}, was ${defaults.keys}"
        }
        val controlIds = controls.mapNotNull { it.id }
        require(controlIds.distinct().size == controlIds.size) {
            "control IDs must be unique within a profile; duplicates: ${controlIds - controlIds.toSet()}"
        }
    }
}
