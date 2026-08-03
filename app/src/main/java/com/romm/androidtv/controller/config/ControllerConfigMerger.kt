package com.romm.androidtv.controller.config

/**
 * Pure function that merges catalog defaults with persisted overrides.
 *
 * Defaults are evolvable: the catalog may change at any time and the merge picks
 * up the new defaults automatically. Overrides are persisted: only the user's
 * explicit edits are stored. Unknown controls are retained in storage but ignored
 * by the active profile — they never cause a crash or a silent rewrite.
 */
object ControllerConfigMerger {

    /**
     * Merge a [profile]'s defaults with user [overrides] and return the resolved
     * [CoreControllerConfig].
     *
     * For each player index `0` until `profile.playerCount`:
     * 1. Start from `profile.defaults[playerIndex]` (the default [PlayerControllerConfig]).
     * 2. Apply `overrides[playerIndex]`: for each `(controlId, binding)`, if `controlId`
     *    is a **known** control in `profile.controls`, replace that control's binding;
     *    if `controlId` is **not** in `profile.controls` (an unknown or obsolete control
     *    from an older or newer app version), ignore it.
     * 3. If a player index has no defaults entry (defensive; Phase 1 validation should
     *    prevent this), fall back to an empty [PlayerControllerConfig].
     *
     * The resulting [PlayerControllerConfig] covers every control in [profile.controls].
     */
    fun merge(
        profile: CoreControllerProfile,
        overrides: Map<Int, Map<CoreControlId, PhysicalBinding>>,
    ): CoreControllerConfig {
        val knownIds = profile.controls.mapNotNull { it.id }.toSet()

        val players = (0 until profile.playerCount).associateWith { playerIndex ->
            val baseDefaults = profile.defaults[playerIndex] ?: PlayerControllerConfig()
            val playerOverrides = overrides[playerIndex] ?: emptyMap()

            val mergedBindings = baseDefaults.bindings.toMutableMap()
            for ((controlId, binding) in playerOverrides) {
                if (controlId in knownIds) {
                    mergedBindings[controlId] = binding
                }
                // Unknown controlId: silently ignored (retained in storage, not in active profile)
            }

            PlayerControllerConfig(mergedBindings)
        }

        return CoreControllerConfig(profile.coreId, players)
    }
}
