package com.romm.androidtv.controller.config

/**
 * The resolved per-player configuration for a core (catalog defaults merged with persisted overrides).
 *
 * Produced by [ControllerConfigMerger.merge]; consumed by the runtime mapping layer and the
 * controller-settings UI. Each entry in [players] covers every [CoreControlId] declared by
 * the corresponding [CoreControllerProfile].
 */
data class CoreControllerConfig(
    val coreId: String,
    val players: Map<Int, PlayerControllerConfig>,
)

/** True for the app-level pause-menu shortcut, which is never forwarded to a core. */
val CoreControlId.isPauseMenuControl: Boolean
    get() = this == CoreControlId.PAUSE_MENU
